# Contributing to pyJVM

1. Read [CLA.md](CLA.md). All contributions require agreement to it, and the
   Project Owner owns the copyright in the project (see section 2 of the CLA).
2. Sign off every commit: `git commit -s`.
3. Tick the CLA box in the pull-request template.
4. Before sending a change, run `python -m pytest -q` and, for compiler or
   runtime changes, the conformance suite described in README.md. Keep every
   method under the 8,000-byte lint (`python -m pyjvm315.methodsize`).
5. Keep SPDX headers on new source files. `tests/test_licenses.py` enforces
   them. Runtime files under `runtime/` and the modules in `CORE`
   (`pyjvm315/selfhost.py`) use
   `GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception`; every other
   file uses `GPL-3.0-or-later`.
