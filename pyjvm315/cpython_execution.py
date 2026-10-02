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
from pathlib import Path
import subprocess
import sys
import tempfile

from .classfile import DEFAULT_TARGET, JAVA_TARGETS
from .cli import build_runtime
from .conformance import Result, compare_file


FIXTURE = '''
class Fixture:
    def assertEqual(self, actual, expected):
        assert actual == expected, "CPython assertEqual failed"
'''


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


def fixture_source(method: ast.FunctionDef, variant: str) -> str:
    if variant not in ('bytes', 'bytearray'):
        raise ValueError("unsupported fixture variant")
    fixture = ast.parse(FIXTURE).body[0]
    fixture.body.insert(0, ast.Assign(targets=[ast.Name(id='type2test', ctx=ast.Store())],
                                      value=ast.Name(id=variant, ctx=ast.Load())))
    fixture.body.append(copy.deepcopy(method))
    driver = ast.parse(f'Fixture().{method.name}()\nprint("CPYTHON CASE PASSED")').body
    return ast.unparse(ast.fix_missing_locations(ast.Module(body=[fixture, *driver], type_ignores=[]))) + '\n'


def run_cases(root: Path, manifest: Path, python: str, *, target: int = DEFAULT_TARGET) -> dict:
    results = []
    with tempfile.TemporaryDirectory(prefix='pyjvm-cpython-execution-') as td:
        work = Path(td)
        runtime = work / 'runtime'
        runtime.mkdir()
        build_runtime(runtime)
        for line in manifest.read_text().splitlines():
            entry = line.strip()
            if not entry or entry.startswith('#'):
                continue
            relative, qualified = entry.split('::', 1)
            method = extract_case(root / relative, qualified)
            for variant in ('bytes', 'bytearray'):
                name = entry + '[' + variant + ']'
                source = work / 'case.py'
                source.write_text(fixture_source(method, variant), encoding='utf-8')
                reference = subprocess.run([python, str(source)], text=True, capture_output=True, timeout=60)
                if reference.returncode != 0:
                    result = Result(name, 'ERROR', 'CPython reference failed: ' + reference.stderr)
                else:
                    result = compare_file(source, python, runtime_cache=runtime, target=target)
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
    parser.add_argument('--output', type=Path)
    ns = parser.parse_args(argv)
    report = run_cases(ns.cpython_root, ns.manifest, ns.python, target=ns.target)
    if ns.output:
        ns.output.parent.mkdir(parents=True, exist_ok=True)
        ns.output.write_text(json.dumps(report, indent=2) + '\n')
    for result in report['results']:
        print(f"{result['status']:11} {result['name']}" + (f" - {result['detail']}" if result['detail'] else ''))
    print(report['summary'])
    return int(any(report['summary'][s] for s in ('UNSUPPORTED', 'FAIL', 'ERROR')))


if __name__ == '__main__':
    raise SystemExit(main())
