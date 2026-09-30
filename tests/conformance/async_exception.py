async def child():
    raise ValueError('boom')

async def parent():
    try:
        await child()
    except ValueError as e:
        print(e.args)
    return 7

c=parent()
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
