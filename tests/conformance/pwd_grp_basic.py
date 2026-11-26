# pwd and grp: lookups through libc (NSS), struct sequences, error texts.
# Prints only facts that are the same for CPython and pyJVM on one host. Read-only.
import os

print(os.name)
if os.name == "posix":
    import grp
    import pwd

    def t(label, f, *a, **k):
        try:
            r = f(*a, **k)
            print(label, "->", repr(r))
            return r
        except Exception as e:
            print(label, "!!", type(e).__name__, e.args)

    print(sorted(n for n in dir(pwd) if not n.startswith("_")), sorted(n for n in dir(grp) if not n.startswith("_")))
    me = pwd.getpwuid(os.getuid())
    print(type(me).__module__, type(me).__qualname__, type(me) is pwd.struct_passwd, len(me), [type(x).__name__ for x in me])
    print(me == pwd.getpwnam(me.pw_name), me[0] == me.pw_name, me[2] == me.pw_uid == os.getuid(), me.pw_gid == me[3], me[5] == me.pw_dir, me[-1] == me.pw_shell)
    print(repr(me).startswith("pwd.struct_passwd(pw_name='"), ", pw_uid=%d, pw_gid=%d, " % (me.pw_uid, me.pw_gid) in repr(me))
    print(me.n_fields, me.n_sequence_fields, me.n_unnamed_fields, pwd.struct_passwd.__match_args__, isinstance(me, tuple), tuple(me) == me, hash(me) == hash(tuple(me)), type(me[0:2]).__name__)
    root = pwd.getpwuid(0)
    print(root.pw_name, root.pw_uid, root.pw_gid, root.pw_dir, pwd.getpwnam("root") == root, pwd.getpwuid(False) == root)
    t("setattr", setattr, root, "pw_uid", 5)
    t("uid missing", pwd.getpwuid, 4000000123)
    t("uid -1", pwd.getpwuid, -1)
    t("uid -2", pwd.getpwuid, -2)
    t("uid 2**32-1", pwd.getpwuid, 2**32 - 1)
    t("uid 2**32", pwd.getpwuid, 2**32)
    t("uid 2**64", pwd.getpwuid, 2**64)
    t("uid -2**70", pwd.getpwuid, -2**70)
    t("uid str", pwd.getpwuid, "0")
    t("uid float", pwd.getpwuid, 0.0)
    t("uid None", pwd.getpwuid, None)
    t("uid keyword", lambda: pwd.getpwuid(uidobj=0))
    t("uid no args", pwd.getpwuid)
    t("name missing", pwd.getpwnam, "pyjvm-no-such-user")
    t("name empty", pwd.getpwnam, "")
    t("name NUL", pwd.getpwnam, "a\0b")
    t("name bytes", pwd.getpwnam, b"root")
    t("name int", pwd.getpwnam, 0)
    t("name non-ASCII", pwd.getpwnam, "\xe9€")
    t("name surrogate", pwd.getpwnam, "\udcff")
    t("name keyword", lambda: pwd.getpwnam(name="root"))
    al = pwd.getpwall()
    print(type(al).__name__, len(al) > 0, all(type(x) is pwd.struct_passwd for x in al), me in al, root in al,
          len(al) == len(pwd.getpwall()), [x.pw_name for x in al] == [x.pw_name for x in pwd.getpwall()])
    print(all(pwd.getpwnam(x.pw_name).pw_name == x.pw_name for x in al[:50]))
    t("getpwall arg", pwd.getpwall, 1)

    g = grp.getgrgid(os.getgid())
    print(type(g).__module__, type(g).__qualname__, type(g) is grp.struct_group, len(g), [type(x).__name__ for x in g], all(type(m) is str for m in g.gr_mem))
    print(g == grp.getgrnam(g.gr_name), g[0] == g.gr_name, g[2] == g.gr_gid == os.getgid(), g[3] == g.gr_mem, repr(g).startswith("grp.struct_group(gr_name='"))
    print(g.n_fields, g.n_sequence_fields, g.n_unnamed_fields, grp.struct_group.__match_args__)
    rg = grp.getgrgid(0)
    print(rg.gr_name, rg.gr_gid, grp.getgrnam("root") == rg, grp.getgrgid(id=0) == rg, grp.getgrnam(name="root") == rg)
    t("gid missing", grp.getgrgid, 4000000123)
    t("gid -1", grp.getgrgid, -1)
    t("gid -2", grp.getgrgid, -2)
    t("gid 2**32-1", grp.getgrgid, 2**32 - 1)
    t("gid 2**32", grp.getgrgid, 2**32)
    t("gid 2**64", grp.getgrgid, 2**64)
    t("gid -2**70", grp.getgrgid, -2**70)
    t("gid str", grp.getgrgid, "0")
    t("gid float", grp.getgrgid, 0.0)
    t("gid no args", grp.getgrgid)
    t("gid two args", grp.getgrgid, 0, 0)
    t("gid bad keyword", lambda: grp.getgrgid(gid=0))
    t("name missing", grp.getgrnam, "pyjvm-no-such-group")
    t("name NUL", grp.getgrnam, "a\0b")
    t("name bytes", grp.getgrnam, b"root")
    t("name int", grp.getgrnam, 0)
    t("name surrogate", grp.getgrnam, "\udcff")
    ga = grp.getgrall()
    print(type(ga).__name__, len(ga) > 0, all(type(x) is grp.struct_group for x in ga), all(type(x.gr_mem) is list for x in ga), g in ga, rg in ga,
          [x.gr_name for x in ga] == [x.gr_name for x in grp.getgrall()])
    print(sorted(x.gr_name for x in ga if x.gr_mem)[:3] == sorted(x.gr_name for x in grp.getgrall() if x.gr_mem)[:3], all(grp.getgrgid(x.gr_gid).gr_gid == x.gr_gid for x in ga[:50]))
    t("getgrall arg", grp.getgrall, 1)
    # stdlib users
    import getpass
    import posixpath
    print(getpass.getuser() == me.pw_name or "USER/LOGNAME differ", posixpath.expanduser("~root") == root.pw_dir, posixpath.expanduser("~pyjvm-no-such-user"))
