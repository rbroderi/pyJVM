from __future__ import annotations

from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path
import argparse
import fnmatch
import json

from .compiler import CompileError, compile_source


@dataclass
class ProbeResult:
    name: str
    status: str
    detail: str = ""


def probe_file(path: Path) -> ProbeResult:
    try:
        source = path.read_text(encoding="utf-8")
    except Exception as exc:
        return ProbeResult(str(path), "ERROR", f"read failed: {exc}")
    try:
        compile_source(source, class_name="ProbeMain", filename=str(path))
    except CompileError as exc:
        return ProbeResult(str(path), "UNSUPPORTED", str(exc))
    except SyntaxError as exc:
        return ProbeResult(str(path), "SYNTAX", str(exc))
    except Exception as exc:
        return ProbeResult(str(path), "ERROR", f"compiler error: {type(exc).__name__}: {exc}")
    return ProbeResult(str(path), "COMPILES")


def discover(root: Path, pattern: str, limit: int | None) -> list[Path]:
    if root.is_file():
        return [root]
    files = [p for p in sorted(root.rglob("*.py")) if fnmatch.fnmatch(p.name, pattern)]
    return files if limit is None else files[:limit]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="Compile-probe CPython Lib/test files and report pyJVM language gaps"
    )
    ap.add_argument("path", type=Path, help="CPython Lib/test directory or a single test file")
    ap.add_argument("--pattern", default="test_*.py", help="Filename glob for directory scans")
    ap.add_argument("--limit", type=int, help="Probe at most N files")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--show-all", action="store_true", help="Print successful files too")
    ns = ap.parse_args(argv)

    files = discover(ns.path, ns.pattern, ns.limit)
    results = [probe_file(p) for p in files]
    counts = Counter(r.status for r in results)
    reasons = Counter(r.detail for r in results if r.status == "UNSUPPORTED")

    if ns.json:
        print(json.dumps({
            "summary": dict(counts),
            "unsupported_reasons": reasons.most_common(),
            "results": [asdict(r) for r in results],
        }, indent=2))
    else:
        for r in results:
            if ns.show_all or r.status != "COMPILES":
                print(f"{r.status:11} {r.name}" + (f" - {r.detail}" if r.detail else ""))
        print("\nSummary:")
        for status in ("COMPILES", "UNSUPPORTED", "SYNTAX", "ERROR"):
            if counts[status]:
                print(f"  {status:11} {counts[status]}")
        if reasons:
            print("\nTop unsupported reasons:")
            for reason, count in reasons.most_common(20):
                print(f"  {count:5}  {reason}")
    return 1 if counts["ERROR"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
