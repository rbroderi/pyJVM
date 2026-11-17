// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The Python halves of {@code _multibytecodec} and the six {@code _codecs_*} modules, shipped as resources (cjk/*.py). */
final class CjkSource {
    private CjkSource() {}

    static boolean handles(String name) {
        return name.equals("_multibytecodec") || name.startsWith("_codecs_") && getClassLoaderHas(name);
    }

    private static boolean getClassLoaderHas(String name) {
        return switch (name) {
            case "_codecs_cn", "_codecs_hk", "_codecs_iso2022", "_codecs_jp", "_codecs_kr", "_codecs_tw" -> true;
            default -> false;
        };
    }

    /** Import entry used by PyRuntime.importNative. The module body is compiled in-process by the self-hosted compiler,
     *  which itself imports native modules on another thread, so this must not run under PyRuntime's NATIVE_LOCK. */
    static PyModule load(String name) {
        synchronized (CjkSource.class) {
            PyModule cached = ImportSupport.cachedModule(name);
            if (cached != null) return cached;
            PyModule m = module(name);
            PyRuntime.MODULE_CACHE.put(name, m);
            ImportSupport.register(name, m);
            return m;
        }
    }

    static PyModule module(String name) {
        try (InputStream in = CjkSource.class.getResourceAsStream("cjk/" + name + ".py")) {
            if (in == null) throw new IllegalStateException("missing module source cjk/" + name + ".py");
            return ImportSupport.execText(name, new String(in.readAllBytes(), StandardCharsets.UTF_8), "<" + name + ">");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
