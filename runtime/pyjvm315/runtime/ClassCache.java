// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
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
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>Entries written under other fingerprints are kept (several worktrees or builds may share one cache directory):
 * after a store only the entries of the {@code PYJVM_CACHE_KEEP} (default 4) most recently used fingerprints of that
 * file are retained, and a hit refreshes the entry's modification time (at most once an hour). Stray temp files left
 * by a killed writer are removed once they are a day old.
 *
 * <p>{@code PYJVM_CACHE_FINGERPRINT=api} (default {@code full}) is a developer mode that fingerprints the runtime
 * package by its linkable surface (class/member signatures and constants, not method bodies); see {@link #apiDigest}.
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

    /** {@code sys.dont_write_bytecode} (initially PYTHONDONTWRITEBYTECODE) stops writes; entries are still read. */
    private static boolean dontWrite() {
        try { return PyRuntime.truth(((PyModule) PyRuntime.importNative("sys")).attrs.get("dont_write_bytecode")); }
        catch (RuntimeException e) { return env("PYTHONDONTWRITEBYTECODE") != null; }
    }

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

    private static boolean apiMode() { return "api".equals(env("PYJVM_CACHE_FINGERPRINT")); }

    private static String computeFingerprint() {
        MessageDigest md = sha256();
        md.update(("pyjvm-cache-" + FORMAT + " java" + Runtime.version().feature() + (apiMode() ? " api" : "")).getBytes(StandardCharsets.UTF_8));
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
                boolean api = apiMode();
                for (Path f : files) {
                    String rel = root.relativize(f).toString().replace('\\', '/');
                    byte[] data = Files.readAllBytes(f);
                    byte[] surface = null;
                    if (api && rel.startsWith("pyjvm315/runtime/")) {
                        try { surface = apiDigest(data); } catch (IOException | RuntimeException e) { /* unparsable: hash it whole */ }
                    }
                    if (surface != null && surface.length == 0) continue;     // anonymous/local class: no linkable surface
                    md.update(rel.getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0);
                    md.update((byte) (surface != null ? 'A' : 'F'));
                    md.update(surface != null ? surface : data);
                }
            } else if (Files.isRegularFile(root)) {
                try (InputStream in = Files.newInputStream(root)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
                }
            }
        } catch (IOException e) { md.update((byte) '?'); }
    }

    /**
     * Digest of what other classes can link against in one runtime class file: its name, flags, super types and, for
     * every non-private, non-synthetic member, name, descriptor, flags and (fields) the compile-time constant value.
     * Method bodies, private members, lambdas and anonymous/local classes do not contribute. Returns an empty array for a class
     * that has no linkable surface (anonymous/local), so adding or editing code inside a method keeps the digest.
     *
     * <p>This is what generated code depends on when it names a runtime class, field or method, so entries made by
     * generated code stay linkable while the surface is unchanged. It does not see behaviour: a body edit that changes
     * what a runtime helper returns, what the in-process compiler (which itself runs on this runtime) produces, or what
     * the runtime expects of generated classes (entry point names looked up by string) is invisible here. That is why
     * the mode is opt-in; bump {@link #FORMAT} or use the default mode when such a contract changes.
     */
    static byte[] apiDigest(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort(); in.readUnsignedShort();
        int n = in.readUnsignedShort();
        int[] tag = new int[n];
        Object[] val = new Object[n];
        for (int i = 1; i < n; i++) {
            int t = in.readUnsignedByte();
            tag[i] = t;
            switch (t) {
                case 1 -> val[i] = in.readUTF();
                case 3, 4 -> val[i] = in.readInt();
                case 5, 6 -> { val[i] = in.readLong(); i++; }
                case 7, 8, 16, 19, 20 -> val[i] = in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> { in.readUnsignedShort(); in.readUnsignedShort(); }
                case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                default -> throw new IOException("bad constant tag " + t);
            }
        }
        int access = in.readUnsignedShort();
        String self = (String) val[(Integer) val[in.readUnsignedShort()]];
        int sup = in.readUnsignedShort();
        String superName = sup == 0 ? "" : (String) val[(Integer) val[sup]];
        String simple = self.substring(self.lastIndexOf('/') + 1);
        for (String part : simple.split("\\$")) if (!part.isEmpty() && Character.isDigit(part.charAt(0))) return new byte[0];   // anonymous / local
        StringBuilder sb = new StringBuilder();
        sb.append(self).append(' ').append(access & ~0x1000).append(' ').append(superName);
        for (int c = in.readUnsignedShort(); c > 0; c--) sb.append(' ').append((String) val[(Integer) val[in.readUnsignedShort()]]);
        sb.append('\n');
        for (int pass = 0; pass < 2; pass++) {
            boolean fields = pass == 0;
            ArrayList<String> members = new ArrayList<>();
            for (int m = in.readUnsignedShort(); m > 0; m--) {
                int acc = in.readUnsignedShort();
                String name = (String) val[in.readUnsignedShort()], desc = (String) val[in.readUnsignedShort()];
                String constant = "";
                for (int a = in.readUnsignedShort(); a > 0; a--) {
                    String an = (String) val[in.readUnsignedShort()];
                    int len = in.readInt();
                    if (fields && an.equals("ConstantValue") && len == 2) {
                        int idx = in.readUnsignedShort();
                        constant = tag[idx] == 8 ? "s" + val[(Integer) val[idx]] : tag[idx] + ":" + val[idx];
                    } else in.skipNBytes(len);
                }
                if ((acc & 0x0002) != 0 || (acc & 0x1000) != 0) continue;       // private or synthetic: not linkable from generated code
                members.add(acc + " " + name + " " + desc + " " + constant);
            }
            if (!fields) members.sort(Comparator.naturalOrder());               // declaration order of fields is kept (enum constants)
            for (String s : members) sb.append(s).append('\n');
            sb.append('\n');
        }
        return sha256().digest(sb.toString().getBytes(StandardCharsets.UTF_8));
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
                if (cls != null) { note("hit " + filename); touch(p); return cls; }
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
        if (disabled() || dontWrite()) return;
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

    private static final long TOUCH_AFTER_MS = 3_600_000L, TMP_MAX_AGE_MS = 86_400_000L;

    /** A hit refreshes the entry's mtime (recency decides which fingerprints {@link #dropStale} keeps); best effort. */
    private static void touch(Path p) {
        try {
            long now = System.currentTimeMillis();
            if (now - Files.getLastModifiedTime(p).toMillis() > TOUCH_AFTER_MS) Files.setLastModifiedTime(p, FileTime.fromMillis(now));
        } catch (IOException | RuntimeException e) { /* read-only cache: fine */ }
    }

    private static int keep() {
        String v = env("PYJVM_CACHE_KEEP");
        try { return v == null ? 4 : Math.max(1, Integer.parseInt(v.trim())); } catch (NumberFormatException e) { return 4; }
    }

    /**
     * Entries of the same file written under other fingerprints may still be wanted by another worktree or build that
     * shares this directory, so they are kept: only fingerprints beyond the {@code keep()} most recently used ones are
     * removed (the one just written always counts as newest). Races are harmless: deleting an already deleted file is
     * a no-op and a reader that loses its entry just recompiles.
     */
    private static void dropStale(Path written) {
        String name = written.getFileName().toString();
        int at = name.indexOf(".pyjvm-");
        if (at < 0) return;
        String stem = name.substring(0, at + 7), current = name.substring(at + 7, at + 15);
        Map<String, FileTime> newest = new LinkedHashMap<>();           // fingerprint -> latest mtime of any variant
        Map<String, List<Path>> byFp = new LinkedHashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(written.getParent(), stem + "*.cls")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.length() < stem.length() + 8) continue;
                String fp = n.substring(stem.length(), stem.length() + 8);
                FileTime t;
                try { t = Files.getLastModifiedTime(p); } catch (IOException e) { continue; }
                if (fp.equals(current)) t = FileTime.fromMillis(Long.MAX_VALUE / 2);
                newest.merge(fp, t, (a, b) -> a.compareTo(b) >= 0 ? a : b);
                byFp.computeIfAbsent(fp, k -> new ArrayList<>()).add(p);
            }
        } catch (IOException | RuntimeException e) { return; }
        if (newest.size() > keep()) {
            List<String> order = new ArrayList<>(newest.keySet());
            order.sort((a, b) -> newest.get(b).compareTo(newest.get(a)));
            for (String fp : order.subList(keep(), order.size()))
                for (Path p : byFp.get(fp)) try { Files.deleteIfExists(p); note("evicted " + p); } catch (IOException | RuntimeException ignored) { /* best effort */ }
        }
        long cutoff = System.currentTimeMillis() - TMP_MAX_AGE_MS;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(written.getParent(), ".pyjvm-*.tmp")) {
            for (Path p : ds) try { if (Files.getLastModifiedTime(p).toMillis() < cutoff) Files.deleteIfExists(p); } catch (IOException | RuntimeException ignored) { /* best effort */ }
        } catch (IOException | RuntimeException e) { /* best effort */ }
    }
}
