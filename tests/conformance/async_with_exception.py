events=[]
class CM:
    async def __aenter__(self):
        events.append('enter')
        return self
    async def __aexit__(self, typ, value, tb):
        events.append('exit')
        return True

async def f():
    async with CM():
        raise ValueError('hidden')
    return 8

c=f()
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
print(events)
