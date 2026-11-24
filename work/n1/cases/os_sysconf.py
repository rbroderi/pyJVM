import os

# os.sysconf / os.confstr and their name tables (POSIX only). Host-dependent values are printed as types or relations.
print(os.name, hasattr(os, "sysconf"), hasattr(os, "confstr"), hasattr(os, "sysconf_names"), hasattr(os, "confstr_names"))

if os.name == "posix":
    def show(label, fn):
        try:
            print(label, fn())
        except Exception as e:
            print(label, type(e).__name__, getattr(e, "errno", None), e.strerror if isinstance(e, OSError) else e)

    print(type(os.sysconf_names).__name__, len(os.sysconf_names), sorted(os.sysconf_names.items()))
    print(type(os.confstr_names).__name__, len(os.confstr_names), sorted(os.confstr_names.items()))
    print(os.sysconf("SC_PAGESIZE") == os.sysconf("SC_PAGE_SIZE") == os.sysconf(os.sysconf_names["SC_PAGESIZE"]), os.sysconf("SC_PAGESIZE") in (4096, 16384, 65536))
    print(os.sysconf("SC_CLK_TCK"), type(os.sysconf("SC_OPEN_MAX")).__name__, os.sysconf("SC_OPEN_MAX") >= 3, os.sysconf("SC_ARG_MAX") >= 4096)
    print(os.sysconf("SC_NPROCESSORS_ONLN") >= 1, os.sysconf("SC_NPROCESSORS_CONF") >= os.sysconf("SC_NPROCESSORS_ONLN"), os.sysconf("SC_PHYS_PAGES") > 0)
    print(os.sysconf("SC_GETPW_R_SIZE_MAX"), os.sysconf("SC_GETGR_R_SIZE_MAX"), os.sysconf("SC_NGROUPS_MAX"), os.sysconf("SC_LINE_MAX"), os.sysconf("SC_IOV_MAX"), os.sysconf("SC_SEM_NSEMS_MAX"))
    bad = []
    for n in sorted(os.sysconf_names):
        try:
            v = os.sysconf(n)
            if type(v) is not int:
                bad.append((n, type(v).__name__))
        except OSError as e:
            bad.append((n, e.errno))
    print(bad)
    for a in ("NOPE", 99999, -5, 1.5, None, 2**40, b"SC_PAGESIZE"):
        show(repr(a), lambda: os.sysconf(a))
    show("noarg", lambda: os.sysconf())
    show("kw", lambda: os.sysconf(name=30))
    v = os.confstr("CS_GNU_LIBC_VERSION")
    print(v.split()[0], len(v.split()), os.confstr(os.confstr_names["CS_GNU_LIBC_VERSION"]) == v, os.confstr("CS_GNU_LIBPTHREAD_VERSION").split()[0])
    print(os.confstr("CS_PATH"))
    kinds = []
    for n in sorted(os.confstr_names):
        try:
            r = os.confstr(n)
            kinds.append((n, None if r is None else "empty" if r == "" else "str"))
        except OSError as e:
            kinds.append((n, e.errno))
    print(kinds)
    for a in ("NOPE", 99999, -1, 1.5, 2**40):
        show(repr(a), lambda: os.confstr(a))
    import subprocess
    print(subprocess._USE_POSIX_SPAWN)
