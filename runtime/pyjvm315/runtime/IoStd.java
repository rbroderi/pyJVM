// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/**
 * The process streams as real objects: {@code sys.stdin/stdout/stderr} are TextIOWrapper over Buffered* over
 * FileIO on descriptors 0-2, as in CPython. stdout is block-buffered (line-buffered on a terminal), stderr is
 * line-buffered; pending output is flushed when the interpreter exits.
 */
final class IoStd {
    private IoStd() {}

    private static PyInstance in, out, err;
    private static boolean hook;

    private static synchronized void init() {
        if (out != null) return;
        IoModule.create();
        PyInstance i = make(0, "<stdin>", "r", "strict", false), o = make(1, "<stdout>", "w", "strict", IoFd.isatty(1)), e = make(2, "<stderr>", "w", "backslashreplace", true);
        in = i; out = o; err = e;
        if (!hook) {
            hook = true;
            Runtime.getRuntime().addShutdownHook(new Thread(IoStd::flushAtExit, "pyjvm-flush-std"));
        }
    }

    private static PyInstance make(int fd, String name, String mode, String errors, boolean lineBuffered) {
        PyInstance raw = (PyInstance) PyRuntime.instantiate(IoFile.cls, new Object[]{(long) fd, mode.equals("r") ? "rb" : "wb", false});
        raw.fields.put("name", name);
        PyClass bc = mode.equals("r") ? IoBuffered.reader : IoBuffered.writer;
        Object buf = PyRuntime.instantiate(bc, new Object[]{raw, (long) IoUtil.DEFAULT_BUFFER_SIZE});
        PyInstance text = (PyInstance) PyRuntime.instantiate(IoText.cls, new Object[]{buf, "utf-8", errors, "\n", lineBuffered, false});
        text.fields.put("mode", mode);
        return text;
    }

    static Object stdin() { init(); return in; }
    static Object stdout() { init(); return out; }
    static Object stderr() { init(); return err; }

    /** The initial stream by name (used before {@code sys} exists and for {@code sys.__stdout__}). */
    static Object stream(String name) { return name.equals("stdout") ? stdout() : name.equals("stderr") ? stderr() : stdin(); }

    /** Flush every writer that still holds data (the standard streams and any unclosed file). */
    static void flushAtExit() {
        IoUtil.syncAll();
        IoFd.flushProcessStreams();
    }
}
