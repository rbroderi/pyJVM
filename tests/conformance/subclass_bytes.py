class B(bytes):
    pass

b = B(b"hello")
print(isinstance(b, bytes), isinstance(b, B), issubclass(B, bytes), type(b) is B, type(b).__name__)
print(b, repr(b), str(b), len(b), b[0], b[1:3], b + b"!", b * 2, b"<" + b, b == b"hello", b"hello" == b, hash(b) == hash(b"hello"), bool(B()), bool(b))
print(b.upper(), b.split(b"l"), b.replace(b"l", b"L"), b.startswith(b"he"), b.find(b"l"), b.hex(), b.decode(), 104 in b, b"ell" in b, list(b))
print(type(b[1:3]).__name__, type(b + b"x").__name__, type(b.upper()).__name__, type(bytes(b)).__name__)
print(bytes.__len__(b), bytes.upper(b), bytes.__add__(b, b"x"), bytes.__repr__(b))
print(B(3), B([1, 2]), B("hé", "utf-8"), B(), B.fromhex("6869"), type(B.fromhex("6869")).__name__, sorted(b), {b: 1}[b"hello"])
print(b + b"-5", bytes(b), bytearray(b), b"".join([b, b]), memoryview(b).tobytes())
b.tag = 1
print(b.tag, b.__dict__)

class Tagged(bytes):
    def __new__(cls, data, tag):
        self = super().__new__(cls, data)
        self.tag = tag
        return self
    def __repr__(self):
        return "Tagged(%s)" % bytes.__repr__(self)
    def __len__(self):
        return 77
    def __getitem__(self, i):
        return "item"
    def __add__(self, other):
        return "added"
    def __eq__(self, other):
        return True
    def __hash__(self):
        return 3
t = Tagged(b"ab", "x")
print(repr(t), t.tag, len(t), t[0], t + b"c", bool(t == 5), hash(t), bytes(t), bytes.__len__(t), t.upper(), list(t))

class BA(bytearray):
    pass
a = BA(b"abc")
print(isinstance(a, bytearray), type(a).__name__, a, repr(a), len(a), a[0], a[1:], a + b"d", a == bytearray(b"abc"), a == b"abc")
a.append(100)
a.extend(b"ef")
a[0] = 65
del a[1]
print(a, bytes(a), a.upper(), type(a.upper()).__name__, a.pop(), a, a.decode(), 65 in a, list(a), bytearray.__len__(a), bytearray.append(a, 1), len(a))
a += b"xy"
print(a, type(a).__name__)
a.tag = "t"
print(a.tag, a.__dict__)

class LogBA(bytearray):
    def __init__(self, *args):
        super().__init__(*args)
        self.log = []
    def append(self, v):
        self.log.append(v)
        super().append(v)
    def __setitem__(self, i, v):
        self.log.append(("set", i))
        super().__setitem__(i, v)
l = LogBA(b"xy")
l.append(1)
l[0] = 2
print(l.log, l, len(l))
print(B.__mro__ == (B, bytes, object), BA.__mro__ == (BA, bytearray, object))
