from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from pyjvm315 import methodsize
from pyjvm315.cli import build_runtime
from pyjvm315.owner_map import owner_for, owner_map


class MethodSizeTests(unittest.TestCase):
    def test_runtime_has_no_huge_methods(self):
        with tempfile.TemporaryDirectory() as d:
            build_runtime(Path(d))
            allm, bad = methodsize.check([Path(d)])
        self.assertGreater(len(allm), 100)
        self.assertEqual(bad, [], f"methods over {methodsize.HUGE_METHOD_LIMIT} bytes")

    def test_lint_fails_when_limit_is_lowered(self):
        with tempfile.TemporaryDirectory() as d:
            build_runtime(Path(d))
            self.assertEqual(methodsize.main([d, "--limit", "100"]), 1)
            self.assertEqual(methodsize.main([d]), 0)


class OwnerMapTests(unittest.TestCase):
    def test_known_reasons(self):
        self.assertEqual(owner_for("Unsupported statement: Match")[1], "P1-01..P1-03")
        self.assertEqual(owner_for("annotation-only assignments are not implemented yet")[1], "P1-09")
        self.assertEqual(owner_for("something novel")[1], "triage")

    def test_grouping_sums_counts(self):
        report = {"unsupported_reasons": [
            {"reason": "Unsupported statement: Match", "count": 283},
            {"reason": "import of module 'copy' is not available", "count": 5},
            {"reason": "import of module 're' is not available", "count": 4},
        ]}
        groups = owner_map(report)
        self.assertEqual(groups[0]["count"], 283)
        self.assertEqual(groups[1]["count"], 9)


if __name__ == "__main__":
    unittest.main()
