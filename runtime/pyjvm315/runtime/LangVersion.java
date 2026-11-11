package pyjvm315.runtime;

/**
 * The CPython language/library version whose behaviour the runtime follows where releases differ
 * (error wording, ast.dump, ...). Set with PYJVM_LANG_VERSION ("3.15"); defaults to the minimum
 * supported version, 3.13. Becomes sys.version_info when the sys module lands (roadmap P3-03).
 */
public final class LangVersion {
    private LangVersion() {}
    public static final int MAJOR, MINOR;
    static {
        int major = 3, minor = 13;
        String v = System.getenv("PYJVM_LANG_VERSION");
        if (v != null && v.matches("\\d+\\.\\d+")) { major = Integer.parseInt(v.split("\\.")[0]); minor = Integer.parseInt(v.split("\\.")[1]); }
        MAJOR = major; MINOR = minor;
    }
    public static boolean atLeast(int major, int minor) { return MAJOR > major || (MAJOR == major && MINOR >= minor); }
}
