# SPDX-License-Identifier: GPL-3.0-or-later
"""Execute selected unchanged CPython test bodies with explicit supported fixtures.

Unlike regression_probe, this lane uses no placeholder imports or bindings and
requires a successful CPython reference run before accepting JVM results.
"""
from __future__ import annotations

import argparse
import ast
import copy
from dataclasses import asdict
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from .classfile import DEFAULT_TARGET, JAVA_TARGETS
from .cli import build_runtime
from .conformance import Result, compare_file


FIXTURE = """
class Fixture:
    def assertEqual(self, actual, expected):
        assert actual == expected, "CPython assertEqual failed"
    def assertIs(self, actual, expected):
        assert actual is expected, "CPython assertIs failed"
    def assertNotEqual(self, actual, expected):
        assert actual != expected, "CPython assertNotEqual failed"
    def assertIn(self, item, container):
        assert item in container, "CPython assertIn failed"
    def fail(self, message):
        raise AssertionError(message)
    def assertNotIn(self, item, container):
        assert item not in container, "CPython assertNotIn failed"
    def assertIsInstance(self, actual, expected):
        assert isinstance(actual, expected), "CPython assertIsInstance failed"
    def assertStartsWith(self, actual, expected):
        assert actual.startswith(expected), "CPython assertStartsWith failed"
    def assertHasAttr(self, actual, name):
        assert hasattr(actual, name), "CPython assertHasAttr failed"
    def assertNotHasAttr(self, actual, name):
        assert not hasattr(actual, name), "CPython assertNotHasAttr failed"
    def assertTrue(self, value):
        assert value, "CPython assertTrue failed"
    def assertFalse(self, value):
        assert not value, "CPython assertFalse failed"
    def assertIsNone(self, value):
        assert value is None, "CPython assertIsNone failed"
    def assertIsNotNone(self, value):
        assert value is not None, "CPython assertIsNotNone failed"
    def assertIsNot(self, actual, expected):
        assert actual is not expected, "CPython assertIsNot failed"
    def assertGreater(self, a, b):
        assert a > b, "CPython assertGreater failed"
    def assertLess(self, a, b):
        assert a < b, "CPython assertLess failed"
    def assertGreaterEqual(self, a, b):
        assert a >= b, "CPython assertGreaterEqual failed"
    def assertLessEqual(self, a, b):
        assert a <= b, "CPython assertLessEqual failed"
    def assertListEqual(self, actual, expected):
        assert actual == expected, "CPython assertListEqual failed"
    def assertTupleEqual(self, actual, expected):
        assert actual == expected, "CPython assertTupleEqual failed"
    def assertDictEqual(self, actual, expected):
        assert actual == expected, "CPython assertDictEqual failed"
    def assertRaisesRegex(self, expected, regex, function=None, *args, **kwargs):
        # the message pattern is not checked; only the exception type (a documented fixture limit)
        return self.assertRaises(expected, function, *args, **kwargs)
    def subTest(self, *args, **kwargs):
        return SubTestContext()
    def assertRaises(self, expected, function=None, *args, **kwargs):
        context = RaisesContext(expected)
        if function is None:
            return context
        with context:
            function(*args, **kwargs)

class SubTestContext:
    def __enter__(self):
        return self
    def __exit__(self, kind, value, trace):
        return False

class RaisesContext:
    def __init__(self, expected):
        self.expected = expected
    def __enter__(self):
        return self
    def __exit__(self, kind, value, trace):
        if kind is None:
            raise AssertionError("expected exception was not raised")
        if not isinstance(value, self.expected):
            return False
        self.exception = value
        return True
"""



# ExceptionIsLikeMixin (test.support.testcase) and the metadata helpers of test_except_star.ExceptionStarTest
EXCGROUP_HELPERS = """
def assertExceptionIsLike(self, exc, template):
    if exc is None and template is None:
        return
    if template is None:
        self.fail(f"unexpected exception: {exc}")
    if exc is None:
        self.fail(f"expected an exception like {template!r}, got None")
    if not isinstance(exc, ExceptionGroup):
        self.assertEqual(exc.__class__, template.__class__)
        self.assertEqual(exc.args[0], template.args[0])
    else:
        self.assertEqual(exc.message, template.message)
        self.assertEqual(len(exc.exceptions), len(template.exceptions))
        for e, t in zip(exc.exceptions, template.exceptions):
            self.assertExceptionIsLike(e, t)

def assertMetadataEqual(self, e1, e2):
    if e1 is None or e2 is None:
        self.assertTrue(e1 is None and e2 is None)
    else:
        self.assertEqual(e1.__context__, e2.__context__)
        self.assertEqual(e1.__cause__, e2.__cause__)
        self.assertEqual(e1.__traceback__, e2.__traceback__)

def assertMetadataNotEqual(self, e1, e2):
    if e1 is None or e2 is None:
        self.assertNotEqual(e1, e2)
    else:
        return not (e1.__context__ == e2.__context__
                    and e1.__cause__ == e2.__cause__
                    and e1.__traceback__ == e2.__traceback__)
"""


def extract_case(path: Path, qualified_name: str) -> ast.FunctionDef:
    parts = qualified_name.split('.')
    if len(parts) != 2:
        raise ValueError("execution manifest requires Class.test_method")
    tree = ast.parse(path.read_text(encoding='utf-8'))
    cls = next((node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == parts[0]), None)
    if cls is None:
        raise ValueError(f"class not found: {qualified_name}")
    method = next((node for node in cls.body if isinstance(node, ast.FunctionDef) and node.name == parts[1]), None)
    if method is None or method.decorator_list:
        raise ValueError(f"missing/decorated test: {qualified_name}")
    return copy.deepcopy(method)


