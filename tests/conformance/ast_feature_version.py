import ast

SNIPPETS = {
    "walrus": "(a := 1)\n",
    "posonly": "def f(a, /, b): pass\n",
    "match": "match x:\n    case 1:\n        pass\n",
    "paren_with": "with (open(a) as f, open(b) as g):\n    pass\n",
    "except_star": "try:\n    pass\nexcept* ValueError:\n    pass\n",
    "type_params": "def f[T](x: T) -> T:\n    return x\n",
    "type_stmt": "type Alias = int\n",
    "class_params": "class C[T]:\n    pass\n",
    "param_default": "def f[T = int](x): pass\n",
    "plain": "x = [i for i in range(3)]\nprint(x)\n",
    "fstring": "f'{x!r:>{w}}'\n",
}

for version in [(3, 13), (3, 12), (3, 11), (3, 10), (3, 9), (3, 7)]:
    row = []
    for name, source in SNIPPETS.items():
        try:
            ast.parse(source, feature_version=version)
            row.append(name + "=ok")
        except SyntaxError as e:
            row.append(name + "=" + e.msg)
    print(version, row)

# an int is the minor version; None means the default
print(type(ast.parse("x = 1", feature_version=13)).__name__, type(ast.parse("x = 1", feature_version=None)).__name__)
try:
    ast.parse("x = 1", feature_version=(4, 0))
except ValueError as e:
    print("ValueError", e)
try:
    ast.parse("x = 1", feature_version="3.13")
except TypeError as e:
    print("TypeError")

# compile() takes the same hidden keyword that ast.parse forwards
try:
    compile("match x:\n    case 1: pass\n", "<s>", "exec", ast.PyCF_ONLY_AST, _feature_version=9)
except SyntaxError as e:
    print("SyntaxError", e.msg, e.lineno)
print(type(compile("match x:\n    case 1: pass\n", "<s>", "exec", ast.PyCF_ONLY_AST, _feature_version=10)).__name__)
print(type(compile("(a := 1)\n", "<s>", "exec", ast.PyCF_ONLY_AST, _feature_version=-1)).__name__)
try:
    ast.parse("def f(a, /): pass", feature_version=(3, 7))
except SyntaxError as e:
    print("SyntaxError", e.msg, e.lineno)

# the gate cites the line that holds the construct
try:
    ast.parse("a = 1\nb = 2\nmatch a:\n    case 1:\n        pass\n", feature_version=(3, 9))
except SyntaxError as e:
    print("SyntaxError", e.lineno)
