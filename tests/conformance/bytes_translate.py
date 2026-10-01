table = bytes.maketrans(b"abc", b"xyz")
print(len(table))
print(b"abracadabra".translate(table))
print(b"abracadabra".translate(None, b"ab"))
print(type(b"abc".translate(table)))

table2 = bytearray.maketrans(b"01", b"ab")
print(b"1010".translate(table2))
