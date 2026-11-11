# pyjvm: requires-python >= 3.14
import ast

a1 = ast.parse("f(x, y=1)"); a2 = ast.parse("f(x, y=1)"); a3 = ast.parse("f(x, y=2)")
print(ast.compare(a1, a2), ast.compare(a1, a3), ast.compare(ast.parse("a"), ast.parse(" a"), compare_attributes=True), ast.compare(ast.parse("a"), ast.parse(" a")))
