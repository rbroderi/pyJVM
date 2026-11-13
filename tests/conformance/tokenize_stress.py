# Token streams of stdlib sources must match CPython's exactly, both through generate_tokens and with extra_tokens off.
import hashlib
import os
import tokenize
import _tokenize

FILES = ["os.py", "tokenize.py", "textwrap.py", "argparse.py", "ast.py", "bisect.py", "calendar.py", "codecs.py",
         "collections/__init__.py", "colorsys.py", "contextlib.py", "copy.py", "csv.py", "dataclasses.py", "difflib.py",
         "enum.py", "fnmatch.py", "fractions.py", "functools.py", "heapq.py", "inspect.py", "json/decoder.py",
         "json/encoder.py", "linecache.py", "operator.py", "pprint.py", "queue.py", "random.py", "re/_parser.py",
         "shlex.py", "string.py", "stat.py", "struct.py", "textwrap.py", "traceback.py", "typing.py", "types.py",
         "uuid.py", "weakref.py", "zipfile/__init__.py"]
base = os.path.dirname(tokenize.__file__)


def digest(items):
    h = hashlib.sha1()
    n = 0
    for item in items:
        h.update(repr(tuple(item)).encode())
        n += 1
    return n, h.hexdigest()[:12]


seen = set()
for name in FILES:
    path = os.path.join(base, name)
    if name in seen or not os.path.exists(path):
        continue
    seen.add(name)
    with open(path, "rb") as f:
        data = f.read()
    text = data.decode("utf-8")
    lines = iter(text.splitlines(True))
    n, h = digest(tokenize.generate_tokens(lambda: next(lines, "")))
    lines = iter(text.splitlines(True))
    m, g = digest(_tokenize.TokenizerIter(lambda: next(lines, ""), extra_tokens=False))
    print(name, n, h, m, g)

# The parser's view (extra_tokens=False): comments and NL vanish, a trailing comment becomes the NEWLINE string, and the
# tokens at the end of the file take the position of the last line (with an empty line after skipped comments).
for text in ["if a:\n    b\n", "if a:\n    b\n    # c\n", "if a:\n    b\n\n", "if a:\n    b\n    # c", "if a:\n    b  # c\n", "# c\n",
             "x\n# c\n", "if a:\n  b\n  # c\n\n", "", "\n", "x = 1", "(a,\n # c\n b)  # d\n", "a = 1 \\\n  + 2\n", "x = f'{a:>{b}}'\n"]:
    lines = iter(text.splitlines(True))
    print(repr(text))
    for t in _tokenize.TokenizerIter(lambda: next(lines, ""), extra_tokens=False):
        print("   ", tuple(t))
