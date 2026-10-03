match 7:
    case captured:
        def read(): return captured
        class Holder:
            value = captured
