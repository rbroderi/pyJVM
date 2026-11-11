# pyjvm: requires-python < 3.14
hint = int
v: hint = 3
w: hint
class K:
    n: hint = 1
print(__annotations__, K.__annotations__, v)
