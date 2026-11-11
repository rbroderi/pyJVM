x = 10
def f(a):
    return a + x

print(eval("x + 1"), eval("f(5)"), eval("[x * i for i in range(3)]"))
exec("y = x * 2")
print(y)
exec("x = 99\nz = 1")
print(x, f(1))

def local_eval(p, q):
    r = p * q
    return eval("p + q + r")
print(local_eval(2, 3))

def local_exec():
    v = 1
    exec("v = 2")         # writes go to a snapshot of the locals, never back to the function
    return v
print(local_exec())

exec("del y")
try:
    print(y)
except NameError:
    print("y deleted")

class K:
    attr = 5
print(eval("K.attr * x"))
