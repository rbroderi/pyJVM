// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The jar finder (roadmap P3-02): a {@code sys.path} entry that is a {@code .jar} or {@code .zip} of precompiled modules.
 * A path hook installed in front of importlib's own hooks answers for such archives; the finder and loader themselves
 * are a few lines of Python ({@link #HELPER}) that run on top of CPython's unmodified importlib.
 *
 * <p>The archive carries {@value #MANIFEST}, a tab-separated text file:
 * <pre>
 * pyjvm-jar        1
 * fingerprint      &lt;hex&gt;     (optional; the archive is ignored, with a warning on stderr, unless it equals sys._pyjvm_fingerprint())
 * lang             3.15       (optional; the archive is ignored, with a warning, when it differs from the running language version)
 * module           &lt;dotted name&gt;  &lt;pkg|mod&gt;  &lt;entry&gt;  &lt;source path shown as co_filename and __file__&gt;
 * </pre>
 * Each entry is the class file {@code compile(source, filename, "exec")} produces; it runs in the module's namespace.
 */
final class JarImport {
    private JarImport() {}

    static final String MANIFEST = "META-INF/pyjvm-modules.txt";

    private static final ConcurrentHashMap<String,ZipFile> OPEN = new ConcurrentHashMap<>();
    private static PyModule helper;

    static final String HELPER = """
        import sys
        _ModuleSpec = sys.modules['_frozen_importlib'].ModuleSpec
        _parsed = {}
        _warned = set()


        def _ignored(archive, why):
            # a stale archive is never used silently
            if archive not in _warned:
                _warned.add(archive)
                sys.stderr.write('pyjvm: ignoring module archive %s: %s\\n' % (archive, why))


        def _parse(archive):
            got = _parsed.get(archive)
            if got is not None:
                return got
            data = _zip_read(archive, MANIFEST)
            if data is None:
                raise ImportError('not a pyjvm module archive', path=archive)
            header = fingerprint = lang = None
            modules = {}
            for line in data.decode('utf-8').splitlines():
                if not line or line[0] == '#':
                    continue
                f = line.split('\\t')
                if f[0] == 'pyjvm-jar':
                    header = f[1]
                elif f[0] == 'fingerprint':
                    fingerprint = f[1]
                elif f[0] == 'lang':
                    lang = f[1]
                elif f[0] == 'module' and len(f) >= 5:
                    modules[f[1]] = (f[2] == 'pkg', f[3], f[4])
            if header != '1':
                raise ImportError('unsupported pyjvm module archive format', path=archive)
            if fingerprint is not None and fingerprint != _fingerprint():
                _ignored(archive, 'it was built by a different compiler or runtime')
                raise ImportError('pyjvm module archive was built by a different compiler or runtime', path=archive)
            if lang is not None and lang != _lang():
                _ignored(archive, 'it was built for Python ' + lang + ', this is ' + _lang())
                raise ImportError('pyjvm module archive was built for another language version', path=archive)
            _parsed[archive] = modules
            return modules


        class JarLoader:
            def __init__(self, archive, name, ispkg, entry, origin):
                self.archive = archive
                self.name = name
                self.ispkg = ispkg
                self.entry = entry
                self.origin = origin

            def __repr__(self):
                return '<pyjvm jar loader for %r in %s>' % (self.name, self.archive)

            def create_module(self, spec):
                return None

            def exec_module(self, module):
                data = _zip_read(self.archive, self.entry)
                if data is None:
                    raise ImportError('%s is missing from %s' % (self.entry, self.archive), name=self.name, path=self.archive)
                exec(_make_code(data, self.origin), module.__dict__)

            def is_package(self, fullname):
                return self.ispkg

            def get_filename(self, fullname):
                return self.origin

            def get_source(self, fullname):
                return None

            def get_code(self, fullname):
                data = _zip_read(self.archive, self.entry)
                return None if data is None else _make_code(data, self.origin)


        class JarImporter:
            def __init__(self, archive, prefix=''):
                self.archive = archive
                self.prefix = prefix
                self._modules = _parse(archive)
                self._parent = prefix.replace('/', '.').strip('.')

            def __repr__(self):
                return '<JarImporter %s/%s>' % (self.archive, self.prefix)

            def find_spec(self, fullname, target=None):
                found = self._modules.get(fullname)
                if found is None or fullname.rpartition('.')[0] != self._parent:
                    return None
                ispkg, entry, source = found
                origin = self.archive + '/' + source
                spec = _ModuleSpec(fullname, JarLoader(self.archive, fullname, ispkg, entry, origin), origin=origin, is_package=ispkg)
                spec.has_location = True
                if ispkg:
                    spec.submodule_search_locations = [self.archive + '/' + fullname.replace('.', '/')]
                return spec

            def invalidate_caches(self):
                _parsed.pop(self.archive, None)
                self._modules = _parse(self.archive)
        """;

    // ---- natives ---------------------------------------------------------------------------------

    private static ZipFile open(String archive) throws IOException {
        ZipFile z = OPEN.get(archive);
        if (z == null) {
            ZipFile fresh = new ZipFile(archive);
            z = OPEN.putIfAbsent(archive, fresh);
            if (z == null) z = fresh; else fresh.close();
        }
        return z;
    }

    private static Object zipRead(List<Object> a, Map<Object,Object> k) {
        String archive = (String) a.get(0), name = (String) a.get(1);
        try {
            ZipFile z = open(archive);
            ZipEntry e = z.getEntry(name);
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) { return new PyBytes(in.readAllBytes()); }
        } catch (IOException e) {
            throw new PyException("ImportError", "cannot read " + archive + ": " + e.getMessage());
        }
    }

    private static Object makeCode(List<Object> a, Map<Object,Object> k) {
        PyCode code = new PyCode("<module>", (String) a.get(1), 1);
        code.classBytes = ((PyBytes) a.get(0)).data;
        code.mode = "exec";
        return code;
    }

    /** Splits {@code /x/lib.jar/pkg/sub} into the archive and the prefix inside it ({@code pkg/sub}); null when no component is an archive file. */
    static String[] splitArchive(String path) {
        int from = 0;
        while (true) {
            int end = path.indexOf('/', from);
            String head = end < 0 ? path : path.substring(0, end);
            if ((head.endsWith(".jar") || head.endsWith(".zip")) && Files.isRegularFile(Path.of(head)))
                return new String[]{head, end < 0 ? "" : path.substring(end + 1)};
            if (end < 0) return null;
            from = end + 1;
        }
    }

    private static Object hook(List<Object> a, Map<Object,Object> k) {
        Object arg = a.isEmpty() ? null : a.get(0);
        if (arg instanceof PyBytes b) arg = new String(b.data, java.nio.charset.StandardCharsets.UTF_8);
        String[] parts = arg instanceof String s ? splitArchive(s) : null;
        if (parts == null) throw new PyException("ImportError", "not a jar or zip archive");
        if (helper == null) loadHelper();
        ArrayList<Object> args = new ArrayList<>(List.of(parts[0], parts[1]));
        return PyRuntime.callFunction(PyRuntime.moduleGetattr(helper, "JarImporter"), args, new LinkedHashMap<Object,Object>());
    }

    private static synchronized void loadHelper() {
        if (helper != null) return;
        PyModule m = (PyModule) ImportSupport.moduleCall(new ArrayList<Object>(List.of("_pyjvm_jarimport")), new LinkedHashMap<Object,Object>());
        m.dict.put("MANIFEST", MANIFEST);
        m.dict.put("_zip_read", fn("_zip_read", JarImport::zipRead));
        m.dict.put("_make_code", fn("_make_code", JarImport::makeCode));
        m.dict.put("_lang", fn("_lang", (a, k) -> LangVersion.MAJOR + "." + LangVersion.MINOR));
        m.dict.put("_fingerprint", fn("_fingerprint", (a, k) -> ClassCache.fingerprint()));
        PyCode code = (PyCode) ExecSupport.compileBuiltin(new ArrayList<Object>(List.of(HELPER, "<frozen pyjvm.jarimport>", "exec")), new LinkedHashMap<Object,Object>());
        ExecSupport.run("exec", code, m.dict, m.dict, "exec");
        helper = m;
    }

    /** Puts the archive hook in front of importlib's path hooks. Called once importlib is installed. */
    @SuppressWarnings("unchecked")
    static void install(PyModule sys) {
        Object hooks = sys.attrs.get("path_hooks");
        if (hooks instanceof List<?> l) ((List<Object>) l).add(0, fn("jar_path_hook", JarImport::hook));
    }
}
