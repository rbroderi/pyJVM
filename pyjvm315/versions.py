# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
"""Python version policy. Self-hostable: only ``sys`` is needed (host-only helpers live in hostver.py).

The compiler runs on, and parses source with, the *host* interpreter's ``ast``. Supported host range is
``MIN_PYTHON`` upward; ``TESTED_PYTHON`` lists versions CI exercises. Behaviour that differs between CPython
releases is selected by the *reference* version a program is compared against, never hard-coded.
"""
from __future__ import annotations

import sys

MIN_PYTHON = (3, 13)
TESTED_PYTHON = ((3, 13), (3, 15))


def check_host() -> None:
    if sys.version_info[:2] < MIN_PYTHON:
        raise RuntimeError("pyjvm requires Python %d.%d or newer, found %d.%d" % (MIN_PYTHON + sys.version_info[:2]))


def feature_version():
    """Grammar level for ``ast.parse``: the host's own (newer syntax cannot be parsed on older hosts)."""
    return sys.version_info[:2]
