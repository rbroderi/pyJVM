# SPDX-License-Identifier: GPL-3.0-or-later
"""Exhaustive CJK codec check: the running CPython against runtime/pyjvm315/runtime/CjkCodec.java.

Usage: python tools/cjk_check.py [--dump DIR] [codec ...]      (run it under each lane's interpreter)

Both sides sweep the same domains (see tools/CjkCheck.java for their definition) and print a SHA-256 per sweep; any
difference is reported, and with --dump the lines of both sides are written for diffing. Needs javac on PATH.
"""
from __future__ import annotations

import hashlib
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cjk_gen  # noqa: E402
import cjk_tables  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RT = ROOT / "runtime" / "pyjvm315" / "runtime"
FOURTH = [0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x7F, 0x80, 0xFF]


def dec_line(name: str, d: bytes) -> tuple[str, str]:
    try:
        r = "o" + ",".join("%x" % ord(c) for c in d.decode(name))
        inc = False
    except UnicodeDecodeError as e:
        inc = e.reason == cjk_gen.INCOMPLETE
        r = "e%d,%d,%s" % (e.start, e.end, "i" if inc else "c")
    return d.hex() + " " + r, inc


def decode_lines(name: str):
    for a in range(256):
        yield dec_line(name, bytes([a]))[0]
    for a in range(256):
        for b in range(256):
            yield dec_line(name, bytes([a, b]))[0]
    three = []
    for a in range(256):
        for b in range(256):
            if not dec_line(name, bytes([a, b]))[1]:
                continue
            for c in range(256):
                line, inc = dec_line(name, bytes([a, b, c]))
                yield line
                if inc:
                    three.append((a, b, c))
    for a, b, c in three:
        for x in FOURTH:
            yield dec_line(name, bytes([a, b, c, x]))[0]
    if name == "hz":        # inside a ~{ ... } run
        for a in range(256):
            yield dec_line(name, b"~{" + bytes([a]))[0]
        for a in range(256):
            for b in range(256):
                yield dec_line(name, b"~{" + bytes([a, b]))[0]
    if name == "euc_kr":    # the eight-byte Hangul make-up sequences
        l, f = 0xA4, 0xD4
        for x in range(0xA0, 256):
            for y in range(0xA0, 256):
                for z in range(0xA0, 256):
                    yield dec_line(name, bytes([l, f, l, x, l, y, l, z]))[0]
        base = [l, f, l, 0xA1, l, 0xBF, l, 0xA1]
        for mark in range(-1, 3):
            b = list(base)
            if mark >= 0:
                b[2 + 2 * mark] = 0xA5
            for k in range(2, 9):
                yield dec_line(name, bytes(b[:k]))[0]


def enc_line(name: str, s: str) -> str:
    try:
        return s.encode(name).hex()
    except UnicodeEncodeError as e:
        return "e%d,%d" % (e.start, e.end)


def encode_lines(name: str, table: dict | None):
    for cp in range(0x110000):
        yield "%x %s" % (cp, enc_line(name, chr(cp)))
    if name == "hz":
        for cp in range(0x110000):
            c = chr(cp)
            for s in (c + "a", "a" + c, c + c):
                yield "s%s %s" % ("".join("%x." % ord(x) for x in s), enc_line(name, s))
    if table:
        firsts, seconds = [], []
        for a, b in table["pairs"]:
            if a not in firsts:
                firsts.append(a)
            if b not in seconds:
                seconds.append(b)
        for a in firsts:
            A = chr(a)
            strs = [A + chr(b) for b in seconds + [0x41, 0x3042, a]] + [A, "A" + A, A + "ÿ"]
            for s in strs:
                yield "s%s %s" % ("".join("%x." % ord(c) for c in s), enc_line(name, s))


def main() -> int:
    args = sys.argv[1:]
    dump = None
    if args[:1] == ["--dump"]:
        dump = Path(args[1]); dump.mkdir(parents=True, exist_ok=True); args = args[2:]
    names = args or cjk_gen.STATELESS
    work = Path(tempfile.mkdtemp(prefix="cjkcheck"))
    try:
        subprocess.run(["javac", "-d", str(work), str(RT / "CjkCodec.java"), str(RT / "CjkStateful.java"),
                        str(ROOT / "tools" / "CjkCheck.java")], check=True)
        shutil.copytree(RT / "cjk", work / "pyjvm315" / "runtime" / "cjk")
        bad = 0
        for n in names:
            table = cjk_tables.parse((RT / "cjk" / f"{n}.bin").read_bytes()) if n in cjk_gen.STATELESS else None
            res = {}
            for which, gen in (("dec", decode_lines(n)), ("enc", encode_lines(n, table))):
                h = hashlib.sha256()
                f = open(dump / f"{n}.py.{which}.txt", "w", encoding="utf-8", newline="\n") if dump else None
                for line in gen:
                    h.update((line + "\n").encode())
                    if f:
                        f.write(line + "\n")
                if f:
                    f.close()
                res[which] = h.hexdigest()
            cmd = ["java", "-cp", str(work), "pyjvm315.runtime.CjkCheck", n] + ([str(dump / f"{n}.java")] if dump else [])
            out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout.split()
            jres = dict(zip(out[::2], out[1::2]))
            ok = jres == res
            bad += not ok
            print(f"{n}: {'OK' if ok else 'DIFFERENT ' + str({k: (res[k][:8], jres.get(k, '')[:8]) for k in res if res[k] != jres.get(k)})}", flush=True)
        return 1 if bad else 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
