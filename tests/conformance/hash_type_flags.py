"""Static-type behaviour of the builtin hash types: not subclassable, immutable, no instance __dict__, unpicklable."""
import copy
import pickle

import _blake2
import _md5
import _sha1
import _sha2
import _sha3

objs = [
    _md5.md5(), _sha1.sha1(), _sha2.sha224(), _sha2.sha256(), _sha2.sha384(), _sha2.sha512(),
    _blake2.blake2b(), _blake2.blake2s(), _sha3.sha3_224(), _sha3.sha3_256(), _sha3.sha3_384(), _sha3.sha3_512(),
    _sha3.shake_128(), _sha3.shake_256(),
]


def t(label, fn):
    try:
        fn()
        print("  " + label, "OK")
    except BaseException as e:
        print("  " + label, type(e).__name__, e)


def subclass(c):
    class S(c):
        pass


def set_class_attr(c):
    c.zz = 1


def type_setattr(c):
    type.__setattr__(c, "zz", 1)


def del_class_attr(c):
    del c.update


def set_inst_attr(o):
    o.zz = 1


for o in objs:
    c = type(o)
    print(c.__module__, c.__name__)
    t("subclass", lambda: subclass(c))
    t("class setattr", lambda: set_class_attr(c))
    t("type.__setattr__", lambda: type_setattr(c))
    t("class delattr", lambda: del_class_attr(c))
    t("instance setattr", lambda: set_inst_attr(o))
    t("instance name", lambda: setattr(o, "name", "x"))
    t("instance digest_size", lambda: setattr(o, "digest_size", 1))
    t("pickle", lambda: pickle.dumps(o))
    t("pickle protocol 2", lambda: pickle.dumps(o, 2))
    t("copy.copy", lambda: copy.copy(o))
    t("copy.deepcopy", lambda: copy.deepcopy(o))
    t("__reduce_ex__(2)", lambda: o.__reduce_ex__(2))
    t("__reduce_ex__(4)", lambda: o.__reduce_ex__(4))
    if c.__module__ in ("_blake2", "_sha3"):
        t("__reduce_ex__(0)", lambda: o.__reduce_ex__(0))
        t("__reduce_ex__(1)", lambda: o.__reduce_ex__(1))
        t("__reduce__", lambda: o.__reduce__())
    print("  hasattr __dict__", hasattr(o, "__dict__"))
    print("  copy() works", type(o.copy()) is c)
