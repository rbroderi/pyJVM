def make(value):
    class Local:
        def values(self):
            yield value
            yield value + 1
        async def answer(self):
            return value + 2
        async def async_values(self):
            yield value + 3
    return Local

A = make(7)
B = make(20)
print(list(A().values()), list(B().values()))
async def run():
    print(await A().answer(), await B().answer())
    async for value in A().async_values():
        print(value)
    async for value in B().async_values():
        print(value)
c = run()
try:
    c.send(None)
except StopIteration:
    print("async done")
