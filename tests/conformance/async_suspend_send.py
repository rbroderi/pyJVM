def pause_iter():
    value = yield "pause"
    return value + 1

class Pause:
    def __await__(self):
        return pause_iter()

async def work():
    x = await Pause()
    return x * 2

c = work()
print(c.send(None))
try:
    c.send(10)
except StopIteration as e:
    print(e.value)
