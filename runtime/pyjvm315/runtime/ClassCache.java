// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * The persistent compile cache (roadmap P3-02): the class file that the in-process compiler produced for a source
 * file, stored on disk so that a later process imports the module without compiling it again.
 *
 * <p>Layout: next to {@code dir/mod.py} the entry is {@code dir/__pycache__/mod.py.pyjvm-<fp>-<variant>.cls}. When that
 * directory cannot be written (read-only stdlib) the entry goes to the user cache directory
 * ({@code $PYJVM_CACHE}, else {@code $XDG_CACHE_HOME/pyjvm}, else {@code ~/.cache/pyjvm}, else the temp dir), where the
 * name also carries a hash of the source directory. {@code PYJVM_CACHE=<dir>} makes that directory the only
 * location; {@code PYJVM_CACHE=off} disables the cache; {@code PYTHONDONTWRITEBYTECODE} keeps reading but stops writing.
 *
 * <p>An entry is valid when its SHA-256 of the source text equals the current source's, so an edited file is simply
 * recompiled (no mtime games). {@code fp} identifies the compiler and runtime that produced it (a hash of every class
 * file under {@code pyjvm315/} on the class path), {@code variant} the compile options (mode, optimize, feature level,
 * language version, file name as it is embedded in the class). Writes go to a temporary file in the target directory
 * and are renamed into place, so concurrent processes never see a torn entry and the last writer wins with identical bytes.
 */
final class ClassCache {
    private ClassCache() {}

    private static final int MIN_DYNAMIC = 100;
    private static final int MAGIC = 0x50594A43, FORMAT = 1;
    private static volatile String fingerprint;
    private static volatile Boolean disabled;

    private static boolean trace() { return System.getenv("PYJVM_CACHE_VERBOSE") != null; }
    private static void note(String s) { if (trace()) System.err.println("[pyjvm-cache] " + s); }

    private static String env(String name) { String v = System.getenv(name); return v == null || v.isEmpty() ? null : v; }

    private static boolean disabled() {
        Boolean d = disabled;
        if (d == null) {
            String v = env("PYJVM_CACHE");
            disabled = d = v != null && (v.equals("off") || v.equals("0") || v.equals("none"));
        }
        return d;
    }

    // ---- fingerprint ------------------------------------------------------------------------------

    /** Hex SHA-256 of the compiler and runtime class files this process runs (a rebuilt compiler or runtime invalidates every entry). */
    static String fingerprint() {
        String f = fingerprint;
        if (f == null) {
            synchronized (ClassCache.class) {
                if (fingerprint == null) fingerprint = computeFingerprint();
                f = fingerprint;
            }
        }
        return f;
    }

    private static String computeFingerprint() {
        MessageDigest md = sha256();
        md.update(("pyjvm-cache-" + FORMAT + " java" + Runtime.version().feature()).getBytes(StandardCharsets.UTF_8));
        ArrayList<Path> roots = new ArrayList<>();
        addRoot(roots, ClassCache.class);
        try { addRoot(roots, Class.forName(ExecSupport.SelfHostedBackend.MODULE_CLASS)); } catch (ClassNotFoundException | LinkageError e) { /* host backend */ }
        for (Path root : roots) hashRoot(md, root);
        return hex(md.digest());
    }

    private static void addRoot(List<Path> roots, Class<?> c) {
        try {
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return;
            Path p = Path.of(cs.getLocation().toURI());
            if (!roots.contains(p)) roots.add(p);
        } catch (Exception e) { /* unknown location: contributes nothing */ }
    }

    private static void hashRoot(MessageDigest md, Path root) {
        try {
            if (Files.isDirectory(root)) {
                Path base = root.resolve("pyjvm315");
                if (!Files.isDirectory(base)) return;
                List<Path> files;
                try (Stream<Path> s = Files.walk(base)) { files = s.filter(p -> p.toString().endsWith(".class")).sorted().toList(); }
                for (Path f : files) {
                    md.update(root.relativize(f).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0);
                    md.update(Files.readAllBytes(f));
                }
            } else if (Files.isRegularFile(root)) {
                try (InputStream in = Files.newInputStream(root)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
                }
            }
        } catch (IOException e) { md.update((byte) '?'); }
    }

