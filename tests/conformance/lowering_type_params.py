def identity[T](x: T) -> T:
    return x


class Box[T]:
    def __init__(self, item: T):
        self.item = item

    def get(self) -> T:
        return self.item


def bounded[T: int, *Ts, **P](x: T) -> T:
    return x


print(identity(3), Box("s").get(), bounded(4))
