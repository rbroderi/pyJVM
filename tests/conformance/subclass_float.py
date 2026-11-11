class F(float):
    pass

f = F(2.5)
print(isinstance(f, float), isinstance(f, F), issubclass(F, float), type(f) is F, type(f).__name__)
print(f, repr(f), str(f), f + 1, 1 + f, f * 2, f - 1, f / 2, f // 2, f % 2, f ** 2, -f, +f, abs(f))
print(type(f + 1) is float, type(-f) is float, type(f * f) is float)
print(f == 2.5, 2.5 == f, f < 3, f > 2, f != 2.5, hash(f) == hash(2.5), bool(F(0.0)), bool(f))
print(float.__add__(f, 1), float.__mul__(f, 3), float.__repr__(f), float.__str__(f))
print(f.is_integer(), f.as_integer_ratio(), f.hex(), f.real, f.imag, f.conjugate(), int(f), round(f), round(f, 1), float(f), complex(f))
print(sorted([F(3.5), F(1.5)]), sum([F(1.5), F(2.5)]), max(F(1.5), F(9.5)), divmod(f, 2), "%.2f %s %r" % (f, f, f), "{} {:>6}".format(f, f), f"{f} {f:.3f}")
print(F("1.5"), F(3), F(), float.fromhex("0x1.8p0"), F.fromhex("0x1.8p0"), type(F.fromhex("0x1p0")).__name__)
f.unit = "m"
print(f.unit, f.__dict__)

class M(float):
    def __new__(cls, value, unit="m"):
        self = super().__new__(cls, value)
        self.unit = unit
        return self
    def __repr__(self):
        return "%s%s" % (float.__repr__(self), self.unit)
    def __add__(self, other):
        return M(float(self) + float(other), self.unit)
    def __bool__(self):
        return False
m = M(1.5, "kg")
print(m, repr(m), str(m), m + 1, (m + 1).unit, bool(m), float(m), m.unit)
print(F.__mro__ == (F, float, object), F.__base__ is float)
