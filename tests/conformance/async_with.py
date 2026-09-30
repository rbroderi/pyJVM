events=[]
class CM:
    async def __aenter__(self):
        events.append('enter')
        return 7
    async def __aexit__(self, typ, value, tb):
        events.append('exit:'+str(typ is None))
        return False

async def f():
    async with CM() as x:
        events.append('body')
        return x

c=f()
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
print(events)
