from __future__ import annotations

from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path
import argparse
import ast
import json
import hashlib
import re
import subprocess
import sys

from .compiler import CompileError, compile_source


LINE_SUFFIX = re.compile(r" at line \d+$")


@dataclass
class CaseResult:
    file: str
    case: str
    lineno: int
    status: str
    detail: str = ""


def normalize_reason(detail: str) -> str:
    return LINE_SUFFIX.sub("", detail.strip())


def _bound_names(tree: ast.Module) -> set[str]:
    names: set[str] = set()

    def bind_target(target: ast.AST) -> None:
        if isinstance(target, ast.Name):
            names.add(target.id)
        elif isinstance(target, (ast.Tuple, ast.List)):
            for elt in target.elts:
                bind_target(elt)

    for stmt in tree.body:
        if isinstance(stmt, ast.Import):
            for alias in stmt.names:
                names.add(alias.asname or alias.name.split(".")[0])
        elif isinstance(stmt, ast.ImportFrom):
            for alias in stmt.names:
                if alias.name != "*":
                    names.add(alias.asname or alias.name)
        elif isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            names.add(stmt.name)
        elif isinstance(stmt, ast.Assign):
            for target in stmt.targets:
                bind_target(target)
        elif isinstance(stmt, ast.AnnAssign):
            bind_target(stmt.target)
    return names


def _placeholder_prelude(tree: ast.Module) -> list[ast.stmt]:
    out: list[ast.stmt] = []
    for name in sorted(_bound_names(tree)):
        if name.startswith("__") and name.endswith("__"):
            continue
        out.append(
            ast.Assign(
                targets=[ast.Name(id=name, ctx=ast.Store())],
                value=ast.Constant(value=None),
            )
        )
    return out


def _case_function(node: ast.FunctionDef | ast.AsyncFunctionDef, qualname: str) -> ast.FunctionDef | ast.AsyncFunctionDef:
    cls = ast.AsyncFunctionDef if isinstance(node, ast.AsyncFunctionDef) else ast.FunctionDef
    copy = cls(
        name="__probe_case__",
        args=node.args,
        body=node.body or [ast.Pass()],
        decorator_list=[],
        returns=node.returns,
        type_comment=getattr(node, "type_comment", None),
        type_params=getattr(node, "type_params", []),
    )
    return ast.copy_location(copy, node)


def discover_cases(path: Path) -> tuple[ast.Module, list[tuple[str, ast.FunctionDef | ast.AsyncFunctionDef]]]:
    source = path.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(path), mode="exec", feature_version=(3, 15))
    cases: list[tuple[str, ast.FunctionDef | ast.AsyncFunctionDef]] = []
    for stmt in tree.body:
        if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef)) and stmt.name.startswith("test"):
            cases.append((stmt.name, stmt))
        elif isinstance(stmt, ast.ClassDef):
            for item in stmt.body:
                if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)) and item.name.startswith("test"):
                    cases.append((f"{stmt.name}.{item.name}", item))
    return tree, cases


def probe_case(path: Path, tree: ast.Module, qualname: str, node: ast.FunctionDef | ast.AsyncFunctionDef) -> CaseResult:
    prelude = _placeholder_prelude(tree)
    synthetic = ast.Module(body=[*prelude, _case_function(node, qualname)], type_ignores=[])
    ast.fix_missing_locations(synthetic)
    source = ast.unparse(synthetic)
    try:
        compile_source(source, class_name="ProbeCase", filename=f"{path}::{qualname}")
    except CompileError as exc:
        return CaseResult(str(path), qualname, getattr(node, "lineno", 0), "UNSUPPORTED", normalize_reason(str(exc)))
    except SyntaxError as exc:
        return CaseResult(str(path), qualname, getattr(node, "lineno", 0), "SYNTAX", normalize_reason(str(exc)))
    except Exception as exc:
        return CaseResult(
            str(path), qualname, getattr(node, "lineno", 0),
            "ERROR", f"{type(exc).__name__}: {exc}",
        )
    return CaseResult(str(path), qualname, getattr(node, "lineno", 0), "COMPILES")


def probe_files(paths: list[Path], *, selections: dict[Path, list[str]] | None = None) -> dict:
    results: list[CaseResult] = []
    for path in paths:
        try:
            tree, cases = discover_cases(path)
        except Exception as exc:
            results.append(CaseResult(str(path), "<file>", 0, "ERROR", f"{type(exc).__name__}: {exc}"))
            continue
        if selections and path in selections:
            available = dict(cases)
            requested = selections[path]
            for name in requested:
                if name not in available:
                    results.append(CaseResult(str(path), name, 0, "ERROR", "selected case not found"))
            cases = [(name, available[name]) for name in requested if name in available]
        if not cases:
            if selections and path in selections:
                continue
            results.append(CaseResult(str(path), "<no-tests>", 0, "NO_CASES", ""))
            continue
        for qualname, node in cases:
            results.append(probe_case(path, tree, qualname, node))

    counts = Counter(r.status for r in results)
    reasons = Counter(r.detail for r in results if r.status == "UNSUPPORTED")
    files = Counter(r.file for r in results if r.status == "UNSUPPORTED")
    errors = Counter(r.detail for r in results if r.status == "ERROR")
    return {
        "summary": dict(counts),
        "unsupported_reasons": [{"reason": reason, "count": count} for reason, count in reasons.most_common()],
        "error_reasons": [{"reason": reason, "count": count} for reason, count in errors.most_common()],
        "unsupported_files": [{"file": file, "count": count} for file, count in files.most_common()],
        "results": [asdict(r) for r in results],
    }


