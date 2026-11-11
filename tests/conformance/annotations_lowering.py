from __future__ import annotations

x: int = 5
y: str
z: list[int] = [1]

class C:
    a: int = 1
    b: "forward"
    def m(self, p: int) -> str:
        local: int = 3
        self.q: float = 1.5
        return str(local)

print(x, sorted(__annotations__.items()))
print(sorted(C.__annotations__.items()), C.a, C().m(1), C().q if hasattr(C(), "q") else "no q")
