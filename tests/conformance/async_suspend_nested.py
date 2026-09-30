def pause_iter():
    value = yield "tick"
    return value

class Pause:
    def __await__(self):
        return pause_iter()

async def child():
    return (await Pause()) + 1

async def parent():
    return (await child()) * 3

c = parent()
print(c.send(None))
try:
    c.send(4)
except StopIteration as e:
    print(e.value)
