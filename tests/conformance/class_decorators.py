events=[]

def make(name):
    events.append('eval-'+name)
    def apply(cls):
        events.append('apply-'+name)
        return cls
    return apply

@make('top')
@make('bottom')
class C:
    x=7

print(events)
print(C.x)

class D(metaclass=type):
    y=9
print(D.y)
