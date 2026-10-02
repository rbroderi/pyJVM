"""Verify frame analysis, encoding boundaries, and actual strict JVM loading."""
import shutil
import re
import struct
import subprocess

import pytest

from pyjvm315.classfile import ClassFile, CodeBuilder, JAVA_TARGETS, Method
from pyjvm315.compiler import compile_file, compile_source
from pyjvm315.stackmap import FrameError, TOP, analyze, descriptor_types


def method(builder, *, desc="([Ljava/lang/String;)V", name="main", locals_=8):
    code = builder.finish()
    return Method(name, desc, code, max_locals=locals_, exception_table=builder.exception_table)


@pytest.mark.parametrize("target", JAVA_TARGETS)
def test_target_header_and_stackmap_presence(target):
    code = compile_source("for i in range(3):\n    print(i)\n", target=target)
    assert struct.unpack('>HH', code[4:8]) == (0, target + 44)
    assert (b"StackMapTable" in code) == (target != 5)


def test_invalid_target():
    with pytest.raises(ValueError, match="Unsupported Java target"):
        compile_source("pass", target=18)


def test_descriptor_arrays_and_category_two_arguments():
    args, returns = descriptor_types("([[Ljava/lang/String;JZ)[I")
    assert args == [(7, '[[Ljava/lang/String;'), (4, None), (1, None)]
    assert returns == (7, '[I')
    for invalid in ("(V)V", "(Lmissing)V", "()Vextra", "", "V"):
        with pytest.raises(FrameError):
            descriptor_types(invalid)


def test_join_merges_null_reference_and_unassigned_local():
    cf = ClassFile("Join")
    b = CodeBuilder(cf.cp)
    branch, end = b.label(), b.label()
    b.getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;")
    b.invokevirtual("java/lang/Boolean", "booleanValue", "()Z")
    b.ifeq(branch)
    b.ldc_string("left"); b.astore(1)
    b.ldc_string("only left"); b.astore(2)
    b.goto(end)
    b.mark(branch); b.aconst_null(); b.astore(1)
    b.mark(end); b.aload(1); b.pop(); b.return_()
    _, _, frames, maximum = analyze(cf.cp, cf.name, method(b))
    at_join = dict(frames)[b._labels[end.ident]][0]
    assert at_join[1] == (7, 'java/lang/String')
    assert at_join[2] == TOP
    assert maximum == 1


def test_stack_height_mismatch_is_rejected():
    cf = ClassFile("Bad")
    b = CodeBuilder(cf.cp)
    end = b.label()
    b.getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;")
    b.invokevirtual("java/lang/Boolean", "booleanValue", "()Z")
    b.ifeq(end); b.aconst_null(); b.goto(end)
    b.mark(end); b.return_()
    with pytest.raises(FrameError, match="Inconsistent stack height"):
        analyze(cf.cp, cf.name, method(b))


def test_bad_instruction_and_unassigned_local_are_rejected():
    cf = ClassFile("Bad")
    for code, message in ((b'\xfe', 'Unsupported JVM opcode'),
                          (b'\x2b\x57\xb1', 'Expected reference'),
                          (b'\xa7\x00\x01', 'non-instruction')):
        with pytest.raises(FrameError, match=message):
            analyze(cf.cp, cf.name, Method("main", "([Ljava/lang/String;)V", code))


def test_handler_frame_preserves_locals_and_discards_operand_stack():
    cf = ClassFile("Handler")
    b = CodeBuilder(cf.cp)
    start, end, handler, done = (b.label() for _ in range(4))
    b.ldc_string("saved"); b.astore(1)
    b.mark(start); b.aconst_null(); b.athrow()
    b.mark(end); b.goto(done) # unreachable protected-range end
    b.mark(handler); b.astore(2); b.aload(1); b.pop(); b.goto(done)
    b.mark(done); b.return_()
    b.add_exception_handler(start, end, handler, "java/lang/Throwable")
    _, exceptions, frames, _ = analyze(cf.cp, cf.name, method(b))
    locals_, stack = dict(frames)[b._labels[handler.ident]]
    assert locals_[1] == (7, 'java/lang/String')
    assert stack == ((7, 'java/lang/Throwable'),)
    assert exceptions == [(b._labels[start.ident], b._labels[end.ident],
                           b._labels[handler.ident], 'java/lang/Throwable')]


JDK = bool(shutil.which('javac') and shutil.which('java'))
JDK_MAJOR = int(re.search(r'version "(\d+)', subprocess.run(
    ['java', '-version'], capture_output=True, text=True).stderr)[1]) if JDK else 0


@pytest.mark.skipif(not JDK, reason="JDK required")
@pytest.mark.parametrize("target", [8, 11, 17, pytest.param(21, marks=pytest.mark.skipif(
    JDK_MAJOR < 21, reason="Java 21 JVM required"))])
def test_strict_verifier_dead_blocks_wide_locals_and_uninitialized_objects(tmp_path, target):
    cf = ClassFile("Verify", target=target)
    b = CodeBuilder(cf.cp)
    skip, start, end, handler, done = (b.label() for _ in range(5))
    initialized = b.label()
    b.new("java/lang/Object"); b.dup(); b.goto(initialized)
    b.mark(initialized)
    b.invokespecial("java/lang/Object", "<init>", "()V")
    b.astore(300); b.aload(300); b.pop()
    b.goto(skip)
    b.ldc_string("dead"); b.astore(2) # must get a dead-block frame
    b.mark(skip)
    b.mark(start); b.aconst_null(); b.athrow()
    b.mark(end); b.ldc_string("dead protected tail"); b.pop(); b.goto(done)
    b.mark(handler); b.pop(); b.goto(done)
    b.mark(done); b.return_()
    b.add_exception_handler(start, done, handler, 'java/lang/Throwable')
    cf.add_method(method(b, locals_=301))
    data = cf.to_bytes()
    assert cf.to_bytes() == data # CP/frame serialization must be repeatable
    (tmp_path / 'Verify.class').write_bytes(data)
    result = subprocess.run(['java', '-Xverify:all', '-cp', str(tmp_path), 'Verify'],
                            capture_output=True, text=True, timeout=20)
    assert result.returncode == 0, result.stderr
    verbose = subprocess.run(['javap', '-v', '-cp', str(tmp_path), 'Verify'],
                             capture_output=True, text=True, check=True).stdout
    assert 'StackMapTable: number_of_entries' in verbose
    assert 'stack=2, locals=301' in verbose
    assert 'uninitialized 0' in verbose


def test_target_propagates_to_imports(tmp_path):
    (tmp_path / 'helper.py').write_text('value = 3\n')
    main = tmp_path / 'main.py'
    main.write_text('import helper\nprint(helper.value)\n')
    out = tmp_path / 'out'
    compile_file(main, out, 'Main', target=21)
    classes = list(out.rglob('*.class'))
    assert len(classes) == 2
    assert all(struct.unpack('>H', p.read_bytes()[6:8])[0] == 65 for p in classes)
