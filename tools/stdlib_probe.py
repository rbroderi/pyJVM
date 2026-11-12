# SPDX-License-Identifier: GPL-3.0-or-later
"""Per-module import smoke test (roadmap P3-04). Runs unchanged under the reference CPython and under pyJVM.

The module to probe comes from ``PYJVM_PROBE_MODULE``. Output lines (tab separated, one marker per line):

    IMPORT-FAIL <ExcType: message>      the import raised
    ORIGIN      <repr of __file__>      where the module came from (a ``.jar`` path for an archive import)
    NAMES       <public names>          the module's public API, modules excluded
    SMOKE       <repr of the result>    a short behavioural check (``SMOKE_CASES``), or ``-`` when none is defined

``tools/stdlib_status.py`` runs this once on the host and once on the JVM and compares the NAMES and SMOKE lines, so a module
that imports from the jar but behaves differently is reported as such.
"""
import importlib
import os
import sys
import types

# module name -> expression evaluated with the imported module bound to ``m``
SMOKE_CASES = {
    "abc": "m.ABC.__name__ + repr(m.abstractmethod(lambda self: 1).__isabstractmethod__)",
    "argparse": "(lambda p: (p.add_argument('--n', type=int), repr(vars(p.parse_args(['--n', '3']))))[1])(m.ArgumentParser(prog='p'))",
    "ast": "m.dump(m.parse('x = 1 + 2'))",
    "base64": "repr((m.b64encode(b'hello world'), m.b64decode('aGk='), m.b32encode(b'ab'), m.b16encode(b'\\xff')))",
    "bisect": "repr((m.bisect_left([1, 3, 5], 3), m.bisect_right([1, 3, 5], 3)))",
    "calendar": "repr((m.isleap(2024), m.monthrange(2024, 2), m.weekday(2024, 3, 1)))",
    "collections": "repr((m.Counter('abracadabra').most_common(2), m.OrderedDict(a=1), m.deque([1, 2, 3], 2), m.namedtuple('P', 'x y')(1, 2)))",
    "colorsys": "repr(m.rgb_to_hsv(0.2, 0.4, 0.4))",
    "configparser": "repr((lambda c: (c.read_string('[s]\\na = 1\\n'), c['s']['a'])[1])(m.ConfigParser()))",
    "contextlib": "repr((m.suppress(KeyError).__class__.__name__, m.nullcontext(3).__enter__()))",
    "copy": "repr((m.deepcopy({'a': [1, 2]}), m.copy([1, 2])))",
    "csv": "repr((list(m.reader(['a,b', '1,\"2,3\"'])), ))",
    "dataclasses": "repr(m.make_dataclass('D', [('x', int)])(1))",
    "datetime": "repr((m.date(2024, 2, 29) + m.timedelta(days=1), m.datetime(2020, 1, 2, 3, 4, 5).isoformat(), m.timedelta(hours=30)))",
    "decimal": "repr((m.Decimal('1.10') + m.Decimal('2.205'), m.Decimal(1) / m.Decimal(7)))",
    "difflib": "repr(list(m.unified_diff(['a\\n', 'b\\n'], ['a\\n', 'c\\n'])))",
    "enum": "repr(list(m.Enum('Color', 'RED GREEN')))",
    "fnmatch": "repr((m.fnmatch('foo.txt', '*.txt'), m.filter(['a.py', 'b.c'], '*.py'), m.translate('*.py')))",
    "fractions": "repr((m.Fraction(3, 6) + m.Fraction(1, 3), m.Fraction('0.75')))",
    "functools": "repr((m.reduce(lambda a, b: a * b, range(1, 6)), m.partial(int, base=2)('101'), m.lru_cache(None)(lambda x: x + 1)(1)))",
    "getopt": "repr(m.getopt(['-a', 'x', '--long=1', 'rest'], 'a:', ['long=']))",
    "glob": "repr(m.translate('*.py') if hasattr(m, 'translate') else m.escape('a[b]'))",
    "hashlib": "repr((m.md5(b'abc').hexdigest(), m.sha256(b'abc').hexdigest()[:16]))",
    "heapq": "repr((lambda h: (m.heapify(h), m.heappush(h, 0), [m.heappop(h) for _ in range(3)])[2])([5, 3, 4]))",
    "html": "repr((m.escape('<a href=\"x\">&</a>'), m.unescape('&lt;&amp;&#65;')))",
    "inspect": "repr((str(m.signature(lambda a, b=2, *c, d, **e: 0)), m.isfunction(len), m.getdoc(m)[:12]))",
    "io": "repr((m.StringIO('a\\nb').readlines(), m.BytesIO(b'xyz').read(2)))",
    "ipaddress": "repr((m.ip_address('192.168.1.1').is_private, m.ip_network('10.0.0.0/30').num_addresses))",
    "itertools": "repr((list(m.islice(m.count(5), 3)), list(m.permutations('abc', 2))[:3]))",
    "json": "m.dumps({'a': [1, 2.5, None, True], 'b': 'é'}, sort_keys=True) + repr(m.loads('{\"x\": [1, {\"y\": \"z\"}]}'))",
    "keyword": "repr((m.iskeyword('class'), len(m.kwlist) > 30))",
    "linecache": "repr(m.getline('/nonexistent/file.py', 1))",
    "locale": "repr(m.normalize('en_US'))",
    "logging": "repr((m.getLevelName(30), m.getLogger('x').level, m.Formatter('%(levelname)s:%(message)s').format(m.LogRecord('n', 20, 'p', 1, 'hi', None, None))))",
    "math": "repr((m.gcd(12, 18), m.isqrt(99), m.comb(5, 2)))",
    "numbers": "repr((isinstance(1, m.Integral), isinstance(1.5, m.Rational)))",
    "operator": "repr((m.add(1, 2), m.itemgetter(1)([5, 6]), m.attrgetter('real')(3)))",
    "os": "repr((m.path.join('a', 'b'), m.fspath('x'), m.sep, m.name))",
    "pathlib": "repr((m.PurePosixPath('/a/b/c.txt').parts, m.PurePosixPath('/a/b/c.txt').suffix, m.PurePosixPath('a') / 'b'))",
    "pprint": "m.pformat({'k': list(range(30)), 'z': {'a': 1}}, width=40)",
    "queue": "repr((lambda q: (q.put(1), q.put(2), q.get(), q.qsize())[2:])(m.Queue()))",
    "quopri": "repr((m.encodestring(b'a=b \\xe9') if hasattr(m, 'encodestring') else m.decodestring(b'a=3Db')))",
    "random": "repr((m.Random(7).random(), m.Random(7).randint(1, 100), m.Random(7).choice('abcdef')))",
    "re": "repr((m.sub(r'(\\d+)', lambda x: str(int(x.group(1)) * 2), 'a1b22'), m.findall(r'\\w+', 'hi there'), m.match(r'(?P<k>\\w)=(\\d)', 'a=1').groupdict()))",
    "reprlib": "m.repr(list(range(100)))",
    "shlex": "repr((m.split('a \"b c\" d\\\\ e'), m.quote(\"it's\")))",
    "statistics": "repr((m.mean([1, 2, 3, 4]), m.median([5, 1, 3]), m.stdev([2, 4, 4, 4, 5, 5, 7, 9])))",
    "string": "repr((m.capwords('hello world'), m.Template('$a-${b}').substitute(a=1, b=2), m.ascii_lowercase[:3]))",
    "struct": "repr((m.pack('<hi', 1, 2), m.unpack('>I', b'\\x00\\x00\\x01\\x00'), m.calcsize('<qb')))",
    "tempfile": "repr(m.gettempprefix())",
    "textwrap": "repr((m.fill('the quick brown fox jumps over the lazy dog', 15), m.dedent('  a\\n   b'), m.shorten('hello world foo', 12)))",
    "threading": "repr((m.current_thread().name, m.active_count()))",
    "time": "repr(m.gmtime(0)[:6])",
    "tokenize": "repr([t.string for t in m.generate_tokens(__import__('io').StringIO('a = 1 + 2\\n').readline)])",
    "traceback": "repr(m.format_exception_only(ValueError, ValueError('bad')))",
    "types": "repr((m.SimpleNamespace(a=1), m.FunctionType.__name__, type(m.MappingProxyType({})).__name__))",
    "typing": "repr((m.List[int], m.Optional[str], m.Union[int, str], m.get_origin(m.Dict[str, int])))",
    "unicodedata": "repr((m.name('A'), m.category('é'), m.normalize('NFD', 'é')))",
    "urllib.parse": "repr((m.urlparse('http://u:p@h:80/a/b?x=1#f'), m.quote('a b/ü'), m.parse_qs('a=1&a=2&b=3')))",
    "uuid": "repr((m.UUID('12345678123456781234567812345678'), m.UUID(int=5).version))",
    "warnings": "repr(m.formatwarning('msg', UserWarning, 'f.py', 3, 'line'))",
    "weakref": "repr((lambda o: m.ref(o)() is o)(type('T', (), {})()))",
    "zipfile": "repr(m.is_zipfile('/nonexistent/x.zip'))",
}


