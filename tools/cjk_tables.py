# SPDX-License-Identifier: GPL-3.0-or-later
"""Reader for the tables written by tools/cjk_gen.py (used by tools/cjk_check.py and for lane comparison)."""
from __future__ import annotations

import zlib


class Reader:
    def __init__(self, data: bytes):
        self.b, self.i = data, 0

    def u(self) -> int:
        v = shift = 0
        while True:
            c = self.b[self.i]; self.i += 1
            v |= (c & 0x7F) << shift
            if c < 0x80:
                return v
            shift += 7

    def s(self) -> int:
        v = self.u()
        return (v >> 1) ^ -(v & 1)

    def row(self, n: int = 256):
        out, prev = [], 0
        for _ in range(n):
            prev += self.s()
            out.append(prev)
        return out


def parse(blob: bytes) -> dict:
    r = Reader(zlib.decompress(blob))
    assert r.u() == 1
    t = {"dec1": r.row()}
    t["rows2"] = {}
    for _ in range(r.u()):
        l = r.u()
        t["rows2"][l] = r.row()
    t["rows3"] = {}
    for _ in range(r.u()):
        l, b2 = r.u(), r.u()
        t["rows3"][(l, b2)] = r.row()
    t["pairs"] = [(r.u(), r.u()) for _ in range(r.u())]
    t["jamo"] = None
    if r.u():
        t["jamo"] = [[r.u() for _ in range(r.u())] for _ in range(3)] + [r.u()]
    t["gb4"] = None
    if r.u():
        bad = [r.u() for _ in range(r.u())]
        runs, pl, pc = [], 0, 0
        for _ in range(r.u()):
            lin = pl + r.s(); cp = pc + r.s(); n = r.u()
            runs.append((lin, cp, n))
            pl, pc = lin + n, cp + n
        t["gb4"] = (bad, runs)
    enc, pc, pv = {}, 0, 0
    for _ in range(r.u()):
        n = r.u()
        pc += r.s(); pv += r.s()
        enc[pc] = (pv, n)
    t["enc"] = enc
    t["pairenc"] = [(r.u(), r.u()) for _ in range(r.u())]
    assert r.i == len(r.b)
    return t
