events=[]

def pause_iter():
    try:
        yield "pause"
    finally:
        events.append("awaitable-finally")

class Pause:
    def __await__(self):
        return pause_iter()

async def work():
    try:
        await Pause()
    finally:
        events.append("coroutine-finally")

c=work()
print(c.send(None))
c.close()
print(events)
