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

async def outer_finally():
    try:
        async with CM():
            return 1
    finally:
        events.append("outer-finally")

c=outer_finally()
print(c.send(None))
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
print(events)
