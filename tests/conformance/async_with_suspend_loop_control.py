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
    def __init__(self, i):
        self.i=i
    async def __aenter__(self):
        events.append("enter"+str(self.i))
        return self
    async def __aexit__(self, typ, value, tb):
        await Pause("exit"+str(self.i))
        events.append("done"+str(self.i))
        return False

async def f():
    for i in range(3):
        async with CM(i):
            if i == 0:
                continue
            if i == 1:
                break
    return events

c=f()
print(c.send(None))
print(c.send(None))
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
