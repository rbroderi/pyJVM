def f(x):
    try:
        if x:
            return 10
        return 20
    finally:
        print('finally-f', x)

print(f(1), f(0))

for i in range(4):
    try:
        if i == 1:
            continue
        if i == 3:
            break
        print('body', i)
    finally:
        print('cleanup', i)
else:
    print('loop-else')
print('done')

def nested():
    try:
        try:
            return 7
        finally:
            print('inner')
    finally:
        print('outer')

print(nested())
