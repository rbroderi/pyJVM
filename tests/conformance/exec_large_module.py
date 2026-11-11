# exec'd module bodies larger than a JVM method (64KB of bytecode) are split into helper methods
lines = ["total = 0"]
for i in range(6000):
    lines.append("v%d = %d * 2 + 1" % (i, i))
    lines.append("total = total + v%d" % i)
lines.append("def tail():")
lines.append("    return total, v5999")
lines.append("result = tail()")
ns = {}
exec("\n".join(lines), ns)
print(ns["total"], ns["v0"], ns["v5999"], ns["result"])

# control flow that spans the split points keeps working: each top-level statement is whole
body = ["acc = []"]
for i in range(1500):
    body.append("for i in range(2):\n    if i == %d %% 2:\n        acc.append(%d)" % (i, i))
body.append("n = len(acc)")
scope = {}
exec("\n".join(body), scope)
print(scope["n"], scope["acc"][:6], scope["acc"][-3:])
