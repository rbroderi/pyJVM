# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
import _multibytecodec
import _cjkcore as _c

_NAMES = ("iso2022_jp", "iso2022_jp_1", "iso2022_jp_2", "iso2022_jp_2004", "iso2022_jp_3", "iso2022_jp_ext", "iso2022_kr")


def getcodec(name):
    if not isinstance(name, str):
        raise TypeError("encoding name must be a string.")
    if name not in _NAMES:
        raise LookupError("no such codec is supported.")
    return _c.make_codec(name)
