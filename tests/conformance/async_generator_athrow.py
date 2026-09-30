async def agen():
    try:
        yield 1
    except ValueError:
        yield 2

async def run():
    g=agen()
    first=await anext(g)
    second=await g.athrow(ValueError("boom"))
    return [first,second]

c=run()
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
