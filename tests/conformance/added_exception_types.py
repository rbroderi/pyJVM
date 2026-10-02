for cls in [ImportError, ModuleNotFoundError, MemoryError, BufferError, SyntaxError, BytesWarning]:
    try:
        raise cls("test")
    except Exception as error:
        print(isinstance(error, cls), str(error))
print(issubclass(ModuleNotFoundError, ImportError), issubclass(BytesWarning, Warning))
print(issubclass(UnicodeError, ValueError), issubclass(IndentationError, SyntaxError))
print(issubclass(int, BaseException), issubclass(KeyboardInterrupt, Exception))

def make():
    class Local:
        def cause(self):
            try:
                raise BufferError("inside")
            except BufferError as error:
                return str(error)
    return Local
print(make()().cause())
