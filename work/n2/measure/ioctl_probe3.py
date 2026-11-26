import fcntl, os, sys
m, s = os.openpty()
for name, req in (("TCGETS", 0x5401), ("TIOCGWINSZ", 0x5413)):
    for flag in ("mut", "ro"):
        res = []
        for n in range(0, 48):
            try:
                if flag == "mut":
                    fcntl.ioctl(s, req, bytearray(b"\x00" * n))
                else:
                    fcntl.ioctl(s, req, b"\x00" * n)
                res.append("ok")
            except SystemError:
                res.append("SE")
            except BaseException as e:
                res.append(type(e).__name__)
        print(name, flag, " ".join("%d:%s" % (n, r) for n, r in enumerate(res) if n < 3 or r != res[n - 1]))
# sentinel candidates: pre-fill with various bytes beyond len cannot be influenced; check non-zero data in the kernel write
os.close(m); os.close(s)
