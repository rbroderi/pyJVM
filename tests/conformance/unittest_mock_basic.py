import os
import unittest
from unittest import mock


class Thing:
    def method(self, x):
        return x * 2

    attr = "real"


m = mock.Mock(return_value=3)
print(m(1, a=2), m.call_count, m.call_args)
m.assert_called_once_with(1, a=2)
try:
    m.assert_called_once_with(9)
except AssertionError:
    print("assertion raised")

mm = mock.MagicMock()
mm.foo.bar.return_value = 5
print(mm.foo.bar(), len(mm), bool(mm), int(mm))
print(mm.foo.bar.mock_calls)

side = mock.Mock(side_effect=[1, 2, ValueError("boom")])
print(side(), side())
try:
    side()
except ValueError as exc:
    print("side effect", exc)

with mock.patch("os.getcwd", return_value="/fake"):
    print(os.getcwd())
print(os.getcwd() != "/fake")

with mock.patch.object(Thing, "attr", "patched"):
    print(Thing.attr)
print(Thing.attr)


@mock.patch.object(Thing, "method")
def decorated(patched):
    patched.return_value = 99
    print(Thing().method(1), patched.call_args_list)


decorated()

spec = mock.Mock(spec=Thing)
spec.method.return_value = 7
print(spec.method(2))
try:
    spec.nonexistent
except AttributeError:
    print("autospec rejects unknown attribute")

with mock.patch.dict(os.environ, {"PYJVM_MOCK_VAR": "yes"}):
    print(os.environ["PYJVM_MOCK_VAR"])
print("PYJVM_MOCK_VAR" in os.environ)

pm = mock.PropertyMock(return_value=4)
print(pm(), mock.sentinel.token is mock.sentinel.token, mock.ANY == 5, mock.call(1, b=2))
