# SPDX-License-Identifier: GPL-3.0-or-later
"""Print generated POSIX tables as Java source (Linux CPython).

Default mode: os.strerror(n) for n in 1..133 as a Java string array body.
``--conf`` mode (N1 plan): the sysconf/confstr name tables, see conf_tables().

``--consts MODULE`` mode (N2 plan): the integer constants of fcntl, termios or resource, see consts_tables().

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


def conf_tables() -> list[str]:
    """Four Java array initialisers from os.sysconf_names and os.confstr_names.

    Names sorted ascending (plain string order), values parallel by index:
    SYSCONF_NAMES, SYSCONF_VALUES, CONFSTR_NAMES, CONFSTR_VALUES.  Several names may share
    a value (SC_PAGESIZE and SC_PAGE_SIZE); the output is identical on every CPython lane.
    """
    out = []
    for label, table in (("SYSCONF", os.sysconf_names), ("CONFSTR", os.confstr_names)):
        names = sorted(table)
        out.append(f"static final String[] {label}_NAMES = {{")
        out.extend(f"    {java_literal(n)}," for n in names)
        out.append("};")
        out.append(f"static final int[] {label}_VALUES = {{")
        out.extend(f"    {table[n]},  // {n}" for n in names)
        out.append("};")
    return out


CONSTS_MODULES = ("fcntl", "termios", "resource")
# Per-lane or non-table names (N2 plan 1.7, 3.8): handled in code, never in the generated tables.
CONSTS_EXCLUDED = ("RLIM_INFINITY", "RLIM_SAVED_CUR", "RLIM_SAVED_MAX")


def consts_tables(module: str) -> list[str]:
    """Two Java array initialisers of every integer name of fcntl, termios or resource (N2 plan, ticket 0).

    Names sorted ascending (plain string order), ``long`` values parallel by index:
    <MODULE>_NAMES and <MODULE>_VALUES.  bool is not an integer name.  RLIM_INFINITY, RLIM_SAVED_CUR and
    RLIM_SAVED_MAX are left out (their values differ by lane or exceed a signed long).  Several names may share a
    value (RLIMIT_NOFILE and RLIMIT_OFILE).  The output is identical on every reference CPython lane.
    """
    mod = __import__(module)
    table = {n: getattr(mod, n) for n in dir(mod)
             if not n.startswith("__") and isinstance(getattr(mod, n), int)
             and not isinstance(getattr(mod, n), bool) and n not in CONSTS_EXCLUDED}
    names = sorted(table)
    label = module.upper()
    out = [f"static final String[] {label}_NAMES = {{"]
    out.extend(f"    {java_literal(n)}," for n in names)
    out.append("};")
    out.append(f"static final long[] {label}_VALUES = {{")
    out.extend(f"    {table[n]}L,  // {n}" for n in names)
    out.append("};")
    return out


def main() -> int:
    if not sys.platform.startswith("linux"):
        print("gen_posix_tables.py: run under Linux CPython", file=sys.stderr)
        return 2
    if sys.argv[1:] == ["--conf"]:
        print("\n".join(conf_tables()))
        return 0
    if len(sys.argv) == 3 and sys.argv[1] == "--consts" and sys.argv[2] in CONSTS_MODULES:
        print("\n".join(consts_tables(sys.argv[2])))
        return 0
    if sys.argv[1:]:
        print("usage: gen_posix_tables.py [--conf | --consts fcntl|termios|resource]", file=sys.stderr)
        return 2
    print("static final String[] TEXT = {")
    for n in range(1, 134):
        print(f"    {java_literal(os.strerror(n))},  // {n}")
    print("};")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
