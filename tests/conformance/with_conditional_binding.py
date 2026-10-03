class Manager:
    def __init__(self, suppress): self.suppress = suppress
    def __enter__(self): return 7
    def __exit__(self, kind, value, trace): return self.suppress
class Broken:
    def __enter__(self): raise ValueError('enter')
    def __exit__(self, kind, value, trace): pass
def suppressed():
    with Manager(True):
        raise ValueError('skipped assignment')
        value = 3
    return value
try: suppressed()
except UnboundLocalError: print('suppressed binding')
def nested():
    try:
        with Manager(False) as first, Broken() as second: pass
    except ValueError: pass
    print(first)
    return second
try: nested()
except UnboundLocalError: print('later enter failed')
