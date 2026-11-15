import ast

# compile() and ast.parse translate "\r\n" and a lone "\r" to "\n" before tokenizing, string literals included
src = 's = """a\r\nb\rc"""\r\nt = \'x\'\r\nu = (1,\r\n     2)\r\n'
ns = {}
exec(compile(src, "<crlf>", "exec"), ns)
print(repr(ns["s"]), ns["t"], ns["u"])
tree = ast.parse(src)
print(repr(tree.body[0].value.value), [(n.lineno, n.col_offset, n.end_lineno, n.end_col_offset) for n in tree.body])
