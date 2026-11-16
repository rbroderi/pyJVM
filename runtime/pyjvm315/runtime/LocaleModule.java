// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

/** Minimal {@code _locale}: the C locale only; the preferred encoding is always UTF-8. */
final class LocaleModule {
    private LocaleModule() {}

    private static volatile String localeEncoding;

    /** {@code _locale.getencoding()}: "cp" + the ANSI code page on Windows, nl_langinfo(CODESET) ("UTF-8") elsewhere. */
    static String localeEncoding() {
        String e = localeEncoding;
        if (e == null) localeEncoding = e = ImportSupport.WINDOWS ? "cp" + WindowsHandles.acp() : "UTF-8";
        return e;
    }

    /** {@code sys.flags.utf8_mode}: PYTHONUTF8=0/1 decides; otherwise on by default from 3.15 (PEP 686), off before. */
    static boolean utf8Mode() {
        String v = System.getenv("PYTHONUTF8");
        if ("1".equals(v)) return true;
        if ("0".equals(v)) return false;
        return LangVersion.atLeast(3, 15);
    }

    /** The encoding of a text stream opened with encoding=None. */
    static String defaultTextEncoding() { return utf8Mode() ? "utf-8" : localeEncoding(); }

    static PyModule create() {
        PyModule m = new PyModule("_locale", "pyjvm315.runtime.LocaleModule");
        PyClass error = (PyClass) PyRuntime.classCreate("Error", "_locale");
        PyRuntime.classAddBase(error, PyRuntime.builtinType("Exception"));
        finish(error);
        m.attrs.put("Error", error);
        String[] cats = {"LC_CTYPE", "LC_NUMERIC", "LC_TIME", "LC_COLLATE", "LC_MONETARY", "LC_MESSAGES", "LC_ALL"};
        for (int i = 0; i < cats.length; i++) m.attrs.put(cats[i], (long) i);
        m.attrs.put("CHAR_MAX", 127L);
        m.attrs.put("CODESET", 14L);
        fn(m, "getencoding", (a, k) -> localeEncoding());
        fn(m, "setlocale", (a, k) -> {
            Object loc = a.size() > 1 ? a.get(1) : k.get("locale");
            if (loc == null || "".equals(loc) || "C".equals(loc) || "POSIX".equals(loc) || "C.UTF-8".equals(loc)) return "C";
            PyRuntime.raiseObject(PyRuntime.instantiate(error, new Object[]{"unsupported locale setting"}));
            return null;
        });
        fn(m, "localeconv", (a, k) -> {
            PyDict d = new PyDict();
            d.put("decimal_point", "."); d.put("thousands_sep", ""); d.put("grouping", new java.util.ArrayList<Object>());
            d.put("int_curr_symbol", ""); d.put("currency_symbol", ""); d.put("mon_decimal_point", ""); d.put("mon_thousands_sep", "");
            d.put("mon_grouping", new java.util.ArrayList<Object>()); d.put("positive_sign", ""); d.put("negative_sign", "");
            for (String f : new String[]{"int_frac_digits", "frac_digits", "p_cs_precedes", "p_sep_by_space", "n_cs_precedes", "n_sep_by_space", "p_sign_posn", "n_sign_posn"})
                d.put(f, 127L);
            return d;
        });
        fn(m, "nl_langinfo", (a, k) -> {
            if (a.size() == 1 && a.get(0) instanceof Long l && l == 14L) return "UTF-8";
            throw new PyException("ValueError", "unsupported langinfo constant");
        });
        fn(m, "strcoll", (a, k) -> (long) Integer.signum(((String) a.get(0)).compareTo((String) a.get(1))));
        fn(m, "strxfrm", (a, k) -> a.get(0));
        return m;
    }
}
