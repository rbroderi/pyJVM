# SPDX-License-Identifier: GPL-3.0-or-later
"""Compile-service entry point used by the JVM runtime for exec()/eval()/compile().

The runtime launches ``python -I -c <boot> <home> <mode> <filename>``, writes the source to stdin
and reads the class file from stdout. Failures are reported as one JSON object on stderr
(exit status 1). This is the interim compiler backend: once the compiler is self-hosted
(roadmap P1-07) the runtime calls it in-process and this module is no longer needed.
"""
from __future__ import annotations

import json
import sys

from .compiler import CompileError, compile_source
from .classfile import DEFAULT_TARGET
from .stackmap import FrameError

CLASS_NAME = "pyjvm315.runtime.PyExec"


def compile_for_exec(source: str, filename: str, mode: str, optimize: int = 0, feature_minor: int = -1) -> bytes:
    if mode not in ("exec", "eval", "single"):
        raise CompileError(f"compile() mode {mode!r} is not implemented")
    level = None if feature_minor < 0 else (3, feature_minor)
    return compile_source(source, class_name=CLASS_NAME, filename=filename, dynamic=True, mode=mode,
                          target=DEFAULT_TARGET, optimize=optimize, feature_version_override=level)


def main(argv: list[str]) -> int:
    mode, filename = argv[0], argv[1]
    optimize = int(argv[2]) if len(argv) > 2 else 0
    feature_minor = int(argv[3]) if len(argv) > 3 else -1
    source = sys.stdin.buffer.read().decode("utf-8")
    try:
        data = compile_for_exec(source, filename, mode, optimize, feature_minor)
    except CompileError as exc:   # a SyntaxError subclass: valid Python the compiler does not support yet
        report = {"kind": "Unsupported", "message": str(exc)}
    except SyntaxError as exc:
        report = {"kind": "SyntaxError", "type": type(exc).__name__, "message": exc.msg or str(exc), "lineno": exc.lineno, "offset": exc.offset}
    except FrameError as exc:
        too_big = "1..65535 bytes" in str(exc)
        report = {"kind": "Unsupported" if too_big else "Error",
                  "message": "compiled code exceeds the JVM 64KB method limit (a function, class or single module-level statement is too large)"
                  if too_big else f"FrameError: {exc}"}
    except RecursionError as exc:
        report = {"kind": "Unsupported", "message": "source is too deeply nested for the compiler"}
    else:
        sys.stdout.buffer.write(data)
        sys.stdout.buffer.flush()
        return 0
    sys.stderr.write(json.dumps(report))
    return 1
