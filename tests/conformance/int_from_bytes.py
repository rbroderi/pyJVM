# SPDX-License-Identifier: GPL-3.0-or-later
# int() parses bytes-like numerals on the direct builtin call path too (pickle protocol 0 relies on it).
import io

print(int(b'32'), int(b'32\n'[:-1]), int(bytearray(b' -7 ')), int(memoryview(b'1_000')))
print(int(b'ff', 16), int(b'0x1f', 0), int(bytearray(b'101'), 2))
line = io.BytesIO(b'44\n').readline()
print(type(line).__name__, int(line), int(line, 0))
for bad in (b'', b'12a', b'1 2', b'\xff'):
    try:
        int(bad)
    except ValueError as e:
        print('ValueError', e)
