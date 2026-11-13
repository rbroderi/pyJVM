# SPDX-License-Identifier: GPL-3.0-or-later
"""In-process runner for CPython's Lib/test modules (compiled and run by pyJVM, or run by CPython for comparison).

usage: pyjvm_regrtest.py [-v] [-k PATTERN] test_name [test_name ...]

Loads ``test.<name>`` for each name, runs its unittest suite and prints one ``RESULT <id> <status>`` line per test
(ok, FAIL, ERROR, skipped, expected failure, unexpected success) followed by a summary line. tools/run_cpython_test.py
drives it on both lanes and compares the per-test statuses.
"""
import importlib
import io
import sys
import unittest


class _Result(unittest.TextTestResult):
    def __init__(self, stream, descriptions, verbosity, **kwargs):
        super().__init__(stream, descriptions, verbosity, **kwargs)
        self.statuses = []

    def _note(self, test, status):
        self.statuses.append((test.id(), status))

    def addSuccess(self, test):
        super().addSuccess(test)
        self._note(test, "ok")

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self._note(test, "FAIL")

    def addError(self, test, err):
        super().addError(test, err)
        self._note(test, "ERROR")

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self._note(test, "skipped")

    def addExpectedFailure(self, test, err):
        super().addExpectedFailure(test, err)
        self._note(test, "expected failure")

    def addUnexpectedSuccess(self, test):
        super().addUnexpectedSuccess(test)
        self._note(test, "unexpected success")

    def addSubTest(self, test, subtest, err):
        super().addSubTest(test, subtest, err)
        if err is not None:
            self._note(test, "FAIL" if issubclass(err[0], test.failureException) else "ERROR")


def main(argv):
    verbose = False
    pattern = None
    names = []
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "-v":
            verbose = True
        elif arg == "-k":
            i += 1
            pattern = argv[i]
        else:
            names.append(arg)
        i += 1
    failed = False
    for name in names:
        modname = name if name.startswith("test.") else "test." + name
        try:
            module = importlib.import_module(modname)
        except unittest.SkipTest as exc:
            print("RESULT %s skipped-module %s" % (modname, exc))
            continue
        except Exception as exc:   # a module that cannot be imported must not hide the other modules
            print("RESULT %s import-error %s" % (modname, type(exc).__name__))
            sys.stderr.write("import of %s failed: %s: %s\n" % (modname, type(exc).__name__, str(exc)[:200]))
            failed = True
            continue
        loader = unittest.TestLoader()
        if pattern:
            loader.testNamePatterns = ["*%s*" % pattern]
        suite = loader.loadTestsFromModule(module)
        stream = io.StringIO()
        runner = unittest.TextTestRunner(stream=stream, verbosity=2 if verbose else 1, resultclass=_Result)
        result = runner.run(suite)
        for test_id, status in sorted(result.statuses):
            print("RESULT %s %s" % (test_id, status))
        print("SUMMARY %s run=%d failures=%d errors=%d skipped=%d xfail=%d xpass=%d" % (
            modname, result.testsRun, len(result.failures), len(result.errors), len(result.skipped),
            len(result.expectedFailures), len(result.unexpectedSuccesses)))
        if verbose or not result.wasSuccessful():
            sys.stderr.write(stream.getvalue())
        failed = failed or not result.wasSuccessful()
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
