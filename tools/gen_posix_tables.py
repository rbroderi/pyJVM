# SPDX-License-Identifier: GPL-3.0-or-later
"""Print os.strerror(n) for n in 1..133 as a Java string array body (Linux CPython).

Used to generate PosixErrnoText.TEXT for the N0 plan.  Output is one Java string literal
per line, index 0 being errno 1.  Run under a Linux CPython; the texts are glibc's English
messages (CPython sets only LC_CTYPE, so they are never translated).
"""

from __future__ import annotations

import os
import sys


def java_literal(text: str) -> str:
    out = []
    for ch in text:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif 32 <= ord(ch) < 127:
            out.append(ch)
        else:
            out.append(f"\\u{ord(ch):04x}")
    return '"' + "".join(out) + '"'


def main() -> int:
    if not sys.platform.startswith("linux"):
        print("gen_posix_tables.py: run under Linux CPython", file=sys.stderr)
        return 2
    print("static final String[] TEXT = {")
    for n in range(1, 134):
        print(f"    {java_literal(os.strerror(n))},  // {n}")
    print("};")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
