# pyjvm: requires-python >= 3.14
import ast

# syntax added in 3.14 is rejected below that feature_version
SNIPPETS = {
    "except_noparen": "try:\n    pass\nexcept A, B:\n    pass\n",
    "template": "t'{x}'\n",
    "plain": "try:\n    pass\nexcept (A, B):\n    pass\n",
}
for version in [(3, 14), (3, 13), (3, 12)]:
    row = []
    for name, source in SNIPPETS.items():
        try:
            ast.parse(source, feature_version=version)
            row.append(name + "=ok")
        except SyntaxError as e:
            row.append(name + "=" + e.msg)
    print(version, row)
print(type(compile("t'{x}'", "<s>", "exec", ast.PyCF_ONLY_AST, _feature_version=14)).__name__)