def _read_manifest(manifest: Path, cpython_root: Path) -> tuple[list[Path], dict[Path, list[str]]]:
    paths: list[Path] = []
    selections: dict[Path, list[str]] = {}
    for raw in manifest.read_text(encoding="utf-8").splitlines():
        item = raw.strip()
        if not item or item.startswith("#"):
            continue
        relative, separator, case = item.partition("::")
        path = cpython_root / relative
        if separator and not case:
            raise ValueError(f"empty case selection: {item}")
        if path in paths:
            if not separator or path not in selections:
                raise ValueError(f"mixed or duplicate whole-file selection: {item}")
            if case in selections[path]:
                raise ValueError(f"duplicate selected case: {item}")
        else:
            paths.append(path)
        if separator:
            selections.setdefault(path, []).append(case)
    return paths, selections


def baseline_regressions(report: dict, baseline: dict) -> list[str]:
    current = {f"{r['file']}::{r['case']}": r['status'] for r in report['results']}
    expected = baseline['statuses']
    failures = []
    if current.keys() != expected.keys():
        failures.append("selected case identities differ from baseline")
    for field in ('cpython_commit', 'manifest_sha256'):
        if report.get(field) != baseline.get(field):
            failures.append(f"{field} differs from baseline")
    for name, status in expected.items():
        if status == 'COMPILES' and current.get(name) != 'COMPILES':
            failures.append(f"previously compiling case regressed: {name}")
    return failures


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="Case-level compile probe for selected CPython regression tests"
    )
    ap.add_argument("paths", nargs="*", type=Path, help="CPython test files to probe")
    ap.add_argument("--manifest", type=Path, help="Text file of paths relative to --cpython-root")
    ap.add_argument("--cpython-root", type=Path, default=Path("."), help="CPython checkout root for manifest entries")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--output", type=Path, help="Write JSON report to this path")
    ap.add_argument("--top", type=int, default=25, help="Number of unsupported/error reasons to print")
    ap.add_argument("--fail-on-error", action="store_true", help="Exit non-zero when compiler errors are observed")
    ap.add_argument("--min-compiles", type=int, default=0, help="Fail if fewer cases compile than this pinned baseline")
    ap.add_argument("--expect-cases", type=int, help="Fail if the selected corpus cardinality changes")
    ap.add_argument("--baseline", type=Path, help="Pinned identity/status baseline; reject previously compiling case regressions")
    ns = ap.parse_args(argv)

    paths = list(ns.paths)
    selections = {}
    if ns.manifest:
        try:
            manifest_paths, selections = _read_manifest(ns.manifest, ns.cpython_root)
        except ValueError as exc:
            ap.error(str(exc))
        if set(paths) & set(manifest_paths):
            ap.error("positional files duplicate manifest selections")
        paths.extend(manifest_paths)
    if not paths:
        ap.error("provide at least one path or --manifest")

    report = probe_files(paths, selections=selections)
    if ns.manifest:
        for result in report['results']:
            if Path(result['file']) in manifest_paths:
                result['file'] = str(Path(result['file']).relative_to(ns.cpython_root))
        report['manifest_sha256'] = hashlib.sha256(ns.manifest.read_bytes()).hexdigest()
        commit = subprocess.run(['git', '-C', str(ns.cpython_root), 'rev-parse', 'HEAD'],
                                text=True, capture_output=True)
        report['cpython_commit'] = commit.stdout.strip() if commit.returncode == 0 else None
    failures = baseline_regressions(report, json.loads(ns.baseline.read_text())) if ns.baseline else []
    if ns.expect_cases is not None and len(report['results']) != ns.expect_cases:
        failures.append(f"expected {ns.expect_cases} cases, found {len(report['results'])}")
    report['baseline_regressions'] = failures
    encoded = json.dumps(report, indent=2, sort_keys=True)
    if ns.output:
        ns.output.parent.mkdir(parents=True, exist_ok=True)
        ns.output.write_text(encoded + "\n", encoding="utf-8")

    if ns.json:
        print(encoded)
    else:
        summary = report["summary"]
        total = sum(summary.values())
        print(f"Cases: {total}")
        for key in ("COMPILES", "UNSUPPORTED", "SYNTAX", "ERROR", "NO_CASES"):
            if summary.get(key):
                print(f"  {key:11} {summary[key]}")
        reasons = report["unsupported_reasons"]
        if reasons:
            print("\nTop unsupported reasons:")
            for item in reasons[: ns.top]:
                print(f"  {item['count']:5}  {item['reason']}")
        errors = report["error_reasons"]
        if errors:
            print("\nTop compiler-error reasons:")
            for item in errors[: ns.top]:
                print(f"  {item['count']:5}  {item['reason']}")

    below_baseline = report["summary"].get("COMPILES", 0) < ns.min_compiles
    if below_baseline:
        print(f"Compilation coverage fell below baseline {ns.min_compiles}", file=sys.stderr)
    for failure in failures:
        print(failure, file=sys.stderr)
    return int(bool(failures) or below_baseline or (ns.fail_on_error and
               any(report['summary'].get(status, 0) for status in ('ERROR', 'SYNTAX', 'NO_CASES'))))


if __name__ == "__main__":
    raise SystemExit(main())
