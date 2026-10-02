import ast

import pytest

from pyjvm315.classfile import ConstantPool
from pyjvm315.compiler import compile_source
from pyjvm315.cpython_execution import extract_case, fixture_source
from pyjvm315.regression_probe import probe_files


def test_modified_utf8_nul_supplementary_and_surrogate():
    cp = ConstantPool()
    index = cp.utf8('\0😀\ud800')
    payload = cp.entries[index - 1]
    assert payload == b'\x01\x00\x0b\xc0\x80\xed\xa0\xbd\xed\xb8\x80\xed\xa0\x80'
    assert cp.utf8('\0😀\ud800') == index


def test_probe_attempts_local_classes_and_control_flow_definitions(tmp_path):
    sample = tmp_path / 'test_local.py'
    sample.write_text('''
def test_local(value):
    class Local:
        def get(self):
            return value
    for number in range(3):
        if number:
            def choose():
                return Local
    return Local
''')
    report = probe_files([sample])
    assert report['summary'] == {'COMPILES': 1}


def test_generator_expression_preserves_outer_loop_and_cleanup_state():
    code = '''
class Context:
    def __enter__(self): return self
    def __exit__(self, kind, value, trace): return False

def run():
    for i in range(3):
        with Context():
            value = sum(x for x in range(i))
            def inner(): return value
            if i: continue
        if value: break
'''
    assert compile_source(code).startswith(b'\xca\xfe\xba\xbe')


def test_local_generator_method_does_not_make_factory_a_generator():
    from pyjvm315.compiler import Compiler
    compiler = Compiler('Main')
    compiler.compile('''
def make(value):
    class Local:
        def values(self):
            yield value
    return Local
''')
    assert not compiler.functions['make'].is_generator
    assert any(info.is_generator for info in compiler.function_infos.values())


def test_cpython_fixture_preserves_body_and_has_no_placeholder_imports(tmp_path):
    source = tmp_path / 'test_body.py'
    source.write_text('''
import unsupported_dependency
class Actual:
    def test_value(self):
        self.assertEqual(ord(b"A"), 65)
''')
    method = extract_case(source, 'Actual.test_value')
    driver = ast.parse(fixture_source(method, 'bytes'))
    extracted = next(node for node in driver.body[0].body
                     if isinstance(node, ast.FunctionDef) and node.name == 'test_value')
    assert ast.dump(method.body[0]) == ast.dump(extracted.body[0])
    assert not any(isinstance(node, (ast.Import, ast.ImportFrom)) for node in ast.walk(driver))
    assert not any(isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant)
                   and node.value.value is None for node in ast.walk(driver))


def test_cpython_fixture_rejects_missing_or_decorated_tests(tmp_path):
    source = tmp_path / 'test_body.py'
    source.write_text('class Actual:\n    @skip\n    def test_value(self): pass\n')
    with pytest.raises(ValueError, match='missing/decorated'):
        extract_case(source, 'Actual.test_value')
    with pytest.raises(ValueError, match='class not found'):
        extract_case(source, 'Unknown.test_value')


def test_numeric_fixture_preserves_body_without_byte_type_binding():
    method = ast.parse('def test_value(self):\n    self.assertNotEqual(hash(1j), hash(0j))\n').body[0]
    driver = ast.parse(fixture_source(method, 'numeric'))
    fixture = driver.body[0]
    extracted = next(node for node in fixture.body
                     if isinstance(node, ast.FunctionDef) and node.name == 'test_value')
    assert ast.dump(method.body[0]) == ast.dump(extracted.body[0])
    assert not any(isinstance(node, ast.Assign) for node in fixture.body)


def test_probe_coverage_floor_fails_on_regression(tmp_path, capsys):
    from pyjvm315.regression_probe import main
    source = tmp_path / 'test_floor.py'
    source.write_text('def test_ok():\n    assert 1 == 1\n')
    assert main([str(source), '--min-compiles', '1']) == 0
    assert main([str(source), '--min-compiles', '2']) == 1
    assert 'below baseline 2' in capsys.readouterr().err


@pytest.mark.parametrize('body,exception', [('pass', 'AssertionError'), ('raise ValueError("wrong")', 'ValueError')])
def test_execution_fixture_does_not_accept_absent_or_wrong_exceptions(body, exception):
    import subprocess
    import sys
    method = ast.parse('def test_case(self):\n    def operation():\n        ' + body +
                       '\n    self.assertRaises(TypeError, operation)\n').body[0]
    result = subprocess.run([sys.executable, '-c', fixture_source(method, 'bytes')],
                            text=True, capture_output=True, timeout=20)
    assert result.returncode != 0
    assert exception in result.stderr
    assert 'CPYTHON CASE PASSED' not in result.stdout
