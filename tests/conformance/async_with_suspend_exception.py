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
        return self
    async def __aexit__(self, typ, value, tb):
        await Pause("suppress")
        return True

async def f():
    async with CM():
        raise ValueError("hidden")
    return 7

c=f()
print(c.send(None))
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
