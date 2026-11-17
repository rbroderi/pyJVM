# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
"""_multibytecodec: the codec, incremental and stream types the CJK ``encodings`` modules build on.

The conversions run in Java (``_cjkcore``, CjkCodec.java); this file holds the object protocol only.
"""
import _cjkcore as _c

_MAXDECPENDING = 8


def _typename(o):
    return type(o).__name__


def _errors_arg(who, errors):
    if errors is None:
        return "strict"
    if not isinstance(errors, str):
        raise TypeError(who + "() argument 'errors' must be str or None, not " + _typename(errors))
    return errors


class MultibyteCodec:
    __module__ = "_multibytecodec"
    _cjk_name = None

    def __new__(cls, *args, **kwargs):
        raise TypeError("cannot create '_multibytecodec.MultibyteCodec' instances")

    def encode(self, input, errors=None):
        """Return an encoded string version of 'input'.

'errors' may be given to set a different error handling scheme.
Default is 'strict' meaning that encoding errors raise
a UnicodeEncodeError.  Other possible values are 'ignore', 'replace'
and 'xmlcharrefreplace' as well as any other name registered with
codecs.register_error that can handle UnicodeEncodeErrors."""
        errors = _errors_arg("encode", errors)
        if not isinstance(input, str):
            input = str(input)
        out, consumed, st = _c.encode(self._cjk_name, input, errors, True, 0)
        return (out, len(input))

    def decode(self, input, errors=None):
        """Decodes 'input'.

'errors' may be given to set a different error handling scheme.
Default is 'strict' meaning that encoding errors raise
a UnicodeDecodeError.  Other possible values are 'ignore' and
'replace' as well as any other name registered with
codecs.register_error that is able to handle UnicodeDecodeErrors."""
        errors = _errors_arg("decode", errors)
        if isinstance(input, str) or not _is_buffer(input):
            raise TypeError("a bytes-like object is required, not '" + _typename(input) + "'")
        data = bytes(input)
        text, consumed, st = _c.decode(self._cjk_name, data, errors, True, 0)
        return (text, len(data))


_MultibyteCodec = MultibyteCodec   # CPython's module does not export the class itself
del MultibyteCodec


def _is_buffer(o):
    try:
        memoryview(o)
    except TypeError:
        return False
    return True


def _make_codec(name):
    if not _c.has(name):
        raise LookupError("no such codec is supported.")
    c = object.__new__(_MultibyteCodec)
    c._cjk_name = name
    return c


_c.make_codec = _make_codec
_c.has_codec = _c.has


def __create_codec(arg):
    if isinstance(arg, _MultibyteCodec):
        return arg
    raise ValueError("argument type invalid")


def _codec_of(obj):
    codec = obj.codec
    if not isinstance(codec, _MultibyteCodec):
        raise TypeError("codec is unexpected type")
    return codec._cjk_name


