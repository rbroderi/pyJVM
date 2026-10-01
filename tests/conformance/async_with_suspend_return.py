def pause_iter(label):
    yield label
    return None

class Pause:
    def __init__(self, label):
        self.label=label
    def __await__(self):
        return pause_iter(self.label)

class CM:
    async def __aenter__(self):
        await Pause("enter")
        return 10
    async def __aexit__(self, typ, value, tb):
        await Pause("exit")
        return False

async def f():
    async with CM() as x:
        return x + 1

c=f()
print(c.send(None))
print(c.send(None))
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
