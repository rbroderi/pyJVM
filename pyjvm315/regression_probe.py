from __future__ import annotations

from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path
import argparse
import ast
import json
import re

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


def _contains_local_class(node: ast.FunctionDef | ast.AsyncFunctionDef) -> bool:
    class Visitor(ast.NodeVisitor):
        found = False
        root = None

        def visit_ClassDef(self, n: ast.ClassDef) -> None:
            self.found = True

        def visit_FunctionDef(self, n: ast.FunctionDef) -> None:
            if n is self.root:
                for stmt in n.body:
                    self.visit(stmt)

        def visit_AsyncFunctionDef(self, n: ast.AsyncFunctionDef) -> None:
            if n is self.root:
                for stmt in n.body:
                    self.visit(stmt)

        def visit_Lambda(self, n: ast.Lambda) -> None:
            return

    visitor = Visitor()
    visitor.root = node
    visitor.visit(node)
    return visitor.found


def probe_case(path: Path, tree: ast.Module, qualname: str, node: ast.FunctionDef | ast.AsyncFunctionDef) -> CaseResult:
    if _contains_local_class(node):
        return CaseResult(
            str(path), qualname, getattr(node, "lineno", 0),
            "UNSUPPORTED", "nested/local class definitions are not implemented",
        )
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


def probe_files(paths: list[Path]) -> dict:
    results: list[CaseResult] = []
    for path in paths:
        try:
            tree, cases = discover_cases(path)
        except Exception as exc:
            results.append(CaseResult(str(path), "<file>", 0, "ERROR", f"{type(exc).__name__}: {exc}"))
            continue
        if not cases:
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


def _read_manifest(manifest: Path, cpython_root: Path) -> list[Path]:
    paths: list[Path] = []
    for raw in manifest.read_text(encoding="utf-8").splitlines():
        item = raw.strip()
        if not item or item.startswith("#"):
            continue
        paths.append(cpython_root / item)
    return paths


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
    ns = ap.parse_args(argv)

    paths = list(ns.paths)
    if ns.manifest:
        paths.extend(_read_manifest(ns.manifest, ns.cpython_root))
    if not paths:
        ap.error("provide at least one path or --manifest")

    report = probe_files(paths)
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

    return 1 if (ns.fail_on_error and report["summary"].get("ERROR", 0)) else 0


if __name__ == "__main__":
    raise SystemExit(main())
