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
            case [1]:
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


def test_explicit_manifest_selects_only_requested_body(tmp_path):
    from pyjvm315.regression_probe import main
    import json
    source = tmp_path / 'test_selection.py'
    source.write_text('class Cases:\n    def test_not_selected(self):\n        match 1:\n            case [1]: pass\n    def test_selected(self):\n        assert 1 == 1\n')
    manifest = tmp_path / 'manifest.txt'
    manifest.write_text('test_selection.py::Cases.test_selected\n')
    output = tmp_path / 'report.json'
    args = ['--manifest', str(manifest), '--cpython-root', str(tmp_path), '--output', str(output)]
    assert main([*args, '--expect-cases', '1', '--fail-on-error']) == 0
    report = json.loads(output.read_text())
    assert report['summary'] == {'COMPILES': 1}
    assert report['results'][0]['file'] == 'test_selection.py'
    assert report['results'][0]['case'] == 'Cases.test_selected'
    assert main([*args, '--expect-cases', '2']) == 1


def test_manifest_rejects_duplicate_and_mixed_selection(tmp_path):
    import pytest
    from pyjvm315.regression_probe import _read_manifest
    manifest = tmp_path / 'manifest.txt'
    for lines in ['test_sample.py::Cases.test_a\ntest_sample.py::Cases.test_a\n',
                  'test_sample.py\ntest_sample.py::Cases.test_a\n',
                  'test_sample.py::\n']:
        manifest.write_text(lines)
        with pytest.raises(ValueError):
            _read_manifest(manifest, tmp_path)


def test_missing_selected_body_is_an_error(tmp_path):
    source = tmp_path / 'test_missing.py'
    source.write_text('def test_other():\n    pass\n')
    report = probe_files([source], selections={source: ['Cases.test_deleted']})
    assert report['summary'] == {'ERROR': 1}
    assert report['results'][0]['detail'] == 'selected case not found'


def test_baseline_catches_regressions_despite_offsetting_gains():
    from pyjvm315.regression_probe import baseline_regressions
    baseline = {'cpython_commit': 'corpus', 'manifest_sha256': 'manifest',
                'statuses': {'test.py::A': 'COMPILES', 'test.py::B': 'UNSUPPORTED'}}
    report = {'cpython_commit': 'corpus', 'manifest_sha256': 'manifest',
              'results': [{'file': 'test.py', 'case': 'A', 'status': 'UNSUPPORTED'},
                          {'file': 'test.py', 'case': 'B', 'status': 'COMPILES'}]}
    assert baseline_regressions(report, baseline) == ['previously compiling case regressed: test.py::A']
    report['results'][0]['status'] = 'COMPILES'
    assert baseline_regressions(report, baseline) == []
    report['manifest_sha256'] = 'changed'
    report['results'].pop()
    failures = baseline_regressions(report, baseline)
    assert 'manifest_sha256 differs from baseline' in failures
    assert 'selected case identities differ from baseline' in failures


def test_expanded_manifest_pins_three_thousand_unique_bodies():
    manifest = Path(__file__).resolve().parents[1] / 'tools/cpython_probe_3000_manifest.txt'
    entries = [line for line in manifest.read_text().splitlines() if line and not line.startswith('#')]
    assert len(entries) == len(set(entries)) == 3000
    assert all('::' in entry for entry in entries)
    legacy_files = {'Lib/test/test_bytes.py', 'Lib/test/test_descr.py',
                    'Lib/test/test_importlib/import_/test_api.py',
                    'Lib/test/test_importlib/import_/test_fromlist.py',
                    'Lib/test/test_importlib/import_/test_relative_imports.py'}
    assert sum(entry.split('::')[0] in legacy_files for entry in entries) == 358
