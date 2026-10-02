def item():
    return 3
original = item
item = None
print(item is None, original())
class item:
    def get(self):
        return 4
print(item().get())
def item():
    return 5
print(item())
del item
try:
    item
except NameError:
    print('mixed binding deleted')
