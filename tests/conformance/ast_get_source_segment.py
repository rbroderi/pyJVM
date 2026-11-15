import ast

src = (
    "x = 1\n"
    "def f(a, b):\n"
    "    return (a +\n"
    "        b)  # \u00e9\n"
    's = "h\u00e9llo" + "w\u00f6rld"\n'
    "class C:\n"
    "\tdef m(self):\n"
    "\t\tif True:\n"
    "\t\t\tpass\n"
    "t = '\u4e2d\u6587' ; u = [1,\r\n 2]\r\n"
    "z = 0"
)
tree = ast.parse(src)
for node in ast.walk(tree):
    if not hasattr(node, "lineno"):
        continue
    print(type(node).__name__, node.lineno, node.col_offset, node.end_lineno, node.end_col_offset,
          repr(ast.get_source_segment(src, node)), repr(ast.get_source_segment(src, node, padded=True)),
          repr(ast.get_source_segment(src, node, padded=False)))

# nodes without locations, partial locations, and non-nodes
print(ast.get_source_segment(src, ast.Name("q")))
print(ast.get_source_segment(src, ast.Module(body=[], type_ignores=[])))
n = ast.Name("q")
n.lineno = 1
n.col_offset = 0
print(ast.get_source_segment(src, n))
n.end_lineno = 1
n.end_col_offset = None
print(ast.get_source_segment(src, n))
n.end_col_offset = 5
print(repr(ast.get_source_segment(src, n)))
print(ast.get_source_segment(src, object()))

# keyword use and errors
fn = tree.body[1]
print(repr(ast.get_source_segment(source=src, node=fn, padded=True)))
for call in (lambda: ast.get_source_segment(src), lambda: ast.get_source_segment(src, fn, True),
             lambda: ast.get_source_segment(src, fn, bogus=1), lambda: ast.get_source_segment(5, fn)):
    try:
        print(call())
    except BaseException as e:
        print(type(e).__name__, e)

# a node whose location lies beyond the source
far = ast.Name("q")
far.lineno = far.end_lineno = 99
far.col_offset, far.end_col_offset = 0, 1
try:
    print(ast.get_source_segment(src, far))
except BaseException as e:
    print(type(e).__name__, e)

# non-ASCII start column: byte offsets, padded first line keeps tabs
mod = ast.parse("if 1:\n\t\u00e9 = (1,\n\t\t2)\n")
assign = mod.body[0].body[0]
print(assign.col_offset, assign.end_col_offset, repr(ast.get_source_segment("if 1:\n\t\u00e9 = (1,\n\t\t2)\n", assign, padded=True)))
print(repr(ast.get_source_segment("a = 1\n", ast.parse("a = 1\n").body[0])))
print(repr(ast.get_source_segment("", ast.parse("").body and ast.parse("").body[0] or ast.Pass(lineno=1, col_offset=0, end_lineno=1, end_col_offset=0))))
