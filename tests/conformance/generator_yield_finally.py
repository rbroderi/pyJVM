def normal():
    try:
        yield "body"
    finally:
        yield "cleanup"

n = normal()
print(next(n))
print(next(n))
try:
    next(n)
except StopIteration:
    print("done")

def exceptional():
    try:
        yield "start"
        raise ValueError("boom")
    finally:
        yield "during-finally"

x = exceptional()
print(next(x))
print(next(x))
try:
    next(x)
except ValueError as e:
    print(type(e), e.args)

def close_case():
    try:
        yield "ready"
    finally:
        yield "ignored-close"

c = close_case()
print(next(c))
try:
    c.close()
except RuntimeError as e:
    print(type(e))
