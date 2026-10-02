async def ready():
    return 3
async def factory(value):
    value = value + await ready()
    class Local:
        def get(self):
            return value
    if value:
        def choose():
            return Local
    return choose()
async def run():
    A = await factory(7)
    B = await factory(10)
    print(A().get(), B().get(), A is B)
c = run()
try:
    c.send(None)
except StopIteration:
    print("done")
