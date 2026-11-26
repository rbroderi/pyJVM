# resource: getrlimit/setrlimit/prlimit/getrusage/getpagesize.
# Prints only facts that are the same for CPython and pyJVM on one host: no limit values (the JVM raises its own soft
# RLIMIT_NOFILE to the hard limit), no usage numbers.
# SAFETY (work/n2_plan.md 2.3): the only change to this process is the soft RLIMIT_NOFILE lowered by ONE and restored,
# and RLIMIT_CORE rewritten with its own values; no hard limit of this process is ever lowered; real lowering is done
# with prlimit on a `sleep 30` child this script starts and kills by pid. Refuses to do the error lines as root.
import os
import sys

print(os.name)
if os.name == "posix":
    import resource
    import subprocess
    import warnings

    def t(label, f, *a):
        try:
            r = f(*a)
            print(label, "->", repr(r))
            return r
        except Exception as e:
            print(label, "!!", type(e).__name__, e.args)

    events = []
    sys.addaudithook(lambda ev, args: events.append((ev, len(args))) if ev.startswith("resource.") else None)

    print(sorted(n for n in dir(resource) if not n.startswith("_") and not n.startswith("RLIM")))
    names = sorted(n for n in dir(resource) if n.startswith("RLIMIT_"))
    print(names, [getattr(resource, n) for n in names])
    print(resource.RLIM_INFINITY, resource.RUSAGE_SELF, resource.RUSAGE_CHILDREN, resource.RUSAGE_THREAD, resource.error is OSError)
    INF = resource.RLIM_INFINITY
    for n in names:
        v = resource.getrlimit(getattr(resource, n))
        print(n, type(v).__name__, len(v), type(v[0]).__name__, type(v[1]).__name__, v[1] == INF or (v[0] != INF and v[0] <= v[1]))
    print("stack hard inf", resource.getrlimit(resource.RLIMIT_STACK)[1] == INF, "as", resource.getrlimit(resource.RLIMIT_AS) == (INF, INF))
    t("getrlimit -1", resource.getrlimit, -1)
    t("getrlimit 16", resource.getrlimit, 16)
    t("getrlimit str", resource.getrlimit, "x")
    t("getrlimit float", resource.getrlimit, 1.0)
    t("getrlimit 2**40", resource.getrlimit, 2**40)
    t("getrlimit keyword", lambda: resource.getrlimit(resource=1))
    t("getrlimit no args", resource.getrlimit)

    NOFILE = resource.RLIMIT_NOFILE
    soft, hard = resource.getrlimit(NOFILE)
    print("nofile sane", soft >= 256, hard != INF)
    t("setrlimit same", resource.setrlimit, NOFILE, (soft, hard))
    t("setrlimit soft-1", resource.setrlimit, NOFILE, (soft - 1, hard))
    print("lowered", resource.getrlimit(NOFILE) == (soft - 1, hard))
    t("setrlimit restore (list)", resource.setrlimit, NOFILE, [soft, hard])
    print("restored", resource.getrlimit(NOFILE) == (soft, hard))
    if os.getuid() != 0:
        t("soft > hard", resource.setrlimit, NOFILE, (hard + 1, hard))
        t("raise hard", resource.setrlimit, NOFILE, (soft, hard + 1))
        t("hard 2**63", resource.setrlimit, NOFILE, (soft, 2**63))
    else:
        print("root: error lines skipped")
    t("1-tuple", resource.setrlimit, NOFILE, (soft,))
    t("3-tuple", resource.setrlimit, NOFILE, (soft, hard, 1))
    t("int", resource.setrlimit, NOFILE, 5)
    t("str items", resource.setrlimit, NOFILE, ("a", "b"))
    t("float item", resource.setrlimit, NOFILE, (float(soft), hard))
    t("bad resource", resource.setrlimit, 99, (1, 1))
    t("2**70", resource.setrlimit, NOFILE, (2**70, 2**70))
    t("2**64", resource.setrlimit, NOFILE, (soft, 2**64))
    t("keyword", lambda: resource.setrlimit(NOFILE, limits=(soft, hard)))
    cs, ch = resource.getrlimit(resource.RLIMIT_CORE)
    t("core same", resource.setrlimit, resource.RLIMIT_CORE, (cs, ch))
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        if ch == INF:
            t("core hard -1", resource.setrlimit, resource.RLIMIT_CORE, (cs, -1))
        print("warnings", [(x.category.__name__, str(x.message)) for x in w])
    print("core unchanged", resource.getrlimit(resource.RLIMIT_CORE) == (cs, ch))
    print("nofile unchanged", resource.getrlimit(NOFILE) == (soft, hard))

    t("prlimit 0", lambda: resource.prlimit(0, NOFILE) == (soft, hard))
    t("prlimit own pid", lambda: resource.prlimit(os.getpid(), NOFILE) == (soft, hard))
    t("prlimit set same", lambda: resource.prlimit(0, NOFILE, (soft, hard)) == (soft, hard))
    t("prlimit None", lambda: resource.prlimit(0, NOFILE, None) == (soft, hard))
    t("prlimit bad resource", resource.prlimit, 0, 99)
    t("prlimit missing pid", resource.prlimit, 2**22 + 12345, NOFILE)
    t("prlimit pid str", resource.prlimit, "x", 1)
    t("prlimit pid 2**40", resource.prlimit, 2**40, 1)
    t("prlimit 1-tuple", resource.prlimit, 0, NOFILE, (1,))
    t("prlimit int", resource.prlimit, 0, NOFILE, 5)
    child = subprocess.Popen(["sleep", "30"])
    try:
        old = t("prlimit child lower", lambda: resource.prlimit(child.pid, NOFILE, (64, 128)) == resource.prlimit(child.pid, NOFILE) or "old value returned")
        t("prlimit child read", resource.prlimit, child.pid, NOFILE)
        if os.getuid() != 0:
            t("prlimit child raise hard", resource.prlimit, child.pid, NOFILE, (64, 256))
    finally:
        child.kill()
        print("child", child.wait(timeout=30))
    print("nofile unchanged", resource.getrlimit(NOFILE) == (soft, hard))

    ru = resource.getrusage(resource.RUSAGE_SELF)
    print(type(ru).__module__, type(ru).__qualname__, len(ru), [type(x).__name__ for x in ru], ru.ru_maxrss > 0, ru.ru_utime >= 0, repr(ru)[:32])
    print(type(ru) is resource.struct_rusage, ru[0] == ru.ru_utime, ru.n_fields, ru.n_sequence_fields)
    t("rusage children", lambda: type(resource.getrusage(resource.RUSAGE_CHILDREN)).__name__)
    t("rusage thread", lambda: type(resource.getrusage(resource.RUSAGE_THREAD)).__name__)
    t("rusage 5", resource.getrusage, 5)
    t("rusage -2", resource.getrusage, -2)
    t("rusage str", resource.getrusage, "x")
    t("rusage 2**40", resource.getrusage, 2**40)
    t("rusage keyword", lambda: resource.getrusage(who=0))
    t("pagesize", lambda: resource.getpagesize() == os.sysconf("SC_PAGESIZE"))
    t("pagesize arg", resource.getpagesize, 1)
    print("audit", sorted(set(events)), len([e for e in events if e[0] == "resource.setrlimit"]), len([e for e in events if e[0] == "resource.prlimit"]))
