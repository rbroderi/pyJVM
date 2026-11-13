import os
import shutil

size = os.terminal_size((80, 24))
print(size, size.columns, size.lines, tuple(size), size[0], len(size))
print(size == (80, 24), hash(size) == hash((80, 24)))
cols, lines = size
print(cols, lines)
print(shutil.get_terminal_size((100, 30)).columns >= 1)
try:
    os.terminal_size((1, 2, 3))
except TypeError as exc:
    print("TypeError")
