class Context:
    def __enter__(self):
        return self
    def __exit__(self, kind, value, traceback):
        return False

def run():
    for i in range(3):
        with Context():
            def choose(value=i):
                return value + 10
            print(choose())
        text = "".join(str(x) for x in range(i))
        print(text)
run()
