s = "  \t hello \xa0　\n"
print(repr(s.strip()), repr(s.lstrip()), repr(s.rstrip()))
print("xxhixx".strip("x"), "xxhixx".lstrip("x"), "xxhixx".rstrip("x"), "abcba".strip("ab"), "abc".strip(""), " a ".strip(None))
print("\x1c\x1dz\x1f".strip() == "z", " e ".strip(), "\U0001f642a\U0001f642".strip("\U0001f642"))
print("é".encode("latin-1"), "é".encode("utf_8"), "é".encode("UTF8"), b"\xe9".decode("latin1"), b"\xe9".decode("iso-8859-1"))
print("abc".encode("ascii"), bytearray(b"\xc3\xa9").decode("utf-8"))
try:
    "x".encode("no-such-codec")
except LookupError as e:
    print("LookupError")
