def choose(value):
    match value:
        case None: return 'none'
        case True: return 'true'
        case False: return 'false'
        case 1: return 'one'
        case -2 | 3.5: return 'number'
        case 'hello' as text: return text + '!'
        case captured: return ('capture', captured)
for value in [None, True, False, 1, 0, -2, 3.5, 'hello', 'other']:
    print(choose(value))
for value in [1,2,3]:
    match value:
        case (1 as captured) | (2 as captured): print('or', captured)
        case _: print('wildcard')
match 7:
    case module_capture: pass
print(module_capture)
def unbound(value):
    match value:
        case 1 as captured: pass
    return captured
try: unbound(2)
except UnboundLocalError: print('unbound capture')
