print((255).bit_length(), (0).bit_length(), (-5).bit_length(), (2**100).bit_length(), (7).bit_count(), (-7).bit_count(), True.bit_length())
print((1).to_bytes(2, "big"), (258).to_bytes(2, "little"), (0).to_bytes(0, "big"), (255).to_bytes(1, "big"), (-1).to_bytes(2, "big", signed=True), (-2).to_bytes(3, "little", signed=True))
print((2**64).to_bytes(9, "big"), (1000).to_bytes(length=4, byteorder="big"), (128).to_bytes(1, "big"), (-128).to_bytes(1, "big", signed=True))
for bad in ("(256).to_bytes(1, 'big')", "(-1).to_bytes(1, 'big')", "(128).to_bytes(1, 'big', signed=True)", "(1).to_bytes(1, 'middle')"):
    try:
        eval(bad, {})
    except (OverflowError, ValueError) as e:
        print(type(e).__name__, e)
print(int.from_bytes(b"\x01\x02", "big"), int.from_bytes(b"\x01\x02", "little"), int.from_bytes(b"\xff\xff", "big", signed=True), int.from_bytes(b"\xff\xff", "big"), int.from_bytes(b"", "big"))
print(int.from_bytes(bytes([1, 0, 0, 0, 0, 0, 0, 0, 0]), "little"), int.from_bytes([0, 255], byteorder="big"), int.from_bytes(bytearray(b"\x80"), "big", signed=True))
f = (300).to_bytes
print(f(2, "big"), (5).conjugate(), (5).is_integer() if False else "ok", (7).as_integer_ratio())
