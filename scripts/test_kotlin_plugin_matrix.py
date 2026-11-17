import importlib.util
import subprocess
import sys
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
