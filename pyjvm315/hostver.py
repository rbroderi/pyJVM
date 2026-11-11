"""Host-only version helpers (regex, subprocess): reference-interpreter detection and conformance gates."""
from __future__ import annotations

import re
import subprocess

Version = tuple[int, int]


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
_GATE_BELOW = re.compile(r"^#\s*pyjvm:\s*requires-python\s*<\s*(\d+\.\d+)\s*$", re.M)


def python_below(source_text: str) -> Version | None:
    """A ``# pyjvm: requires-python < 3.14`` header limits a case to references older than that version."""
    m = _GATE_BELOW.search("\n".join(source_text.splitlines()[:10]))
    return parse_version(m.group(1)) if m else None


def required_python(source_text: str) -> Version | None:
    """A ``# pyjvm: requires-python >= 3.15`` header gates a conformance case on its reference version."""
    m = _GATE.search("\n".join(source_text.splitlines()[:10]))
    return parse_version(m.group(1)) if m else None
