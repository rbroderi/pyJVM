events=[]

async def agen():
    try:
        yield 1
        yield 2
    finally:
        events.append("closed")

async def run():
    g=agen()
    value=await anext(g)
    await g.aclose()
    return value

c=run()
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
print(events)
