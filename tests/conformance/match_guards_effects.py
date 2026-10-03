events = []
def subject():
    events.append('subject')
    return 2
class Constants:
    one = 1
    two = 2
match subject():
    case Constants.one: events.append('wrong')
    case captured if events.append(('guard', captured)) or False: events.append('wrong')
    case Constants.two as result: events.append(('body', result))
print(events, captured, result)
class Comparable:
    def __eq__(self, other):
        print('compare', other)
        return other == 2
match Comparable():
    case 1 | 2: print('equal')
    case _: print('wrong')
class Truth:
    def __bool__(self):
        print('truth')
        return True
match 3:
    case value if Truth(): print(value)
class Broken:
    def __eq__(self, other): raise ValueError('comparison')
try:
    match Broken():
        case 1: pass
except ValueError as error: print(str(error))
try:
    match 1:
        case x if 1 / 0: pass
except ZeroDivisionError: print('guard raised')
