# SPDX-License-Identifier: GPL-3.0-or-later
# Default text encodings: UTF-8 mode (on from 3.15, PEP 686), the locale encoding (the ANSI code page on Windows),
# and what open(), TextIOWrapper and io.text_encoding pick from them. Stdio is left out: the runner sets PYTHONIOENCODING.
import _locale
import io
import locale
import os
import sys
import tempfile

print("utf8_mode", sys.flags.utf8_mode, sys.flags.warn_default_encoding)
print("getencoding", _locale.getencoding(), locale.getencoding())
print("getpreferredencoding", locale.getpreferredencoding(False), locale.getpreferredencoding())
print("text_encoding", io.text_encoding(None), io.text_encoding("latin-1"))
print("fs", sys.getfilesystemencoding())
p = os.path.join(tempfile.gettempdir(), "pyjvm_encoding_defaults_%d.txt" % os.getpid())
with open(p, "w") as f:
    print("open w", f.encoding, f.errors)
    f.write("a\nb\n")
with open(p, encoding="locale") as f:
    print("open locale", f.encoding == locale.getencoding())
with open(p, "rb") as f:
    print("bytes", f.read() == ("a" + os.linesep + "b" + os.linesep).encode())
with open(p) as f:
    print("open r", f.encoding, f.read())
os.remove(p)
print("TextIOWrapper", io.TextIOWrapper(io.BytesIO()).encoding, io.TextIOWrapper(io.BytesIO(), encoding="locale").encoding == locale.getencoding())
w = io.TextIOWrapper(io.BytesIO(), encoding="utf-8")
w.reconfigure(encoding="locale")
print("reconfigure", w.encoding == locale.getencoding())
