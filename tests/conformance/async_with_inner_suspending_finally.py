events=[]

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
        events.append("enter")
        return self
    async def __aexit__(self, typ, value, tb):
        await Pause("exit-token")
        events.append("exit")
        return False

async def f():
    async with CM():
        try:
            return 3
        finally:
            await Pause("inner-token")
            events.append("inner-finally")

c=f()
print(c.send(None))
print(c.send(None))
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
print(events)
