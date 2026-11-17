import codecs
import io

import _codecs_cn, _codecs_hk, _codecs_jp, _codecs_kr, _codecs_tw
import _multibytecodec as mbc

SAMPLES = {
    "cp932": "日本語テキスト ｱｲｳ ①",
    "euc_jp": "日本語テキスト ｱｲｳ 補",
    "shift_jis": "日本語テキスト ｱｲｳ",
    "euc_jisx0213": "日本語 か゚ æ̀ 丂",
    "euc_jis_2004": "日本語 か゚ æ̀",
    "shift_jisx0213": "日本語 か゚ ˩˥",
    "shift_jis_2004": "日本語 か゚",
    "euc_kr": "한국어 텍스트 갂 가",
    "cp949": "한국어 텍스트 갂 똠",
    "johab": "한국어 텍스트 갂",
    "gb2312": "中文文本 简体",
    "gbk": "中文文本 简体 繁體",
    "gb18030": "中文文本 € \U0001f600 \u0080",
    "big5": "中文文本 繁體",
    "cp950": "中文文本 繁體",
    "big5hkscs": "中文文本 Ê̄ \U00020021 龲",
}

for name, text in SAMPLES.items():
    enc = text.encode(name)
    print(name, enc.hex(), enc.decode(name) == text, len(codecs.lookup(name).name) > 0)

# error handlers, positions and reasons
for name in ("cp932", "euc_kr", "gb18030", "big5"):
    for h in ("ignore", "replace", "xmlcharrefreplace", "backslashreplace"):
        print(name, h, "aé€\U0001f600b".encode(name, h) if name != "gb18030" else "a\ud800b".encode(name, h))
    try:
        "aé\U0001f600".encode(name) if name != "gb18030" else "a\ud800".encode(name)
    except UnicodeEncodeError as e:
        print(name, "enc", e.start, e.end, e.reason, str(e))
    for h in ("ignore", "replace", "backslashreplace", "surrogateescape"):
        print(name, h, ascii(b"a\xff\xfeb\x81".decode(name, h)))
    try:
        b"a\xff\xfeb".decode(name)
    except UnicodeDecodeError as e:
        print(name, "dec", e.start, e.end, e.reason, str(e))
    try:
        b"ab\x81".decode(name)
    except UnicodeDecodeError as e:
        print(name, "dec2", e.start, e.end, e.reason)


def handler(e):
    return ("<%d:%d>" % (e.start, e.end), e.end)


codecs.register_error("cjk_h", handler)
print("aéb".encode("euc_jp", "cjk_h"), b"a\xffb".decode("euc_jp", "cjk_h"))
codecs.register_error("cjk_b", lambda e: (b"??", e.end))
print("aéb".encode("euc_jp", "cjk_b"))
codecs.register_error("cjk_bad", lambda e: ("X", 99))
try:
    "aéb".encode("euc_jp", "cjk_bad")
except IndexError as e:
    print("IndexError", e)
codecs.register_error("cjk_neg", lambda e: ("X", -1))
print("aébc".encode("cp932", "cjk_neg"))
codecs.register_error("cjk_unenc", lambda e: ("é", e.end))
try:
    "aéb".encode("cp932", "cjk_unenc")
except UnicodeEncodeError as e:
    print("repl", e.start, e.end, ascii(e.object))

# incremental coding split inside multi-byte sequences
for name in ("cp932", "euc_jp", "gb18030", "big5hkscs", "euc_kr", "euc_jisx0213"):
    text = SAMPLES.get(name, "日本語")
    data = text.encode(name)
    d = codecs.getincrementaldecoder(name)()
    out = [d.decode(data[i:i + 1]) for i in range(len(data))] + [d.decode(b"", True)]
    print(name, "inc-dec", "".join(out) == text, d.getstate())
    e = codecs.getincrementalencoder(name)()
    out = b"".join(e.encode(c) for c in text) + e.encode("", True)
    print(name, "inc-enc", out == data, e.getstate())
d = codecs.getincrementaldecoder("cp932")()
print(d.decode(b"\x82"), d.getstate())
d.setstate((b"\x82", 0))
print(d.decode(b"\xa0"), d.getstate())
try:
    d.decode(b"\x82", True)
except UnicodeDecodeError as e:
    print(e.reason, e.start, e.end)
e = codecs.getincrementalencoder("euc_jisx0213")()
print(e.encode("か"), e.getstate(), e.encode("゚"), e.getstate(), e.encode("か"), e.encode("", True))
e.setstate(2340545283)
print(e.encode("゚"))

# stream reader / writer
data = "あいう\nえお\n".encode("cp932") * 3
r = codecs.getreader("cp932")(io.BytesIO(data))
print(repr(r.read(3)), repr(r.readline()), repr(r.readlines()[:2]), repr(r.read()))
b = io.BytesIO()
w = codecs.getwriter("euc_jp")(b)
w.write("あ")
w.writelines(["い", "う"])
w.reset()
print(b.getvalue())

# module surface
c = _codecs_jp.getcodec("cp932")
print(type(c).__name__, type(c).__module__, c.encode("あ"), c.decode(b"\x82\xa0"), c.encode("あ", None))
for fn, arg in ((_codecs_jp.getcodec, "nope"), (_codecs_jp.getcodec, 1), (c.encode, "é"), (c.decode, "a")):
    try:
        fn(arg)
    except (LookupError, TypeError, UnicodeError) as e:
        print(type(e).__name__, e)
try:
    type(c)()
except TypeError as e:
    print(e)
print(sorted(n for n in dir(mbc) if not n.startswith("_")))
print(_codecs_kr.getcodec("euc_kr") is not None, _codecs_cn.getcodec("gbk") is not None, _codecs_tw.getcodec("big5") is not None,
      _codecs_hk.getcodec("big5hkscs") is not None)

# the encodings modules import and round-trip
import importlib
for n in ("big5", "big5hkscs", "cp932", "cp949", "cp950", "euc_jis_2004", "euc_jisx0213", "euc_jp", "euc_kr", "gb18030", "gb2312",
          "gbk", "johab", "shift_jis", "shift_jis_2004", "shift_jisx0213"):
    m = importlib.import_module("encodings." + n)
    ci = m.getregentry()
    print(n, ci.name, ci.incrementalencoder.__mro__[1].__name__, ci.streamreader.__mro__[2].__name__)
