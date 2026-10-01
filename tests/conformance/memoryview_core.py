buf = bytearray(b"abc")
view = memoryview(buf)
print(len(view))
print(view[1])
print(view.readonly)
view[1] = 90
print(buf)
print(view.tobytes())
print(view.tolist())
sub = view[1:3]
sub[0] = 89
print(buf)

ro = memoryview(b"xy")
print(ro.readonly)
print(ro.tobytes())
