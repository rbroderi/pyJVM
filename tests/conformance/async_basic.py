events=[]

async def child(x):
    events.append('child')
    return x + 1

async def parent(x):
    events.append('parent')
    y = await child(x)
    return y * 2

c = parent(5)
print(events)
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
print(events)
