def make(value):
    if value:
        class Local:
            def get(self):
                return value
    else:
        class Local:
            def get(self):
                return "zero"
    return Local

A = make(2)
B = make(0)
print(A().get(), B().get())
for i in range(3):
    with_dummy = i
    def helper(value=i):
        return value
    print(helper())

def generate():
    for value in range(3):
        result = sum(x for x in range(value))
        if value == 1:
            continue
        yield result
    return
print(list(generate()))
