class A:
    def method(self): return 'A'
class B(A):
    def nested(self):
        def choose(instance): return super().method()
        return choose(self)
    def lambda_method(self):
        return (lambda instance: super().method())(self)
    def generated(self):
        yield super().method()
        alias = super
        yield alias().method()
    def changed(self):
        self = A()
        return super().method()
    def changed_by_closure(self):
        def replace():
            nonlocal self
            self = A()
        replace()
        return super().method()
    def deleted(self):
        del self
        return super()
    def no_cell(self):
        return constructor()
constructor = super
value = B()
print(value.nested(), value.lambda_method(), list(value.generated()))
for operation in [value.changed, value.changed_by_closure, value.deleted, value.no_cell]:
    try:
        operation()
    except TypeError:
        print('TypeError')
    except RuntimeError as error:
        print(str(error))
def no_arguments(): return super()
def no_cell(value): return super()
for operation, args in [(no_arguments, ()), (no_cell, (value,))]:
    try:
        operation(*args)
    except RuntimeError as error:
        print(str(error))
class C:
    @staticmethod
    def missing(): return super()
try:
    C.missing()
except RuntimeError as error:
    print(str(error))
class D:
    def empty(self):
        nonlocal __class__
        del __class__
        return super()
try:
    D().empty()
except RuntimeError as error:
    print(str(error))

class Suite:
    try:
        super()
    except RuntimeError as error:
        print(str(error))
    alias = constructor
    try:
        alias()
    except RuntimeError as error:
        print(str(error))

class Expression(B):
    def generated_expression(self):
        return list(super().method() for _ in [1])
    def comprehension(self):
        return [super().method() for _ in [1]]
try:
    Expression().generated_expression()
except TypeError:
    print('implicit generator argument')
print(Expression().comprehension())

class LambdaClass(A):
    method = lambda self: super().method()
print(LambdaClass().method())
