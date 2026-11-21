import re
import sys

base = r"C:\Users\richa\workspace_local\pyjvm-work\n0\measure-%s.txt"


def load(v):
    text = open(base % v, encoding="utf-8").read()
    text = re.sub(r"posix_measure_\w+", "posix_measure_X", text)
    text = re.sub(r"(fd|fileno|\()\d+", r"\1N", text) if False else text
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
