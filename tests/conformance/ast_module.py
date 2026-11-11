import ast

tree = ast.parse("x = 1 + 2\ndef f(a, *b, c=3):\n    return [a, *b]\n")
print(ast.dump(tree))
print(ast.dump(tree.body[1], indent=2))
print(ast.dump(ast.parse("a.b(c)[0]", mode="eval"), include_attributes=True))
print(type(tree).__name__, isinstance(tree, ast.AST), isinstance(tree, ast.Module), isinstance(tree.body[0], ast.stmt))
print(ast.Name._fields, ast.Constant._fields, ast.Name._attributes, ast.BinOp.__mro__[1].__name__)

class Names(ast.NodeVisitor):
    def __init__(self):
        self.seen = []
    def visit_Name(self, node):
        self.seen.append((node.id, type(node.ctx).__name__, node.lineno, node.col_offset))
    def visit_FunctionDef(self, node):
        self.seen.append("def " + node.name)
        self.generic_visit(node)

v = Names()
v.visit(tree)
print(v.seen)

class Doubler(ast.NodeTransformer):
    def visit_Constant(self, node):
        if isinstance(node.value, int):
            return ast.copy_location(ast.Constant(value=node.value * 2), node)
        return node
    def visit_Return(self, node):
        return None

t2 = Doubler().visit(ast.parse("a = 5\nb = 'x'\ndef g():\n    return 7\n    y = 9\n"))
print(ast.dump(t2))

print([type(n).__name__ for n in ast.walk(ast.parse("f(1)"))])
print([(f, type(v).__name__) for f, v in ast.iter_fields(ast.Name(id="q", ctx=ast.Load()))])
print([type(n).__name__ for n in ast.iter_child_nodes(ast.parse("a + b").body[0].value)])

n = ast.Name("zed", ast.Store())
print(n.id, type(n.ctx).__name__, ast.dump(ast.Assign(targets=[n], value=ast.Constant(1))))
print(ast.dump(ast.fix_missing_locations(ast.Expression(ast.BinOp(ast.Constant(1), ast.Add(), ast.Constant(2)))), include_attributes=True))
call = ast.Call(func=ast.Name(id="f", ctx=ast.Load()), args=[], keywords=[])
print(ast.dump(call), call.args, call.keywords)

for src, mode in [("1 +", "eval"), ("def f(:\n  pass", "exec"), ("x = (", "exec")]:
    try:
        ast.parse(src, mode=mode)
    except SyntaxError as e:
        print("SyntaxError")
print(ast.literal_eval("[1, 2.5, 'a', (3, None), {'k': -1}, {1, 2}, 1+2j, b'x']") == [1, 2.5, 'a', (3, None), {'k': -1}, {1, 2}, 1+2j, b'x'])
try:
    ast.literal_eval("f(1)")
except ValueError:
    print("ValueError")
print(ast.get_docstring(ast.parse('"""doc"""\nx = 1')), ast.get_docstring(ast.parse("x = 1")))

def describe(node):
    match node:
        case ast.Constant(value=int(v)):
            return "int %d" % v
        case ast.Constant(value=str(v)):
            return "str " + v
        case ast.Name(id=name):
            return "name " + name
        case ast.BinOp(left=l, op=ast.Add(), right=r):
            return "add(" + describe(l) + ", " + describe(r) + ")"
        case _:
            return type(node).__name__

print(describe(ast.parse("1 + x", mode="eval").body), describe(ast.parse("'s'", mode="eval").body), describe(ast.parse("-1", mode="eval").body))

print(ast.dump(compile("x = 1", "<s>", "exec", ast.PyCF_ONLY_AST)), ast.dump(compile("1 + 2", "<s>", "eval", flags=ast.PyCF_ONLY_AST)))
