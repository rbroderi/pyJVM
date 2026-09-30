def pause_iter():
    try:
        yield "waiting"
    except ValueError:
        return 7

class Pause:
    def __await__(self):
        return pause_iter()

async def work():
    return await Pause()

c = work()
print(c.send(None))
try:
    c.throw(ValueError("boom"))
except StopIteration as e:
    print(e.value)
