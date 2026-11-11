# pyjvm: requires-python >= 3.14
# Annotations executed conditionally are only recorded when their statement ran.
def show(label, scope):
    print(label, list(scope.__annotations__.items()))

len_ = len

class Swallower:
    def __enter__(self): pass
    def __exit__(self, *args): return True

for cond in (True, False):
    class IfElse:
        if cond:
            in_if: "if"
        else:
            in_if: "else"
    show("ifelse", IfElse)

    class IfElif:
        if not len_:
            in_if: "if"
        elif cond:
            in_elif: "elif"
        else:
            in_else: "else"
    show("ifelif", IfElif)

    class WithSwallow:
        with Swallower():
            if cond:
                about_to_raise: int
                raise Exception
            in_with: "with"
    show("with", WithSwallow)

    class TryExc:
        try:
            if cond:
                raise Exception
            in_try: "try"
        except Exception:
            in_except: "except"
        finally:
            in_finally: "finally"
    show("try", TryExc)

    class WhileLoop:
        while cond:
            in_while: "while"
            break
        else:
            in_else: "else"
    show("while", WhileLoop)

    class ForLoop:
        for _ in ([1] if cond else []):
            in_for: "for"
        else:
            in_else: "else"
    show("for", ForLoop)

    class Matching:
        match cond:
            case True:
                x: "true"
            case False:
                x: "false"
    show("match", Matching)

    class NestOverride:
        if cond:
            x: "foo"
            if cond:
                x: "bar"
    show("override", NestOverride)

    class NestInner:
        if len_:
            outer_before: "outer_before"
            if cond:
                inner_if: "inner_if"
            else:
                inner_else: "inner_else"
            outer_after: "outer_after"
    show("inner", NestInner)

    class NonName:
        before: "before"
        if cond:
            a = "x"
            a[0]: int
        else:
            a = object()
            a.b: str
        after: "after"
    show("nonname", NonName)
