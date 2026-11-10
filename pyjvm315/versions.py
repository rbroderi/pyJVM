"""Python version policy.

The compiler runs on, and parses source with, the *host* interpreter's ``ast``.
Supported host range is ``MIN_PYTHON`` .. (no upper bound; ``TESTED_PYTHON`` lists
versions CI exercises). Behaviour that differs between CPython releases is
selected by the *reference* version a program is compared against, never
hard-coded to one release.
"""
from __future__ import annotations

import re
import subprocess
import sys

MIN_PYTHON = (3, 13)
TESTED_PYTHON = ((3, 13), (3, 15))
Version = tuple[int, int]


def check_host() -> None:
    if sys.version_info[:2] < MIN_PYTHON:
        raise RuntimeError("pyjvm requires Python %d.%d or newer, found %d.%d" % (MIN_PYTHON + sys.version_info[:2]))


def feature_version() -> Version:
    """Grammar level for ``ast.parse``: the host's own (newer syntax cannot be parsed on older hosts)."""
    return sys.version_info[:2]


def parse_version(text: str) -> Version:
    m = re.fullmatch(r"\s*(\d+)\.(\d+)(?:\.\w+)?\s*", text)
    if not m:
        raise ValueError(f"bad Python version {text!r}")
    return int(m.group(1)), int(m.group(2))


def reference_version(python_exe: str) -> Version:
    out = subprocess.run([python_exe, "-c", "import sys;print('%d.%d'%sys.version_info[:2])"],
                         text=True, capture_output=True, check=True).stdout
    return parse_version(out)


_GATE = re.compile(r"^#\s*pyjvm:\s*requires-python\s*>=\s*(\d+\.\d+)\s*$", re.M)


def required_python(source_text: str) -> Version | None:
    """A ``# pyjvm: requires-python >= 3.15`` header gates a conformance case on its reference version."""
    m = _GATE.search("\n".join(source_text.splitlines()[:10]))
    return parse_version(m.group(1)) if m else None
