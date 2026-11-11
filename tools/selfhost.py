"""Self-hosting build (roadmap P1-07): see pyjvm315/selfhost.py.

usage: python tools/selfhost.py stage1 <workdir>     build the JVM compiler with the host compiler
       python tools/selfhost.py stage2 <workdir>     rebuild with the JVM compiler and compare (bootstrap gate)
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from pyjvm315.selfhost import stage1, stage2  # noqa: E402

if __name__ == "__main__":
    cmd, work = sys.argv[1], Path(sys.argv[2]).resolve()
    work.mkdir(parents=True, exist_ok=True)
    raise SystemExit({"stage1": stage1, "stage2": stage2}[cmd](work))
