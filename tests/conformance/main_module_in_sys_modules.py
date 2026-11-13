# The program being run is sys.modules['__main__'] (enum.global_enum, pickle and doctest look it up).
import sys
from enum import IntEnum, global_enum

main = sys.modules["__main__"]
print(main.__name__)
import __main__
print(__main__ is main)


@global_enum
class Month(IntEnum):
    JANUARY = 1
    FEBRUARY = 2


print(int(globals()["JANUARY"]), int(main.FEBRUARY), Month.__module__)
