# pyjvm: requires-python >= 3.14
from __future__ import annotations

def f(x: int, *a: list[int], y: "quoted" = 1, **k: dict[str, "T"]) -> Optional[int]: pass

print(f.__annotations__)
print(f.__annotate__(1) == f.__annotations__)
print(f.__annotate__.__name__)

class C:
    a: int
    b: List[Undefined] = []
    def m(self, p: C) -> C: pass

print(C.__annotations__)
print(C.m.__annotations__)
print(C.__annotate__, "__annotations__" in C.__dict__, "__annotations_cache__" in C.__dict__)
