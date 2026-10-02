def make(value):
    class Local:
        "class documentation"
        ...
        def get(self):
            return value
    return Local
A = make(1)
B = make(2)
print(A.__doc__, B.__doc__, A().get(), B().get())
