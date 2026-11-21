"""Diff measure-314.txt against its neighbours: usage `python measure_diff.py [DIR]` (default: this script's directory)."""
import os
import re
import sys

base = os.path.join(sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__)), "measure-%s.txt")


def load(v):
    text = open(base % v, encoding="utf-8").read()
    text = re.sub(r"posix_measure_\w+", "posix_measure_X", text)   # outputs from before the <tmp> scrubbing
    return text.splitlines()


def keyed(lines):
    d = {}
    for ln in lines:
        k, _, v = ln.partition(": ")
        d[k] = v
    return d


def diff(a, b):
    ka, kb = keyed(load(a)), keyed(load(b))
    for k in sorted(set(ka) | set(kb)):
        if k == "python":
            continue
        if ka.get(k) != kb.get(k):
            print(f"[{k}]\n  {a}: {ka.get(k)}\n  {b}: {kb.get(k)}")


print("=== 314 vs 313"); diff("313", "314")
print("=== 314 vs 315"); diff("314", "315")
