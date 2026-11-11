// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/** {@code java Launcher <MainClass> [args...]}: runs a compiled program with sys.argv set. */
public final class Launcher {
    private Launcher() {}
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: Launcher <MainClass> [args...]"); System.exit(2); }
        String[] argv = new String[args.length];
        argv[0] = args[0];
        System.arraycopy(args, 1, argv, 1, args.length - 1);
        NativeModules.argv = argv;
        try {
            Class.forName(args[0]).getMethod("main", String[].class).invoke(null, (Object) Arrays.copyOfRange(args, 1, args.length));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }
}
