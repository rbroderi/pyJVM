# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate the CJK codec tables (runtime/pyjvm315/runtime/cjk/*.bin) by probing the running CPython.

Usage: python tools/cjk_gen.py OUTDIR [codec ...]     (run once per lane; see tools/cjk_check.py for the comparison)

The tables describe CPython's stateless multi-byte codecs exactly as observed through ``bytes.decode`` /
``str.encode``: for every first byte the result (character, illegal, or "needs more"), for every lead byte the
256 results of the second byte (and for the three-byte leads the third), the characters that decode to two code
points (JIS X 0213, Big5-HKSCS combining pairs), the GB18030 four-byte ranges, and the full code point -> bytes map.
Nothing is typed by hand: the file is derived from the interpreter, so each lane gets its own tables.

Entry values (decode rows): >= 0 a code point; >= COMBO a combining pair (index into the pair list);
-1..-4 illegal sequence of that many bytes; NEED3 / NEED4 the sequence needs a third / fourth byte.
Binary layout: zlib-compressed stream of LEB128 varints (signed values zigzag, rows delta-coded).
"""
from __future__ import annotations

import sys
import zlib
from pathlib import Path

STATELESS = ["gb2312", "gbk", "gb18030", "big5hkscs", "big5", "cp950", "cp932", "euc_jis_2004", "euc_jisx0213", "euc_jp",
             "shift_jis", "shift_jis_2004", "shift_jisx0213", "cp949", "euc_kr", "johab"]
COMBO = 0x200000
NEED3, NEED4, NEED8 = -10, -11, -12
LEAD = -9
ILLEGAL = "illegal multibyte sequence"
INCOMPLETE = "incomplete multibyte sequence"


def probe(name: str, data: bytes):
    try:
        return data.decode(name)
    except UnicodeDecodeError as e:
        assert e.start == 0, (name, data, e)
        return (e.end, e.reason)


def classify(name: str, data: bytes, pairs: list):
    r = probe(name, data)
    if isinstance(r, str):
        if len(r) == 1:
            return ord(r)
        assert len(r) == 2, (name, data, r)
        key = (ord(r[0]), ord(r[1]))
        if key not in pairs:
            pairs.append(key)
        return COMBO + pairs.index(key)
    end, reason = r
    if reason == INCOMPLETE:
        assert end == len(data)
        return LEAD
    assert reason == ILLEGAL and 1 <= end <= 4, (name, data, r)
    return -end


class Writer:
    def __init__(self):
        self.b = bytearray()

    def u(self, v: int):
        assert v >= 0
        while v >= 0x80:
            self.b.append(v & 0x7F | 0x80)
            v >>= 7
        self.b.append(v)

    def s(self, v: int):
        self.u((v << 1) ^ (v >> 63) if v >= 0 else ((-v) << 1) - 1)

    def row(self, vals):
        prev = 0
        for v in vals:
            self.s(v - prev)
            prev = v


def gen(name: str) -> bytes:
    pairs: list = []
    w = Writer()
    dec1 = [classify(name, bytes([b]), pairs) for b in range(256)]
    leads = [b for b in range(256) if dec1[b] == LEAD]
    rows2, rows3 = {}, {}
    gb4 = False
    jamo = None
    for l in leads:
        row = []  # noqa
        for b2 in range(256):
            v = classify(name, bytes([l, b2]), pairs)
            if v == LEAD:
                third = {classify(name, bytes([l, b2, x]), pairs) for x in range(256)}
                if third == {LEAD}:
                    if classify(name, bytes([l, b2, 0xA4, 0x30]), pairs) == LEAD:
                        v = NEED8        # EUC-KR 0xA4D4: eight-byte Hangul make-up sequence
                        jamo = (l, b2)
                    else:
                        v = NEED4
                        gb4 = True
                else:
                    v = NEED3
                    rows3[(l, b2)] = [classify(name, bytes([l, b2, x]), pairs) for x in range(256)]
                    assert LEAD not in rows3[(l, b2)]
            row.append(v)
        rows2[l] = row
    w.u(1)
    w.row(dec1)
    w.u(len(leads))
    for l in leads:
        w.u(l)
        w.row(rows2[l])
    w.u(len(rows3))
    for (l, b2), row in sorted(rows3.items()):
        w.u(l); w.u(b2)
        w.row(row)
    w.u(len(pairs))
    for a, b in pairs:
        w.u(a); w.u(b)
    # EUC-KR make-up sequences: 0xA4D4, then three 0xA4xx jamo (initial, medial, final or the 0xD4 filler). The three
    # jamo code -> index tables are read back from the decoder (255 = not valid in that position).
    w.u(1 if jamo else 0)
    jamo_cps = set()
    if jamo:
        l, f = jamo
        def jam(i, x, y, z):
            r = probe(name, bytes([l, f, l, x, l, y, l, z]))
            return ord(r) if isinstance(r, str) else None
        cho = [(jam(0, x, 0xBF, f) or 0xAC00 - 588) - 0xAC00 for x in range(0xA1, 0xBF)]
        jung = [(jam(0, 0xA1, y, f) or 0xAC00 - 28) - 0xAC00 for y in range(0xBF, 0xD4)]
        jong = [(jam(0, 0xA1, 0xBF, z) or 0xAC00 - 1) - 0xAC00 for z in range(0xA1, 0xBF)]
        cho = [255 if c < 0 else c // 588 for c in cho]
        jung = [255 if c < 0 else c // 28 for c in jung]
        jong = [255 if c < 0 else c for c in jong]
        jbad = set()
        for t in (cho, jung, jong):
            w.u(len(t)); [w.u(x) for x in t]
        for x in range(0xA1, 0xBF):
            for y in range(0xBF, 0xD4):
                for z in [0xD4] + list(range(0xA1, 0xBF)):
                    r = probe(name, bytes([l, f, l, x, l, y, l, z]))
                    c, v_, t_ = cho[x - 0xA1], jung[y - 0xBF], 0 if z == 0xD4 else jong[z - 0xA1]
                    if 255 in (c, v_, t_):
                        assert not isinstance(r, str) and r[1] == ILLEGAL, (x, y, z, r)
                        jbad.add(r[0])
                    else:
                        assert r == chr(0xAC00 + c * 588 + v_ * 28 + t_), (x, y, z, r)
        assert len(jbad) == 1, jbad
        w.u(jbad.pop())      # length reported for a make-up sequence with an invalid jamo
        for cp in range(0xAC00, 0xD7A4):
            b = chr(cp).encode(name)
            if len(b) == 8:
                jamo_cps.add(cp)
                ci, vi, ti = (cp - 0xAC00) // 588, (cp - 0xAC00) % 588 // 28, (cp - 0xAC00) % 28
                cb, vb = 0xA1 + cho.index(ci), 0xBF + jung.index(vi)
                tb = 0xD4 if ti == 0 else 0xA1 + jong.index(ti)
                assert b == bytes([l, f, l, cb, l, vb, l, tb]), (cp, b)
    # GB18030 four-byte section: runs (first linear index, first code point, count); the four-byte rules are verified
    # below and implemented in the Java engine.
    w.u(1 if gb4 else 0)
    if gb4:
        runs = []
        bad_len = set()
        for b1 in range(0x81, 0xFF):
            for b2 in range(0x30, 0x3A):
                for b3 in range(0x81, 0xFF):
                    for b4 in range(0x30, 0x3A):
                        lin = (((b1 - 0x81) * 10 + b2 - 0x30) * 126 + b3 - 0x81) * 10 + b4 - 0x30
                        r = probe(name, bytes([b1, b2, b3, b4]))
                        if isinstance(r, str):
                            assert len(r) == 1
                            cp = ord(r)
                            if runs and runs[-1][0] + runs[-1][2] == lin and runs[-1][1] + runs[-1][2] == cp:
                                runs[-1][2] += 1
                            else:
                                runs.append([lin, cp, 1])
                        else:
                            bad_len.add(r)
        assert bad_len <= {(4, ILLEGAL), (1, ILLEGAL)}, bad_len
        w.u(len(bad_len)); [w.u(x[0]) for x in sorted(bad_len)]
        w.u(len(runs))
        pl = pc = 0
        for lin, cp, n in runs:
            w.s(lin - pl); w.s(cp - pc); w.u(n)
            pl, pc = lin + n, cp + n
    # encode map
    enc = []
    for cp in range(0x110000):
        try:
            b = chr(cp).encode(name)
        except UnicodeEncodeError as e:
            assert (e.start, e.end, e.reason) == (0, 1, ILLEGAL), (name, cp, e)
            continue
        if len(b) > 4:
            assert len(b) == 8 and cp in jamo_cps, (name, cp, b)
            continue
        enc.append((cp, int.from_bytes(b, "big"), len(b)))
    w.u(len(enc))
    pc = pv = 0
    for cp, v, n in enc:
        w.u(n)
        w.s(cp - pc); w.s(v - pv)
        pc, pv = cp, v
    # combining-pair encodings: a two-character string whose encoding is not the concatenation
    w.u(len(pairs))
    for a, b in pairs:
        e = (chr(a) + chr(b)).encode(name)
        w.u(len(e)); w.u(int.from_bytes(e, "big"))
    return zlib.compress(bytes(w.b), 9)


def main() -> None:
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    names = sys.argv[2:] or STATELESS
    for n in names:
        blob = gen(n)
        (out / f"{n}.bin").write_bytes(blob)
        print(n, len(blob), flush=True)


if __name__ == "__main__":
    main()
