# N2 ticket 2: negative limit values for both tuple positions (plan 3.8 last paragraph, U4). CPython only, no JVM.
# SAFETY: only RLIMIT_CORE is written, with values that are restored in a finally; no hard limit is lowered
# (the hard position is only tried with values the kernel or the converter rejects or that equal the current hard limit).
import os
import warnings

import resource

assert os.getuid() != 0
R = resource.RLIMIT_CORE
cs, ch = resource.getrlimit(R)
INF = resource.RLIM_INFINITY
print("inf", INF, "hard is inf", ch == INF)
vals = [-1, -2, -3, -2**31, -2**63, -2**63 - 1, -2**64, -2**70]
try:
    for pos in (0, 1):
        for v in vals:
            lim = (v, ch) if pos == 0 else (cs, v)
            with warnings.catch_warnings(record=True) as w:
                warnings.simplefilter("always")
                try:
                    r = resource.setrlimit(R, lim)
                    out = "ok"
                except Exception as e:
                    out = type(e).__name__ + " " + repr(e.args)
                print("pos", pos, "value", v, "->", out, [(x.category.__name__, str(x.message)) for x in w], "now", resource.getrlimit(R) == (cs, ch))
            resource.setrlimit(R, (cs, ch))
finally:
    resource.setrlimit(R, (cs, ch))
print("restored", resource.getrlimit(R) == (cs, ch))
