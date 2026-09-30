VALUE = 40

def add(x):
    return VALUE + x

class Box:
    def __init__(self, x):
        self.x = x
    def get(self):
        return self.x
