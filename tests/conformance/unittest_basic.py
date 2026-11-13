import io
import unittest


class Basic(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        print("setUpClass")

    @classmethod
    def tearDownClass(cls):
        print("tearDownClass")

    def setUp(self):
        self.x = 1

    def tearDown(self):
        pass

    def test_eq(self):
        self.assertEqual(self.x, 1)
        self.assertTrue(True)
        self.assertIn(1, [1, 2])
        self.assertIsNone(None)

    def test_raises(self):
        with self.assertRaises(ZeroDivisionError):
            1 / 0
        with self.assertRaisesRegex(ValueError, "bad"):
            raise ValueError("bad thing")
        self.assertRaises(KeyError, {}.__getitem__, 1)

    def test_fail(self):
        self.assertEqual(1, 2)

    def test_error(self):
        raise RuntimeError("boom")

    @unittest.skip("nope")
    def test_skip(self):
        pass

    @unittest.expectedFailure
    def test_xfail(self):
        self.fail("expected")


suite = unittest.defaultTestLoader.loadTestsFromTestCase(Basic)
stream = io.StringIO()
res = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
for line in stream.getvalue().splitlines():
    if line.startswith(("test_", "FAILED", "OK")) or line.startswith(("AssertionError", "RuntimeError")):
        print(line.split(" (")[0] + (" ..." + line.rsplit("...", 1)[1] if " ... " in line else ""))
    elif line.startswith("Ran"):
        print(line.split(" in ")[0])
print(res.testsRun, len(res.failures), len(res.errors), len(res.skipped), len(res.expectedFailures))
