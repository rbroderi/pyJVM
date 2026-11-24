# SPDX-License-Identifier: GPL-3.0-or-later
"""Kernel descriptors on Linux (the default; PYJVM_POSIX_FDS=nio is the rollback): the numbers pyJVM hands out are the kernel's."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
from pathlib import Path

import pytest

from pyjvm315.cli import build_runtime
from pyjvm315.conformance import compare_file
from pyjvm315.driver import compile_file
from pyjvm315.hostver import reference_stdlib, reference_version
from pyjvm315.selfhost import build_selfhosted

pytestmark = pytest.mark.skipif(not sys.platform.startswith("linux"), reason="kernel descriptors are Linux-only")


@pytest.fixture(scope="module")
def jvm_kit():
    with tempfile.TemporaryDirectory(prefix="pyjvm-posix-fds-test-") as td:
        runtime = Path(td) / "runtime"
        build_runtime(runtime)
        yield runtime, build_selfhosted(Path(td) / "selfhosted")


def _run_pyjvm(kit, main: Path, env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    """Compile ``main`` and run it under pyJVM with ``env`` added to this process's environment (an inherited
    PYJVM_POSIX_FDS is dropped: the mode is whatever ``env`` says, kernel by default)."""
    runtime, selfhosted = kit
    out = main.parent / "out"
    compile_file(main, out, "ConformanceMain")
    shutil.copytree(runtime / "pyjvm315", out / "pyjvm315", dirs_exist_ok=True)
    full = os.environ.copy()
    full.pop("PYJVM_POSIX_FDS", None)
    full.update(env)
    full["PYJVM_LANG_VERSION"] = "%d.%d" % reference_version(sys.executable)
    full["PYJVM_STDLIB"] = os.environ.get("PYJVM_STDLIB") or reference_stdlib(sys.executable)
    full.setdefault("PYTHONIOENCODING", "utf-8")
    cmd = ["java", "--enable-native-access=ALL-UNNAMED", "-Xss64m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
           "-cp", str(out) + os.pathsep + str(selfhosted), "ConformanceMain"]
    return subprocess.run(cmd, cwd=main.parent, capture_output=True, encoding="utf-8", errors="replace", env=full, timeout=600)


DEV_NULL = '''
import os
fd = os.open("/dev/null", os.O_RDONLY)
print(os.readlink("/proc/self/fd/%d" % fd))
os.close(fd)
'''


def test_descriptor_is_the_kernels(jvm_kit, tmp_path):
    main = tmp_path / "main.py"
    main.write_text(DEV_NULL, encoding="utf-8")
    proc = _run_pyjvm(jvm_kit, main, {})
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == "/dev/null\n"


ROUND_TRIP = '''
import os, tempfile
d = tempfile.mkdtemp()
p = os.path.join(d, "f")
with open(p, "w") as f:
    f.write("alpha\\nbeta\\n")
    print(f.tell())
with open(p, "r+") as f:
    print(repr(f.read()))
    f.seek(0)
    f.write("ALPHA")
    f.seek(0)
    print(repr(f.readline()))
    f.truncate(8)
with open(p) as f:
    print(repr(f.read()))
with open(p, "ab") as f:
    f.write(b"\\x00\\xff tail")
    print(f.tell())
with open(p, "rb+") as f:
    print(f.read(3), f.tell())
    f.seek(-4, 2)
    print(f.read())
    f.seek(2)
    f.write(b"--")
    f.seek(0)
    print(f.read())
    f.truncate(0)
    print(f.seek(0, 2))
fd = os.open(p, os.O_RDWR | os.O_APPEND)
os.lseek(fd, 0, 0)
os.write(fd, b"xyz")
print(os.lseek(fd, 0, 1))
os.lseek(fd, 0, 0)
print(os.read(fd, 100))
os.ftruncate(fd, 1)
os.fsync(fd)
print(os.lseek(fd, 0, 2))
fd2 = os.dup(fd)
print(fd2 != fd, os.lseek(fd2, 0, 1))
os.close(fd2)
os.close(fd)
os.chdir(d)
with open("rel", "w") as f:
    f.write("relative")
print(sorted(os.listdir(d)))
os.remove("rel")
os.remove(p)
os.chdir("/")
os.rmdir(d)
'''


def test_round_trip_kernel_matches_cpython(jvm_kit, tmp_path, monkeypatch):
    runtime, selfhosted = jvm_kit
    main = tmp_path / "main.py"
    main.write_text(ROUND_TRIP, encoding="utf-8")
    monkeypatch.delenv("PYJVM_POSIX_FDS", raising=False)   # the default, whatever the calling shell exported
    result = compare_file(main, sys.executable, runtime_cache=runtime, selfhosted=selfhosted)
    assert result.status == "PASS", result.detail


def test_nio_rollback_is_really_nio(jvm_kit, tmp_path):
    # under the rollback the numbers come from pyJVM's own table, not the kernel: the round trip below passing in
    # both modes proves nothing about which mode ran, this does
    main = tmp_path / "main.py"
    main.write_text(DEV_NULL, encoding="utf-8")
    proc = _run_pyjvm(jvm_kit, main, {"PYJVM_POSIX_FDS": "nio"})
    assert not (proc.returncode == 0 and proc.stdout == "/dev/null\n"), (proc.stdout, proc.stderr)


def test_nio_rollback_has_no_pipe_api(jvm_kit, tmp_path):
    # the new descriptor functions exist only on kernel descriptors: nothing is half-registered under the rollback
    main = tmp_path / "main.py"
    main.write_text('import os\nprint([hasattr(os, n) for n in ("pipe", "pipe2", "dup2", "pread", "pwrite", "closerange")])\n', encoding="utf-8")
    proc = _run_pyjvm(jvm_kit, main, {"PYJVM_POSIX_FDS": "nio"})
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == "[False, False, False, False, False, False]\n"


def test_round_trip_nio_rollback_matches_cpython(jvm_kit, tmp_path, monkeypatch):
    runtime, selfhosted = jvm_kit
    main = tmp_path / "main.py"
    main.write_text(ROUND_TRIP, encoding="utf-8")
    monkeypatch.setenv("PYJVM_POSIX_FDS", "nio")
    result = compare_file(main, sys.executable, runtime_cache=runtime, selfhosted=selfhosted)
    assert result.status == "PASS", result.detail


EXIT_FLUSH = '''
import sys
print("out1")
sys.stderr.write("err1\\n")
sys.stdout.write("no newline")
'''

EXIT_RAISE = '''
print("before", end="")
raise SystemExit("bye")
'''


@pytest.mark.parametrize("script,code,out,err", [
    (EXIT_FLUSH, 0, "out1\nno newline", "err1\n"),
    (EXIT_RAISE, 1, "before", "bye\n"),
])
def test_std_streams_flushed_at_exit(jvm_kit, tmp_path, script, code, out, err):
    main = tmp_path / "main.py"
    main.write_text(textwrap.dedent(script), encoding="utf-8")
    proc = _run_pyjvm(jvm_kit, main, {})
    assert (proc.returncode, proc.stdout, proc.stderr) == (code, out, err)


def test_std_streams_flushed_on_uncaught_exception(jvm_kit, tmp_path):
    main = tmp_path / "main.py"
    main.write_text('print("before", end="")\nraise ValueError("boom")\n', encoding="utf-8")
    proc = _run_pyjvm(jvm_kit, main, {})
    assert proc.returncode == 1 and proc.stdout == "before"
    assert proc.stderr.startswith("Traceback (most recent call last):") and proc.stderr.endswith("ValueError: boom\n")