def _names(mod):
    declared = getattr(mod, "__all__", None)
    if isinstance(declared, (list, tuple)):
        return sorted(str(n) for n in declared)
    return sorted(n for n in dir(mod) if not n.startswith("_") and not isinstance(getattr(mod, n, None), types.ModuleType))


def _oneline(text):
    return str(text).replace("\\", "\\\\").replace("\n", "\\n").replace("\t", " ").replace("\r", "")


def main():
    name = os.environ["PYJVM_PROBE_MODULE"]
    if name == "--fingerprint":
        print("FINGERPRINT\t%s" % sys._pyjvm_fingerprint())
        return
    try:
        mod = importlib.import_module(name)
    except BaseException as e:
        print("IMPORT-FAIL\t%s: %s" % (type(e).__name__, _oneline(e)[:300]))
        return
    print("ORIGIN\t%s" % _oneline(repr(getattr(mod, "__file__", None))))
    try:
        print("NAMES\t%s" % " ".join(_names(mod)))
    except BaseException as e:
        print("NAMES\t!%s: %s" % (type(e).__name__, _oneline(e)[:200]))
    case = SMOKE_CASES.get(name)
    if case is None:
        print("SMOKE\t-")
        return
    try:
        print("SMOKE\t%s" % _oneline(eval(case, {"m": mod})))
    except BaseException as e:
        print("SMOKE\t!%s: %s" % (type(e).__name__, _oneline(e)[:200]))


main()
sys.stdout.flush()
