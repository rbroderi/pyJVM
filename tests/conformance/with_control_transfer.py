class CM:
    def __init__(self, name):
        self.name = name
    def __enter__(self):
        print("enter", self.name)
        return self
    def __exit__(self, typ, value, tb):
        print("exit", self.name, typ is None)
        return False

def ret():
    with CM("ret"):
        return 7

print("retvalue", ret())

for i in range(3):
    with CM("loop"):
        if i == 0:
            continue
        if i == 1:
            break
        print("body", i)
print("after")
