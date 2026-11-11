import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("compiler_matrix", SCRIPTS / "verify-compiler-compatibility.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CompilerMatrixTest(unittest.TestCase):
    def test_arbitrary_versions_run_all_suites_and_report_failures(self):
        calls = []
        def run(command, **_):
            calls.append(command)
            failed = ":kpaths-compiler-plugin-fixture:jvmTest" in command and "-Pkpaths.testKotlinVersion=2.4.0" in command
            return SimpleNamespace(returncode=1 if failed else 0)
        with tempfile.TemporaryDirectory() as directory, patch.object(MODULE, "simulator_available", return_value=True), patch.object(MODULE.subprocess, "run", side_effect=run), contextlib.redirect_stdout(io.StringIO()):
            report_path = Path(directory) / "nested" / "report.json"
            result = MODULE.main(["2.4.0", "2.4.21", "--report-json", str(report_path)])
            report = json.loads(report_path.read_text())
        self.assertEqual(result, 1)
        self.assertEqual([item["version"] for item in report["results"]], ["2.4.0", "2.4.21"])
        self.assertEqual(report["results"][0]["jvm"], "failed")
        self.assertEqual(report["results"][1]["jvm"], "passed")
        self.assertTrue(all(item["iosSimulator"] == "passed" for item in report["results"]))
        self.assertEqual(len(calls), 7)  # Publication, then JVM suites + JVM consumer + Native.
        for command in (calls[1], calls[4]):
            self.assertIn(":kpaths-compiler-plugin-fixture:jvmTest", command)
            self.assertIn(":kpaths-compiler-plugin-fixture:compilerPluginContractTest", command)
            self.assertIn("-Pkpaths.snapshotRecording=none", command)
        self.assertTrue(all(item["jvmConsumer"] == "passed" for item in report["results"]))

    def test_jvm_only_reports_native_and_ide_as_not_run(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(MODULE.subprocess, "run", return_value=SimpleNamespace(returncode=0)), contextlib.redirect_stdout(io.StringIO()):
            report_path = Path(directory) / "report.json"
            self.assertEqual(MODULE.main(["2.4.0", "--jvm-only", "--report-json", str(report_path)]), 0)
            report = json.loads(report_path.read_text())
        self.assertEqual(report["results"][0]["iosSimulator"], "not-run")
        self.assertEqual(report["ideKefs"], "not-run")

    def test_resolve_only_does_not_launch_build(self):
        with patch.object(MODULE.subprocess, "run") as run, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(MODULE.main(["2.4.0", "--resolve-only"]), 0)
        run.assert_not_called()

    def test_failed_publication_fails_the_matrix(self):
        def run(command, **_):
            return SimpleNamespace(returncode=1 if "-I" in command else 0)
        with tempfile.TemporaryDirectory() as directory, patch.object(MODULE.subprocess, "run", side_effect=run), contextlib.redirect_stdout(io.StringIO()):
            report_path = Path(directory) / "report.json"
            self.assertEqual(MODULE.main(["2.4.0", "--jvm-only", "--report-json", str(report_path)]), 1)
            self.assertEqual(json.loads(report_path.read_text())["results"][0]["jvmConsumer"], "failed-setup")

    def test_explicit_native_requires_simulator(self):
        with patch.object(MODULE, "simulator_available", return_value=False), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                MODULE.main(["2.4.0", "--native"])


if __name__ == "__main__":
    unittest.main()
