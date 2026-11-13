# pyjvm: requires-python >= 3.15
# test.support is CPython's own regression-test package; it is not part of an installed library, so the case
# looks for a checkout of CPython's Lib directory and prints one identical line on both lanes when there is none.
import os
import sys

corpus = os.environ.get("PYJVM_CPYTHON_LIB", "/tmp/claude-0/cpython/Lib")
try:
    from test import support
except ImportError:
    if os.path.isdir(os.path.join(corpus, "test")):
        sys.path.append(corpus)
    try:
        from test import support
    except ImportError:
        support = None
if support is None:
    print("test.support unavailable")
    raise SystemExit(0)

import io
import unittest
from test.support import (captured_stdout, captured_stderr, swap_attr, swap_item, findfile, check_impl_detail,
                          cpython_only, import_helper, os_helper, warnings_helper)
from test.support.os_helper import TESTFN


class Box:
    value = 1


class Helpers(unittest.TestCase):
    def test_swap_attr(self):
        with swap_attr(Box, "value", 2) as old:
            self.assertEqual(old, 1)
            self.assertEqual(Box.value, 2)
        self.assertEqual(Box.value, 1)

    def test_swap_item(self):
        d = {"a": 1}
        with swap_item(d, "a", 5) as old:
            self.assertEqual(old, 1)
            self.assertEqual(d["a"], 5)
        self.assertEqual(d, {"a": 1})

    def test_captured_stdout(self):
        with captured_stdout() as out:
            print("hello")
        self.assertEqual(out.getvalue(), "hello\n")
        with captured_stderr() as err:
            print("oops", file=sys.stderr)
        self.assertEqual(err.getvalue(), "oops\n")

    def test_import_helper(self):
        self.assertIsNotNone(import_helper.import_module("os"))
        with self.assertRaises(unittest.SkipTest):
            import_helper.import_module("no_such_module_xyz")

    def test_os_helper(self):
        self.assertIsInstance(TESTFN, str)
        with os_helper.temp_dir() as d:
            self.assertTrue(os.path.isdir(d))
        self.assertFalse(os.path.isdir(d))
        with os_helper.EnvironmentVarGuard() as env:
            env["PYJVM_SUPPORT_VAR"] = "1"
            self.assertEqual(os.environ["PYJVM_SUPPORT_VAR"], "1")
        self.assertNotIn("PYJVM_SUPPORT_VAR", os.environ)

    def test_warnings_helper(self):
        import warnings
        with warnings_helper.check_warnings(("boo", UserWarning)):
            warnings.warn("boo", UserWarning)

    def test_decorators(self):
        @cpython_only
        def f():
            return 1
        self.assertEqual(f(), 1)
        self.assertTrue(check_impl_detail())
        self.assertTrue(check_impl_detail(cpython=True))
        self.assertFalse(check_impl_detail(jython=True))


stream = io.StringIO()
result = unittest.TextTestRunner(stream=stream, verbosity=0).run(unittest.defaultTestLoader.loadTestsFromTestCase(Helpers))
print(result.testsRun, len(result.failures), len(result.errors), len(result.skipped))
for test, tb in result.failures + result.errors:
    print(test.id().rsplit(".", 1)[-1], tb.strip().splitlines()[-1])
