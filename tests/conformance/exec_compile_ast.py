import ast

# compile() accepts AST objects, flags, dont_inherit and optimize
tree = ast.parse("a = 1\nb = a + 41\nb")
code = compile(tree, "<ast>", "exec")
ns = {}
exec(code, ns)
print(ns["a"], ns["b"], type(code).__name__)

expr = ast.parse("x * 2 + 1", mode="eval")
print(eval(compile(expr, "<e>", "eval"), {"x": 20}))

# AST built by hand needs locations; fix_missing_locations supplies them
hand = ast.Module(body=[ast.Assign(targets=[ast.Name(id="made", ctx=ast.Store())],
                                   value=ast.BinOp(left=ast.Constant(value=6), op=ast.Mult(), right=ast.Constant(value=7)))],
                  type_ignores=[])
ast.fix_missing_locations(hand)
h = {}
exec(compile(hand, "<hand>", "exec"), h)
print(h["made"])


# transformed trees run
class Plus(ast.NodeTransformer):
    def visit_Constant(self, node):
        if isinstance(node.value, int):
            return ast.copy_location(ast.Constant(value=node.value + 100), node)
        return node


t2 = Plus().visit(ast.parse("r = 1 + 2"))
t2 = ast.fix_missing_locations(t2)
r = {}
exec(compile(t2, "<t2>", "exec"), r)
print(r["r"])

# flags: PyCF_ONLY_AST returns the tree, not code
print(ast.PyCF_ONLY_AST, ast.PyCF_TYPE_COMMENTS, ast.PyCF_ALLOW_TOP_LEVEL_AWAIT)
only = compile("z = 5", "<s>", "exec", ast.PyCF_ONLY_AST)
print(type(only).__name__, ast.dump(only))
print(type(compile("1 + 2", "<s>", "eval", flags=ast.PyCF_ONLY_AST)).__name__)
print(ast.dump(compile(only, "<s>", "exec", ast.PyCF_ONLY_AST)) == ast.dump(only), ast.PyCF_OPTIMIZED_AST)
print(type(compile("x = 1", "<s>", "single", ast.PyCF_ONLY_AST)).__name__)

# dont_inherit / optimize accepted, bad values rejected as CPython does
print(type(compile("1", "<s>", "eval", 0, True)).__name__)
print(type(compile("1", "<s>", "eval", dont_inherit=True, optimize=2)).__name__)
for bad in (dict(optimize=7), dict(flags=0x10000000), dict(optimize=-5)):
    try:
        compile("1", "<s>", "eval", **bad)
    except ValueError as e:
        print("ValueError", e)
try:
    compile("1", "<s>", "bogus")
except ValueError as e:
    print("ValueError", e)

# wrong AST shape for the mode
for node, mode in ((ast.parse("x"), "eval"), (ast.parse("x", mode="eval"), "exec")):
    try:
        compile(node, "<s>", mode)
    except TypeError as e:
        print("TypeError", e)
try:
    compile(ast.Module(body=[ast.Expr(value=ast.Constant(value=1))], type_ignores=[]), "<bad>", "exec")
except TypeError as e:
    print("TypeError", e)
try:
    compile(ast.Name(id="n", ctx=ast.Load()), "<bad>", "eval")
except TypeError as e:
    print("TypeError", e)
try:
    compile(42, "<s>", "exec")
except TypeError as e:
    print("TypeError", e)

# optimize levels: docstrings survive 0 and 1, go at 2; __debug__ turns False from 1
fn_tree = ast.parse("def f():\n    'doc'\n    return __debug__\nd = f.__doc__\nv = f()")
for opt in (0, 1, 2):
    scope = {}
    exec(compile(fn_tree, "<f>", "exec", optimize=opt), scope)
    print(opt, scope["d"], scope["v"])

# top-level await needs the flag
try:
    compile("await x", "<s>", "exec")
except SyntaxError as e:
    print("SyntaxError")

# source -> AST -> code round trip equals direct compilation
src = "def g(n):\n    return [i * n for i in range(3)]\nout = g(4)\n"
a, b = {}, {}
exec(compile(src, "<s>", "exec"), a)
exec(compile(ast.parse(src), "<s>", "exec"), b)
print(a["out"], b["out"])

# a constant that cannot be a constant is rejected
try:
    compile(ast.Expression(body=ast.Constant(value=object())), "<s>", "eval")
except (TypeError, ValueError) as e:
    print(type(e).__name__)
