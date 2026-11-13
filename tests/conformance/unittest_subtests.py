import io
import unittest


class Subs(unittest.TestCase):
    def test_all_pass(self):
        for i in range(3):
            with self.subTest(i=i):
                self.assertLess(i, 5)

    def test_some_fail(self):
        for i in range(4):
            with self.subTest("case", i=i):
                self.assertEqual(i % 2, 0)

    def test_nested(self):
        for a in (1, 2):
            with self.subTest(a=a):
                for b in (1, 2):
                    with self.subTest(b=b):
                        if a == 2 and b == 1:
                            self.fail("nope")

    def test_error_in_sub(self):
        with self.subTest(kind="err"):
            raise ValueError("inside")


class Skips(unittest.TestCase):
    @unittest.skipIf(True, "always")
    def test_skip_if(self):
        self.fail("not run")

    @unittest.skipUnless(False, "never")
    def test_skip_unless(self):
        self.fail("not run")

    def test_skip_inside(self):
        self.skipTest("inside")

    @unittest.expectedFailure
    def test_unexpected_success(self):
        pass


def run(case):
    stream = io.StringIO()
    result = unittest.TextTestRunner(stream=stream, verbosity=0).run(
        unittest.defaultTestLoader.loadTestsFromTestCase(case))
    print(case.__name__, result.testsRun, len(result.failures), len(result.errors),
          len(result.skipped), len(result.expectedFailures), len(result.unexpectedSuccesses),
          result.wasSuccessful())
    for test, tb in sorted(result.failures + result.errors, key=lambda p: str(p[0])):
        print(" ", str(test).split(" (")[0], tb.strip().splitlines()[-1])
    for test, reason in sorted(result.skipped, key=lambda p: str(p[0])):
        print(" skipped", str(test).split(" (")[0], reason)


run(Subs)
run(Skips)
