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


def _run_pyjvm(kit, main: Path, env: dict[str, str], prefix: tuple[str, ...] = ()) -> subprocess.CompletedProcess[str]:
    """Compile ``main`` and run it under pyJVM with ``env`` added to this process's environment (an inherited
    PYJVM_POSIX_FDS is dropped: the mode is whatever ``env`` says, kernel by default). ``prefix`` is put in front of
    the ``java`` command (a wrapper that execs it)."""
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
    cmd = [*prefix, "java", "--enable-native-access=ALL-UNNAMED", "-Xss64m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
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


# A descriptor closed behind a file object's back, the object dropped, then the number reused by a new open. CPython
# finalizes at the drop (its close fails with EBADF, ignored in __del__); pyJVM's finalizer runs later, at gc.collect,
# when the number denotes the new descriptor: it must skip the close (the generation changed), so fd2 survives.
STALE_FINALIZER = '''
import gc, os, tempfile
d = tempfile.mkdtemp()
p1, p2 = os.path.join(d, "one"), os.path.join(d, "two")
for p, data in ((p1, b"first"), (p2, b"second")):
    with open(p, "wb") as w:
        w.write(data)

def stale(wrap):
    fd = os.open(p1, os.O_RDONLY)
    f = wrap(fd)
    os.close(fd)
    del f
    fd2 = os.open(p2, os.O_RDONLY)
    print(fd2 == fd)   # the lowest free number; a JVM thread taking it in between makes this False (a loud failure)
    for _ in range(3):
        gc.collect()
    os.fstat(fd2)
    print(os.read(fd2, 100))
    os.close(fd2)

stale(lambda fd: os.fdopen(fd, "rb"))
stale(lambda fd: open(fd, "rb", buffering=0))
stale(lambda fd: open(fd, "r"))
for p in (p1, p2):
    os.remove(p)
os.rmdir(d)
'''

# The plain case: a file object dropped unclosed still has its descriptor closed by the finalizer (raw, buffered and a
# text wrapper, whose __del__ closes its FileIO through the same guard).
DROPPED_UNCLOSED = '''
import gc, os, tempfile
d = tempfile.mkdtemp()
p = os.path.join(d, "f")
with open(p, "wb") as w:
    w.write(b"data")
real = os.path.realpath(p)

def dropped(mode, **kw):
    f = open(p, mode, **kw)
    fd = f.fileno()
    del f
    for _ in range(3):
        gc.collect()
    try:
        held = os.readlink("/proc/self/fd/%d" % fd) == real
    except FileNotFoundError:
        held = False
    print(mode, "still open" if held else "closed")

dropped("r")
dropped("w")
dropped("rb")
dropped("ab", buffering=0)
os.remove(p)
os.rmdir(d)
'''


def _same_as_cpython(kit, tmp_path, script, expected):
    main = tmp_path / "main.py"
    main.write_text(script, encoding="utf-8")
    ref = subprocess.run([sys.executable, str(main)], capture_output=True, encoding="utf-8", timeout=120)
    assert (ref.returncode, ref.stdout) == (0, expected), ref.stderr
    proc = _run_pyjvm(kit, main, {})
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == expected


def test_stale_finalizer_keeps_reused_descriptor(jvm_kit, tmp_path):
    _same_as_cpython(jvm_kit, tmp_path, STALE_FINALIZER, "True\nb'second'\n" * 3)


def test_dropped_file_is_closed_by_finalizer(jvm_kit, tmp_path):
    _same_as_cpython(jvm_kit, tmp_path, DROPPED_UNCLOSED, "r closed\nw closed\nrb closed\nab closed\n")


# signal.set_wakeup_fd on a full non-blocking pipe: with warn_on_full_buffer (the default) the failed write is reported
# on stderr under a header whose wording depends on the lane; with warn_on_full_buffer=False EAGAIN is silent, any other
# error (EPIPE once the read end is closed) is still reported. CPython also prints a traceback of whatever frame runs
# its deferred report; pyJVM writes from the delivering thread, so only the header and the error line are compared.
WAKEUP_FULL = '''
import os, signal, sys
r, w = os.pipe()
os.set_blocking(w, False)
for chunk in (65536, 1):
    try:
        while True:
            os.write(w, b"x" * chunk)
    except BlockingIOError:
        pass
signal.signal(signal.SIGUSR1, lambda n, f: None)
print(signal.set_wakeup_fd(w))
signal.raise_signal(signal.SIGUSR1)
sys.stderr.flush()
print("warn done", flush=True)
signal.set_wakeup_fd(w, warn_on_full_buffer=False)
signal.raise_signal(signal.SIGUSR1)
sys.stderr.flush()
print("nowarn done", flush=True)
os.close(r)
signal.raise_signal(signal.SIGUSR1)
sys.stderr.flush()
print("broken done", flush=True)
signal.set_wakeup_fd(-1)
'''

WAKEUP_HEADER = {(3, 13): "Exception ignored when trying to write to the signal wakeup fd:"}
WAKEUP_HEADER_LATER = "Exception ignored while trying to write to the signal wakeup fd:"   # 3.14 and later


def _wakeup_report(stderr: str) -> list[str]:
    return [ln for ln in stderr.splitlines() if ln.startswith("Exception ignored") or " [Errno " in ln]


def test_wakeup_fd_full_buffer_warning_header_per_lane(jvm_kit, tmp_path):
    main = tmp_path / "main.py"
    main.write_text(WAKEUP_FULL, encoding="utf-8")
    header = WAKEUP_HEADER.get(reference_version(sys.executable), WAKEUP_HEADER_LATER)
    expected = [header, "BlockingIOError: [Errno 11] Resource temporarily unavailable", header, "BrokenPipeError: [Errno 32] Broken pipe"]
    ref = subprocess.run([sys.executable, str(main)], capture_output=True, encoding="utf-8", timeout=120)
    assert ref.returncode == 0 and _wakeup_report(ref.stderr) == expected, ref.stderr
    proc = _run_pyjvm(jvm_kit, main, {})
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == ref.stdout == "-1\nwarn done\nnowarn done\nbroken done\n"
    assert _wakeup_report(proc.stderr) == expected, proc.stderr


# SIGINT inherited as SIG_IGN (a background job of a non-interactive shell, nohup-like launchers) stays SIG_IGN, as in
# CPython, which installs default_int_handler only over SIG_DFL; inherited SIG_DFL still gets default_int_handler.
SIGINT_SCRIPT = '''
import signal
h = signal.getsignal(signal.SIGINT)
print(h is signal.default_int_handler, repr(h))
'''


@pytest.mark.skipif(shutil.which("perl") is None, reason="needs perl to launch with a chosen SIGINT disposition")
@pytest.mark.parametrize("disposition,expected", [
    ("IGNORE", "False <Handlers.SIG_IGN: 1>\n"),
    ("DEFAULT", "True <built-in function default_int_handler>\n"),
])
def test_inherited_sigint_disposition(jvm_kit, tmp_path, disposition, expected):
    main = tmp_path / "main.py"
    main.write_text(SIGINT_SCRIPT, encoding="utf-8")
    prefix = ("perl", "-e", '$SIG{INT}="%s"; exec @ARGV' % disposition)
    ref = subprocess.run([*prefix, sys.executable, str(main)], capture_output=True, encoding="utf-8", timeout=120)
    assert (ref.returncode, ref.stdout) == (0, expected), ref.stderr
    proc = _run_pyjvm(jvm_kit, main, {}, prefix)
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == expected
