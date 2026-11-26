# N2 ticket 2: argument-count texts and prlimit/setrlimit argument shapes. CPython only. No limit is changed (every call fails early or reads).
import resource

def t(label, f, *a):
    try:
        print(label, "->", repr(f(*a)))
    except Exception as e:
        print(label, "!!", type(e).__name__, e.args)

t("setrlimit 0 args", resource.setrlimit)
t("setrlimit 1 arg", resource.setrlimit, 4)
t("setrlimit 3 args", resource.setrlimit, 4, (1, 1), 3)
t("prlimit 0 args", resource.prlimit)
t("prlimit 1 arg", resource.prlimit, 0)
t("prlimit 4 args", resource.prlimit, 0, 4, None, 5)
t("prlimit kw", lambda: resource.prlimit(0, 4, limits=None))
t("getrusage 0 args", resource.getrusage)
t("getrusage 2 args", resource.getrusage, 0, 1)
t("getrlimit 2 args", resource.getrlimit, 0, 1)
t("setrlimit res None", resource.setrlimit, None, (1, 1))
t("setrlimit res float", resource.setrlimit, 1.5, (1, 1))
t("setrlimit res True invalid-limits", resource.setrlimit, True, None)
t("setrlimit limits str-2", resource.setrlimit, 4, "ab")
t("setrlimit limits gen", resource.setrlimit, 4, (x for x in (1, 2, 3)))
t("setrlimit limits None", resource.setrlimit, 4, None)
t("prlimit limits str", resource.prlimit, 0, 4, "ab")
t("prlimit pid float", resource.prlimit, 1.5, 4)
t("prlimit pid -2**40", resource.prlimit, -2**40, 4)
t("prlimit pid True res", resource.prlimit, 0, 99, (1, 1))
t("getrlimit True", resource.getrlimit, True)
t("getrusage None", resource.getrusage, None)
t("getrlimit RLIMIT_AS type", lambda: type(resource.getrlimit(resource.RLIMIT_AS)[0]).__name__)
print(type(resource.RLIM_INFINITY).__name__, [n for n in dir(resource) if n.startswith("RLIM_")])
print(resource.prlimit(0, resource.RLIMIT_NOFILE, None) == resource.getrlimit(resource.RLIMIT_NOFILE))


class I:
    def __index__(self):
        return 3


t("getrlimit __index__", lambda: len(resource.getrlimit(I())))
