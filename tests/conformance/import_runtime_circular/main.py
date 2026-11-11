import sys

LIBS = __file__.rpartition("/")[0] + "/libs"
sys.path.insert(0, LIBS)

import circ.a
print(circ.a.a_func())
print(sorted(k for k in sys.modules if k.startswith("circ")))

try:
    import circ.c
except ImportError as e:
    print(type(e).__name__, str(e).replace(LIBS, "<libs>"), e.name, e.name_from, e.path.replace(LIBS, "<libs>"))
print(sorted(k for k in sys.modules if k.startswith("circ")))
