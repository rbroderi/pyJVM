print(ord("A"), ord("é"), ord("😀"), ord(b"\xff"), ord(bytearray(b"a")))
print(chr(0), chr(65), chr(233), chr(0x1f600))
print(list(map(ord, "Abé😀")))
print(list(map(chr, [65, 66, 233, 0x1f600])))
print(list(map(str, [1, 2, 3])), list(map(repr, [1, "x", None])))
class Code:
    def __index__(self):
        return 65
print(chr(Code()))
for value in ["", "ab", b"", b"ab", 1, memoryview(b"a")]:
    try:
        ord(value)
    except TypeError:
        print("ord type error")
for value in [-1, 0x110000]:
    try:
        chr(value)
    except ValueError:
        print("chr value error")
try:
    chr(1.0)
except TypeError:
    print("chr type error")

try:
    chr(2**100)
except OverflowError:
    print("chr overflow")
print(ord("\0"), ord("\ud800"))
