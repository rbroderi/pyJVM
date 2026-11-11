# pyjvm: requires-python >= 3.14
import codecs

def show(label, fn):
    try:
        print(label, repr(fn()))
    except Exception as e:
        print(label, type(e).__name__, e)

s = "héllo € \U0001F600 end"
for enc in ("utf-8", "utf8", "UTF-8", "latin-1", "ascii", "utf-16", "utf-16-le", "utf-16-be", "utf-32", "utf-32-le", "utf-32-be", "cp1252", "cp437"):
    show("encode " + enc, lambda: s.encode(enc))
    show("encode-replace " + enc, lambda: s.encode(enc, "replace"))
    show("roundtrip " + enc, lambda: s.encode(enc, "replace").decode(enc, "replace"))

for errors in ("strict", "ignore", "replace", "backslashreplace", "xmlcharrefreplace", "namereplace", "surrogateescape"):
    show("ascii/" + errors, lambda: "aéb€c\U0001F600".encode("ascii", errors))
    show("latin1/" + errors, lambda: "aéb€c".encode("latin-1", errors))
    show("dec utf8/" + errors, lambda: b"a\xffb\xc3\x28c\xe2\x82".decode("utf-8", errors))
    show("dec ascii/" + errors, lambda: b"a\xffb\x80".decode("ascii", errors))

show("surrogates strict", lambda: "a\udc80b".encode("utf-8"))
show("surrogates escape", lambda: "a\udc80b".encode("utf-8", "surrogateescape"))
show("surrogates pass", lambda: "a\ud800b".encode("utf-8", "surrogatepass"))
show("decode escape", lambda: b"a\xffb".decode("utf-8", "surrogateescape"))
show("decode pass", lambda: b"a\xed\xa0\x80b".decode("utf-8", "surrogatepass"))

for data in (b"\xff", b"\xc0\x80", b"\xe0\x80\x80", b"\xed\xa0\x80", b"\xf0\x80\x80\x80", b"\xf4\x90\x80\x80", b"\xe2\x82", b"\xe2", b"\xf0\x9f\x98", b"abc\xe2\x82\xac\xc3"):
    show("utf8 " + repr(data), lambda: data.decode("utf-8"))
    show("utf8-partial " + repr(data), lambda: codecs.utf_8_decode(data, "ignore", False))

show("utf16 odd", lambda: b"a\x00b".decode("utf-16-le"))
show("utf16 lone high", lambda: b"\x00\xd8a\x00".decode("utf-16-le"))
show("utf16 lone low", lambda: b"\x00\xdca\x00".decode("utf-16-le"))
show("utf16 bom", lambda: b"\xff\xfea\x00".decode("utf-16"))
show("utf16 bom be", lambda: b"\xfe\xff\x00a".decode("utf-16"))
show("utf32 big", lambda: b"\x00\x00\x00\x00\xff\xff\xff\xff"[4:].decode("utf-32-be"))
show("utf32 trunc", lambda: b"\x00\x00\x00".decode("utf-32-le"))

e = UnicodeDecodeError("utf-8", b"abc\xff", 3, 4, "invalid start byte")
print(e, e.encoding, e.object, e.start, e.end, e.reason)
e = UnicodeEncodeError("ascii", "a€b", 1, 2, "ordinal not in range(128)")
print(e, e.encoding, e.object, e.start, e.end, e.reason)
e = UnicodeEncodeError("ascii", "a€bcd", 1, 4, "boom")
print(e)
e = UnicodeTranslateError("a€b", 1, 2, "boom")
print(e, e.object, e.start, e.end, e.reason)
print(isinstance(e, ValueError), isinstance(e, UnicodeError))

def handler(exc):
    print("handler", type(exc).__name__, exc.start, exc.end, exc.reason)
    return ("<%d>" % (exc.end - exc.start), exc.end)
codecs.register_error("test.h", handler)
show("custom enc", lambda: "a€b€".encode("ascii", "test.h"))
show("custom dec", lambda: b"a\xffb\xfe".decode("ascii", "test.h"))
show("custom bytes", lambda: "a€b".encode("ascii", lambda e: None) if False else None)
codecs.register_error("test.bytes", lambda exc: (b"?!", exc.end))
show("custom bytes enc", lambda: "a€b".encode("ascii", "test.bytes"))
show("bad handler name", lambda: "a€".encode("ascii", "nope"))
show("lookup_error", lambda: codecs.lookup_error("strict").__name__)
show("lookup_error bad", lambda: codecs.lookup_error("nope"))

info = codecs.lookup("UTF-8")
print(info.name, info.__class__.__name__)
print(codecs.lookup("latin1").name, codecs.lookup("us-ascii").name, codecs.lookup("cp1252").name)
show("lookup bad", lambda: codecs.lookup("no-such-codec"))
show("encode bad", lambda: "x".encode("no-such-codec"))
show("rot13", lambda: codecs.encode("hello", "rot13"))
show("charmap cp1252", lambda: bytes(range(0x80, 0x90)).decode("cp1252", "replace"))
show("unicode_escape", lambda: "a€\n\xe9".encode("unicode_escape"))
show("unicode_escape dec", lambda: b"a\\u20ac\\n\\xe9\\N{BULLET}\\101".decode("unicode_escape"))
show("unicode_escape bad", lambda: b"\\u20".decode("unicode_escape"))
show("raw_unicode_escape", lambda: "a€\xe9".encode("raw_unicode_escape"))
show("escape_encode", lambda: codecs.escape_encode(b"a\n\xff'"))
show("escape_decode", lambda: codecs.escape_decode(b"a\\n\\xff\\101"))
show("bytes(str, enc)", lambda: bytes("hé", "latin-1"))
show("str(bytes, enc)", lambda: str(b"h\xe9", "latin-1"))
show("bytearray decode", lambda: bytearray(b"h\xc3\xa9").decode())
show("kw decode", lambda: b"h\xe9".decode(encoding="ascii", errors="replace"))
show("utf_8_encode", lambda: codecs.utf_8_encode("hé"))
show("utf_16_ex_decode", lambda: codecs.utf_16_ex_decode(b"\xff\xfeh\x00", "strict", 0, True))
show("incremental utf8", lambda: [codecs.getincrementaldecoder("utf-8")().decode(b, False) for b in (b"\xe2", b"\x82")])
dec = codecs.getincrementaldecoder("utf-8")()
print(repr(dec.decode(b"\xe2\x82")), repr(dec.decode(b"\xac")), repr(dec.decode(b"", True)))
enc = codecs.getincrementalencoder("utf-16")()
print(enc.encode("ab"), enc.encode("c", True))
print(codecs.BOM_UTF8, codecs.BOM_UTF16_LE)
