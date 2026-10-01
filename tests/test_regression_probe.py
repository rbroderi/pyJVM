from pathlib import Path

from pyjvm315.regression_probe import normalize_reason, probe_files


def test_regression_probe_extracts_individual_test_cases(tmp_path: Path):
    source = tmp_path / "test_sample.py"
    source.write_text(
        """
class Tests:
    def test_ok(self):
        x = 1
        print(x)

    def test_unsupported(self):
        match 1:
            case 1:
                pass
""".strip()
        + "\n",
        encoding="utf-8",
    )

    report = probe_files([source])
    assert report["summary"]["COMPILES"] == 1
    assert report["summary"]["UNSUPPORTED"] == 1
    assert any("Match" in item["reason"] for item in report["unsupported_reasons"])


def test_reason_normalization_removes_line_numbers():
    assert normalize_reason("Unsupported statement: Match at line 42") == "Unsupported statement: Match"
