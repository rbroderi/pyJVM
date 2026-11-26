import warnings


class W(UserWarning):
    def __init__(self, a, b):
        super().__init__(a, b)
        self.pair = (a, b)


class Plain(UserWarning):
    pass


def show(label, w):
    print(label, [(x.category.__name__, type(x.message).__name__, x.message.args) for x in w])


# a Warning instance with a two-argument constructor is delivered as the very object that was warned
obj = W(1, 2)
with warnings.catch_warnings(record=True) as w:
    warnings.simplefilter("always")
    warnings.warn(obj)
print("recorded", len(w), w[0].message is obj, w[0].category.__name__, w[0].message.pair)

# the same instance under the "error" action is raised itself
with warnings.catch_warnings():
    warnings.simplefilter("error")
    try:
        warnings.warn(obj)
    except W as e:
        print("error is obj", e is obj, e.args)

# a plain message is wrapped in the category, with the original message object as its argument
with warnings.catch_warnings(record=True) as w:
    warnings.simplefilter("always")
    warnings.warn("text", Plain)
    warnings.warn(42, Plain)
show("wrapped", w)
print(w[1].message.args[0] == 42)

# warn_explicit takes the same path
with warnings.catch_warnings(record=True) as w:
    warnings.simplefilter("always")
    warnings.warn_explicit(obj, W, "f.py", 3)
print("explicit", w[0].message is obj, w[0].filename, w[0].lineno)
