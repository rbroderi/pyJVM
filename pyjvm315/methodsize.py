"""Method-size lint for compiled JVM class files.

HotSpot refuses to JIT-compile methods whose bytecode exceeds 8,000 bytes
(``-XX:-DontCompileHugeMethods``). The runtime lost 4.84x on collections when
its dispatcher crossed that limit, so CI fails on any method above the limit.
Usage: ``python -m pyjvm315.methodsize <dir-or-class-or-jar>... [--limit N]``
"""
from __future__ import annotations

import argparse
import io
import struct
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path

HUGE_METHOD_LIMIT = 8000


@dataclass(frozen=True)
class MethodSize:
    class_name: str
    method: str
    descriptor: str
    code_length: int


def _read_pool(data: memoryview, pos: int) -> tuple[list, int]:
    (count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    pool: list = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            (n,) = struct.unpack_from(">H", data, pos)
            pool[i] = ("utf8", bytes(data[pos + 2:pos + 2 + n]).decode("utf-8", "replace"))
            pos += 2 + n
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            (idx,) = struct.unpack_from(">H", data, pos)
            pool[i] = ("ref", idx)
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        i += 1
    return pool, pos


def _skip_attributes(data: memoryview, pos: int) -> int:
    (n,) = struct.unpack_from(">H", data, pos)
    pos += 2
    for _ in range(n):
        (length,) = struct.unpack_from(">I", data, pos + 2)
        pos += 6 + length
    return pos


def method_sizes(class_bytes: bytes) -> list[MethodSize]:
    data = memoryview(class_bytes)
    if struct.unpack_from(">I", data, 0)[0] != 0xCAFEBABE:
        raise ValueError("not a class file")
    pool, pos = _read_pool(data, 8)
    utf = lambda i: pool[i][1]
    _flags, this_class = struct.unpack_from(">HH", data, pos)
    class_name = utf(pool[this_class][1]).replace("/", ".")
    pos += 6  # flags, this, super
    (ifaces,) = struct.unpack_from(">H", data, pos)
    pos += 2 + 2 * ifaces
    (fields,) = struct.unpack_from(">H", data, pos)
    pos += 2
    for _ in range(fields):
        pos = _skip_attributes(data, pos + 6)
    (methods,) = struct.unpack_from(">H", data, pos)
    pos += 2
    out: list[MethodSize] = []
    for _ in range(methods):
        _flags, name_i, desc_i, attr_n = struct.unpack_from(">HHHH", data, pos)
        pos += 8
        for _ in range(attr_n):
            attr_name, length = struct.unpack_from(">HI", data, pos)
            body = pos + 6
            if utf(attr_name) == "Code":
                (code_len,) = struct.unpack_from(">I", data, body + 4)
                out.append(MethodSize(class_name, utf(name_i), utf(desc_i), code_len))
            pos = body + length
    return out


def _iter_class_bytes(path: Path):
    if path.is_dir():
        for p in sorted(path.rglob("*.class")):
            yield str(p), p.read_bytes()
    elif path.suffix in (".jar", ".zip"):
        with zipfile.ZipFile(path) as z:
            for n in sorted(z.namelist()):
                if n.endswith(".class"):
                    yield f"{path}!{n}", z.read(n)
    else:
        yield str(path), path.read_bytes()


def check(paths: list[Path], limit: int = HUGE_METHOD_LIMIT) -> tuple[list[MethodSize], list[MethodSize]]:
    """Return (all methods, methods over the limit)."""
    allm: list[MethodSize] = []
    for p in paths:
        for _label, blob in _iter_class_bytes(p):
            allm.extend(method_sizes(blob))
    return allm, [m for m in allm if m.code_length > limit]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("paths", nargs="*", type=Path, help="class files, directories or jars")
    ap.add_argument("--limit", type=int, default=HUGE_METHOD_LIMIT)
    ap.add_argument("--top", type=int, default=5, help="show the N largest methods")
    ap.add_argument("--runtime", action="store_true", help="compile the bundled runtime and lint it")
    ns = ap.parse_args(argv)
    paths = list(ns.paths)
    tmp = None
    if ns.runtime:
        from .cli import build_runtime
        tmp = tempfile.TemporaryDirectory()
        build_runtime(Path(tmp.name))
        paths.append(Path(tmp.name))
    if not paths:
        ap.error("provide paths or --runtime")
    allm, bad = check(paths, ns.limit)
    for m in sorted(allm, key=lambda m: -m.code_length)[: ns.top]:
        print(f"  {m.code_length:6}  {m.class_name}.{m.method}{m.descriptor}")
    for m in bad:
        print(f"method-size: {m.class_name}.{m.method}{m.descriptor} is {m.code_length} bytes (limit {ns.limit})",
              file=sys.stderr)
    print(f"{len(allm)} methods checked, {len(bad)} over {ns.limit} bytes")
    if tmp:
        tmp.cleanup()
    return int(bool(bad))


if __name__ == "__main__":
    raise SystemExit(main())