def fixture_source(method: ast.FunctionDef, variant: str, imports: tuple[str, ...] = ()) -> str:
    if variant not in ('bytes', 'bytearray', 'numeric', 'objects', 'set', 'frozenset', 'excgroup'):
        raise ValueError("unsupported fixture variant")
    fixtures = ast.parse(FIXTURE).body
    fixture = fixtures[0]
    if variant in ('bytes', 'bytearray'):
        fixture.body.insert(0, ast.Assign(targets=[ast.Name(id='type2test', ctx=ast.Store())],
                                          value=ast.Name(id=variant, ctx=ast.Load())))
    if variant in ('set', 'frozenset'):
        setup = ast.parse(f"""
thetype = {variant}
basetype = {variant}
def __init__(self):
    self.word = 'simsalabim'
    self.otherword = 'madagascar'
    self.letters = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
    self.s = self.thetype(self.word)
    self.d = {{letter: None for letter in self.word}}
""").body
        fixture.body.extend(setup)
    if variant == 'excgroup':
        fixture.body.extend(ast.parse(EXCGROUP_HELPERS).body)
    fixture.body.append(copy.deepcopy(method))
    driver = ast.parse(f'Fixture().{method.name}()\nprint("CPYTHON CASE PASSED")').body
    header = ''.join(f'import {name}\n' for name in imports)
    return header + ast.unparse(ast.fix_missing_locations(ast.Module(body=[*fixtures, *driver], type_ignores=[]))) + '\n'


def run_cases(root: Path, manifest: Path, python: str, *, target: int = DEFAULT_TARGET, selfhosted: bool = False) -> dict:
    results = []
    with tempfile.TemporaryDirectory(prefix='pyjvm-cpython-execution-') as td:
        work = Path(td)
        runtime = work / 'runtime'
        runtime.mkdir()
        build_runtime(runtime)
        classes = None
        if selfhosted:
            from .selfhost import build_selfhosted
            classes = build_selfhosted(work / 'selfhosted')
        imports: tuple[str, ...] = ()
        for line in manifest.read_text().splitlines():
            entry = line.strip()
            if entry.startswith('@imports'):   # "@imports ast textwrap": modules the following bodies expect at module level
                imports = tuple(entry.split()[1:])
                continue
            if not entry or entry.startswith('#'):
                continue
            fields = entry.split()
            if len(fields) not in (1, 2):
                raise ValueError("execution manifest entry requires a case and optional fixture variant")
            relative, qualified = fields[0].split('::', 1)
            variants = ('bytes', 'bytearray') if len(fields) == 1 else (fields[1],)
            if any(variant not in ('bytes', 'bytearray', 'numeric', 'objects', 'set', 'frozenset', 'excgroup') for variant in variants):
                raise ValueError("unsupported fixture variant")
            method = extract_case(root / relative, qualified)
            for variant in variants:
                name = fields[0] + '[' + variant + ']'
                source = work / 'case.py'
                source.write_text(fixture_source(method, variant, imports), encoding='utf-8')
                reference = subprocess.run([python, str(source)], text=True, capture_output=True, timeout=60)
                if reference.returncode != 0:
                    result = Result(name, 'ERROR', 'CPython reference failed: ' + reference.stderr)
                else:
                    # bodies under an @imports block resolve their stdlib imports from the corpus Lib at run time
                    saved = os.environ.get('PYJVM_STDLIB')
                    if imports:
                        os.environ['PYJVM_STDLIB'] = str(root / 'Lib')
                    try:
                        result = compare_file(source, python, runtime_cache=runtime, target=target, selfhosted=classes)
                    finally:
                        if saved is None:
                            os.environ.pop('PYJVM_STDLIB', None)
                        else:
                            os.environ['PYJVM_STDLIB'] = saved
                    result.name = name
                results.append(result)
    version = subprocess.run([python, '--version'], text=True, capture_output=True, check=True).stdout.strip()
    commit = subprocess.run(['git', '-C', str(root), 'rev-parse', 'HEAD'],
                            text=True, capture_output=True).stdout.strip() or None
    return {'reference_python': version, 'cpython_commit': commit, 'target': target,
            'summary': {status: sum(r.status == status for r in results)
                        for status in ('PASS', 'UNSUPPORTED', 'FAIL', 'ERROR')},
            'results': [asdict(r) for r in results]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cpython-root', required=True, type=Path)
    parser.add_argument('--manifest', type=Path, default=Path('tools/cpython_execution_manifest.txt'))
    parser.add_argument('--python', default=sys.executable)
    parser.add_argument('--target', type=int, choices=JAVA_TARGETS, default=DEFAULT_TARGET)
    parser.add_argument('--selfhosted', action='store_true', help='compile exec/eval/compile code with the self-hosted compiler (no host Python)')
    parser.add_argument('--output', type=Path)
    ns = parser.parse_args(argv)
    report = run_cases(ns.cpython_root, ns.manifest, ns.python, target=ns.target, selfhosted=ns.selfhosted)
    if ns.output:
        ns.output.parent.mkdir(parents=True, exist_ok=True)
        ns.output.write_text(json.dumps(report, indent=2) + '\n')
    for result in report['results']:
        print(f"{result['status']:11} {result['name']}" + (f" - {result['detail']}" if result['detail'] else ''))
    print(report['summary'])
    return int(any(report['summary'][s] for s in ('UNSUPPORTED', 'FAIL', 'ERROR')))


if __name__ == '__main__':
    raise SystemExit(main())
