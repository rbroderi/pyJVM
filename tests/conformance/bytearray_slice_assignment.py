b = bytearray(b"abcdef")
b[1:4] = b"XY"
print(b)
b[2:2] = [49, 50]
print(b)
b[::2] = b"1234"
print(b)
b[::-2] = b"WXYZ"
print(b)