    // ---- keys -------------------------------------------------------------------------------------

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 80; i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) && c < 128 || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return sb.toString();
    }

    /** Where entries for {@code filename} live, in lookup order; empty when the file name is not cacheable. */
    private static List<Path> candidates(String filename, String variant, String source) {
        String fp8 = fingerprint().substring(0, 8);
        String tag = ".pyjvm-" + fp8 + "-" + variant + ".cls";
        ArrayList<Path> out = new ArrayList<>();
        Path central = centralDir();
        if (filename.startsWith("<frozen ")) {
            // importlib's own bootstrap modules: no file on disk, so only the user cache can hold them
            if (central != null) out.add(central.resolve(sanitize(filename) + tag));
            return out;
        }
        if (filename.startsWith("<")) {
            // exec()/eval() of generated code (dataclasses, namedtuple, ...): worth keeping only when it is not tiny
            if (central != null && source.length() >= MIN_DYNAMIC)
                out.add(central.resolve("dynamic-" + hex(sourceDigest(source)).substring(0, 16) + tag));
            return out;
        }
        Path abs;
        try {
            abs = Path.of(filename).toAbsolutePath().normalize();
            if (!Files.isRegularFile(abs)) return out;
        } catch (RuntimeException e) { return out; }
        Path dir = abs.getParent();
        String name = abs.getFileName().toString();
        String dirHash = hex(sha256().digest(String.valueOf(dir).getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        if (env("PYJVM_CACHE") == null && dir != null) out.add(dir.resolve("__pycache__").resolve(name + tag));
        if (central != null) out.add(central.resolve(name + "." + dirHash + tag));
        return out;
    }

    private static Path centralDir() {
        String explicit = env("PYJVM_CACHE");
        if (explicit != null) return Path.of(explicit);
        String xdg = env("XDG_CACHE_HOME"), home = System.getProperty("user.home");
        if (xdg != null) return Path.of(xdg, "pyjvm");
        if (home != null && !home.isEmpty()) return Path.of(home, ".cache", "pyjvm");
        return Path.of(System.getProperty("java.io.tmpdir"), "pyjvm-cache");
    }

    private static String variant(String filename, String mode, int optimize, int featureMinor) {
        String key = mode + '\0' + optimize + '\0' + featureMinor + '\0' + LangVersion.MAJOR + '.' + LangVersion.MINOR + '\0' + filename;
        return hex(sha256().digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
    }

    private static byte[] sourceDigest(String source) { return sha256().digest(source.getBytes(StandardCharsets.UTF_8)); }

    // ---- lookup / store ---------------------------------------------------------------------------

    /** The cached class bytes for exactly this source and compile options, or null. */
    static byte[] lookup(String filename, String mode, int optimize, int featureMinor, String source) {
        if (disabled()) return null;
        try {
            byte[] digest = null;
            for (Path p : candidates(filename, variant(filename, mode, optimize, featureMinor), source)) {
                byte[] raw;
                try { raw = Files.readAllBytes(p); } catch (IOException e) { continue; }
                if (digest == null) digest = sourceDigest(source);
                byte[] cls = parse(raw, digest);
                if (cls != null) { note("hit " + filename); return cls; }
                note("stale " + p);
            }
        } catch (RuntimeException e) { note("lookup failed: " + e); }
        note("miss " + filename);
        return null;
    }

    private static byte[] parse(byte[] raw, byte[] digest) {
        if (raw.length < 44) return null;
        ByteBuffer b = ByteBuffer.wrap(raw);
        if (b.getInt() != MAGIC || b.getInt() != FORMAT) return null;
        byte[] stored = new byte[32];
        b.get(stored);
        if (!Arrays.equals(stored, digest)) return null;
        int len = b.getInt();
        if (len < 4 || len != b.remaining()) return null;
        byte[] cls = new byte[len];
        b.get(cls);
        return (cls[0] & 0xff) == 0xCA && (cls[1] & 0xff) == 0xFE && (cls[2] & 0xff) == 0xBA && (cls[3] & 0xff) == 0xBE ? cls : null;
    }

    /** Best effort: any failure (read-only directory, full disk, races) leaves the cache as it was. */
    static void store(String filename, String mode, int optimize, int featureMinor, String source, byte[] classBytes) {
        if (disabled() || env("PYTHONDONTWRITEBYTECODE") != null) return;
        try {
            String variant = variant(filename, mode, optimize, featureMinor);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(classBytes.length + 64);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(FORMAT); out.write(sourceDigest(source)); out.writeInt(classBytes.length); out.write(classBytes);
            for (Path target : candidates(filename, variant, source)) {
                if (write(target, bytes.toByteArray())) { note("stored " + target); dropStale(target); return; }
            }
        } catch (IOException | RuntimeException e) { note("store failed: " + e); }
    }

    private static boolean write(Path target, byte[] data) {
        Path tmp = null;
        try {
            Path dir = target.getParent();
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, ".pyjvm-", ".tmp");
            Files.write(tmp, data);
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException | RuntimeException e) {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException | RuntimeException ignored) { /* best effort */ }
            return false;
        }
    }

    /** Entries of the same file written by another compiler or runtime build can never match again: remove them. */
    private static void dropStale(Path written) {
        String name = written.getFileName().toString();
        int at = name.indexOf(".pyjvm-");
        if (at < 0) return;
        String stem = name.substring(0, at + 7), current = name.substring(at + 7, at + 15);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(written.getParent(), stem + "*.cls")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.length() > stem.length() + 8 && !n.startsWith(current, stem.length())) Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException e) { /* best effort */ }
    }
}
