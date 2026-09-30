async def agen():
    yield 1
    yield 2

async def collect():
    out=[]
    async for x in agen():
        out.append(x)
    return out

c=collect()
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
