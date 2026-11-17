import importlib.util
import subprocess
import sys
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock


script = Path(__file__).with_name('kotlin-plugin-matrix.py')
spec = importlib.util.spec_from_file_location('matrix_cli', script)
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class MatrixCliTests(unittest.TestCase):
    def test_dispatches_arguments_and_preserves_failure(self):
        with mock.patch.object(cli.subprocess, 'run', return_value=mock.Mock(returncode=7)) as run:
            self.assertEqual(cli.main(['compiler', '2.5.0-dev*', '--jvm-only']), 7)
        run.assert_called_once_with(
            [sys.executable, str(script.resolve().with_name('verify-compiler-compatibility.py')),
             '2.5.0-dev*', '--jvm-only'], check=False)

    def test_ide_help_reaches_subcommand(self):
        result = subprocess.run([sys.executable, str(script), 'ide', '--help'],
                                capture_output=True, text=True, check=True)
        self.assertIn('--ide-path', result.stdout)
        self.assertIn('--driver', result.stdout)

    def test_custom_compiler_driver_runs_each_version_and_reports_failures(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = root / 'driver'
            driver.write_text('#!/bin/sh\nset -eu\nprintf "%s\\n" "$2" >> versions\n[ "$2" != 2.4.21 ]\n')
            driver.chmod(0o755)
            report = root / 'report.json'
            result = subprocess.run([sys.executable, str(script), 'compiler', '2.4.20', '2.4.21',
                                     '--driver', str(driver), '--report-json', str(report)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertEqual((root / 'versions').read_text().splitlines(), ['2.4.20', '2.4.21'])
            self.assertEqual([x['result'] for x in json.loads(report.read_text())['results']], ['passed', 'failed'])

    def test_custom_compiler_driver_succeeds_and_deduplicates_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = root / 'driver'
            driver.write_text('#!/bin/sh\nset -eu\nprintf "%s\\n" "$2" >> versions\n')
            driver.chmod(0o755)
            report = root / 'report.json'
            result = subprocess.run([sys.executable, str(script), 'compiler', '2.4.20', '2.4.20',
                                     '--driver', str(driver), '--report-json', str(report)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0)
            self.assertEqual((root / 'versions').read_text().splitlines(), ['2.4.20'])
            self.assertEqual(json.loads(report.read_text())['results'][0]['result'], 'passed')
