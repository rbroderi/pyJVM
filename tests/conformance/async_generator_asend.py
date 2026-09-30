async def agen():
    value = yield 1
    yield value

async def run():
    g=agen()
    a=await anext(g)
    b=await g.asend(7)
    return [a,b]

c=run()
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
