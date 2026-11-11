# SPDX-License-Identifier: GPL-3.0-or-later
"""Compare pyjvm315.parser.Parser with CPython's ast.parse over a source tree (roadmap P1-05 acceptance)."""
from __future__ import annotations

import ast
sys_stdout_fix = None
import json
import os
import struct
import subprocess
import sys
from pathlib import Path


def conv(v):
    if isinstance(v, ast.AST):
        d = {"_": type(v).__name__}
        for f in v._fields:
            d[f] = conv(getattr(v, f, None))
        for a in v._attributes:
            d[a] = getattr(v, a, None)
        return d
    if isinstance(v, list):
        return [conv(x) for x in v]
    if v is Ellipsis:
        return {"Ellipsis": 1}
    if isinstance(v, str):
        # Java strings are UTF-16: a pair of escaped surrogates is indistinguishable from the astral character
        return v.encode("utf-16", "surrogatepass").decode("utf-16", "surrogatepass")
    if isinstance(v, bool) or v is None or isinstance(v, int):
        return v
    if isinstance(v, float):
        return {"float": struct.unpack("<q", struct.pack("<d", v))[0]}
    if isinstance(v, complex):
        return {"complex": [struct.unpack("<q", struct.pack("<d", x))[0] for x in (v.real, v.imag)]}
    if isinstance(v, bytes):
        return {"bytes": v.hex()}
    raise TypeError(type(v))


def prune(java, host):
    """Drop fields only the Java side knows (newer-version fields such as is_lazy)."""
    if isinstance(java, dict) and isinstance(host, dict):
        for k in [k for k in java if k not in host]:
            del java[k]
        for k in java:
            if k in host:
                java[k] = prune(java[k], host[k])
    elif isinstance(java, list) and isinstance(host, list) and len(java) == len(host):
        return [prune(a, b) for a, b in zip(java, host)]
    return java


def first_diff(a, b, path="$"):
    if type(a) != type(b):
        return path, a, b
    if isinstance(a, dict):
        for k in a:
            if k not in b:
                return f"{path}.{k}", a[k], "<missing>"
            r = first_diff(a[k], b[k], f"{path}.{k}")
            if r: return r
        for k in b:
            if k not in a:
                return f"{path}.{k}", "<missing>", b[k]
    elif isinstance(a, list):
        if len(a) != len(b):
            return path + ".len", len(a), len(b)
        for i, (x, y) in enumerate(zip(a, b)):
            r = first_diff(x, y, f"{path}[{i}]")
            if r: return r
    elif a != b:
        return path, a, b
    return None


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(errors="backslashreplace")
    classes, root = argv[0], Path(argv[1])
    limit = int(argv[2]) if len(argv) > 2 else 10**9
    files = sorted(p for p in root.rglob("*.py") if "site-packages" not in p.parts)[:limit]
    java = subprocess.run(["java", "-Xss64m", "-cp", classes, "pyjvm315.parser.AstDump", *map(str, files)],
                          text=True, capture_output=True, encoding="utf-8")
    got: dict[str, str] = {}
    cur = None
    for line in java.stdout.split("\n"):
        if line.startswith("#FILE\t"):
            cur = line[6:]
        elif cur is not None and line:
            got[cur] = line
    same = diff = agree_err = java_err = host_err = skipped = lenient = 0
    shown = 0
    import collections, re
    cats = collections.Counter(); examples = {}
    limit_show = int(os.environ.get("SHOW", "5"))
    for p in files:
        try:
            text = p.read_text(encoding="utf-8")
            tree = ast.parse(text)
        except UnicodeDecodeError:
            skipped += 1; continue
        except (SyntaxError, ValueError, RecursionError, MemoryError):
            host_err += 1
            if not got.get(str(p), "#").startswith("#"):
                lenient += 1
                if shown < limit_show: shown += 1; print(f"{p}: JAVA ACCEPTS what CPython rejects")
            continue
        line = got.get(str(p), "")
        if line.startswith("#"):
            java_err += 1
            if shown < limit_show: shown += 1; print(f"{p}: JAVA {line[:200]}")
            continue
        want = conv(tree)
        have = prune(json.loads(line), want)
        if have == want:
            same += 1
        else:
            diff += 1
            d0 = first_diff(have, want)
            cat = re.sub(r"\[\d+\]", "[]", d0[0])
            cat = ".".join(cat.split(".")[-3:]) + " :: " + type(d0[1]).__name__
            cats[cat] += 1; examples.setdefault(cat, (str(p), d0))
            if shown < limit_show:
                shown += 1
                d = first_diff(have, want)
                print(f"{p}: {d[0]}\n   java: {str(d[1])[:150]}\n   host: {str(d[2])[:150]}")
    for cat, n in cats.most_common(12):
        ex = examples[cat]
        print(f"  {n:4}  {cat}   e.g. {ex[0].split('/')[-1]}  java={str(ex[1][1])[:50]} host={str(ex[1][2])[:50]}")
    print(f"identical {same}  different {diff}  java-errors {java_err}  host-rejected {host_err} (java wrongly accepts {lenient})  skipped {skipped}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