class MultibyteIncrementalEncoder:
    __module__ = "_multibytecodec"
    _mb_pending = ""
    _mb_state = 0
    errors = "strict"

    def __init__(self, errors="strict"):
        self.errors = errors
        self._mb_pending = ""
        self._mb_state = 0
        _codec_of(self)

    def encode(self, input, final=False):
        if not isinstance(input, str):
            raise TypeError("encode() argument 1 must be str, not " + _typename(input))
        text = self._mb_pending + input
        out, consumed, st = _c.encode(_codec_of(self), text, self.errors, bool(final), self._mb_state)
        self._mb_pending = text[consumed:]
        self._mb_state = st
        return out

    def reset(self):
        self._mb_pending = ""
        self._mb_state = 0

    def getstate(self):
        pend = self._mb_pending.encode("utf-8", "surrogatepass")
        return int.from_bytes(bytes([len(pend)]) + pend + self._mb_state.to_bytes(8, "little"), "little")

    def setstate(self, state):
        if not isinstance(state, int):
            raise TypeError("setstate() argument must be int, not " + _typename(state))
        raw = state.to_bytes(max(9, (state.bit_length() + 7) // 8), "little")
        n = raw[0]
        if n > 4 * 2:
            raise UnicodeEncodeError(_codec_of(self), "", 0, 0, "pending buffer too large")
        self._mb_pending = raw[1:1 + n].decode("utf-8", "surrogatepass")
        self._mb_state = int.from_bytes(raw[1 + n:9 + n], "little")


class MultibyteIncrementalDecoder:
    __module__ = "_multibytecodec"
    _mb_pending = b""
    _mb_state = 0
    errors = "strict"

    def __init__(self, errors="strict"):
        self.errors = errors
        self._mb_pending = b""
        self._mb_state = 0
        _codec_of(self)

    def decode(self, input, final=False):
        if isinstance(input, str) or not _is_buffer(input):
            raise TypeError("a bytes-like object is required, not '" + _typename(input) + "'")
        data = self._mb_pending + bytes(input)
        text, consumed, st = _c.decode(_codec_of(self), data, self.errors, bool(final), self._mb_state)
        self._mb_pending = data[consumed:]
        self._mb_state = st
        return text

    def reset(self):
        self._mb_pending = b""
        self._mb_state = 0

    def getstate(self):
        return (self._mb_pending, self._mb_state)

    def setstate(self, state):
        if not isinstance(state, tuple):
            raise TypeError("setstate() argument must be tuple, not " + _typename(state))
        pending, st = state
        if not isinstance(pending, bytes):
            raise TypeError("argument 1 must be bytes, not " + _typename(pending))
        if len(pending) > _MAXDECPENDING:
            raise UnicodeDecodeError(_codec_of(self), pending, 0, len(pending), "pending buffer too large")
        self._mb_pending = pending
        self._mb_state = st & 0xFFFFFFFFFFFFFFFF


class MultibyteStreamReader:
    __module__ = "_multibytecodec"
    _mb_pending = b""
    _mb_state = 0
    errors = "strict"

    def __init__(self, stream, errors=None):
        self.stream = stream
        self.errors = "strict" if errors is None else errors
        self._mb_pending = b""
        self._mb_state = 0
        _codec_of(self)

    def _iread(self, method, sizehint):
        if sizehint == 0:
            return ""
        fn = getattr(self.stream, method)
        while True:
            cres = fn() if sizehint < 0 else fn(sizehint)
            if not isinstance(cres, bytes):
                raise TypeError("stream function returned a non-bytes object (" + _typename(cres) + ")")
            eof = len(cres) == 0
            data = self._mb_pending + cres
            text, consumed, st = _c.decode(_codec_of(self), data, self.errors, eof, self._mb_state)
            self._mb_pending = data[consumed:]
            self._mb_state = st
            if eof or sizehint < 0 or text:
                return text

    @staticmethod
    def _size(o):
        if o is None:
            return -1
        return o.__index__()

    def read(self, sizeobj=None):
        return self._iread("read", self._size(sizeobj))

    def readline(self, sizeobj=None):
        return self._iread("readline", self._size(sizeobj))

    def readlines(self, sizehintobj=None):
        return self._iread("read", self._size(sizehintobj)).splitlines(True)

    def reset(self):
        self._mb_pending = b""
        self._mb_state = 0


class MultibyteStreamWriter:
    __module__ = "_multibytecodec"
    _mb_pending = ""
    _mb_state = 0
    errors = "strict"

    def __init__(self, stream, errors=None):
        self.stream = stream
        self.errors = "strict" if errors is None else errors
        self._mb_pending = ""
        self._mb_state = 0
        _codec_of(self)

    def _write(self, text, final):
        text = self._mb_pending + text
        out, consumed, st = _c.encode(_codec_of(self), text, self.errors, final, self._mb_state)
        self._mb_pending = text[consumed:]
        self._mb_state = st
        if out:
            self.stream.write(out)

    def write(self, strobj):
        if not isinstance(strobj, str):
            raise TypeError("write() argument must be str, not " + _typename(strobj))
        self._write(strobj, False)

    def writelines(self, lines):
        for line in lines:
            self.write(line)

    def reset(self):
        self._write("", True)
