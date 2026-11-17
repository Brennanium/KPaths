"""Verify a pinned local wheel works outside the KPaths checkout."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


class MatrixPackageTests(unittest.TestCase):
    def test_source_distribution_wheel_and_pinned_install(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, artifacts, external = root / 'source', root / 'artifacts', root / 'external'
            source.mkdir()
            artifacts.mkdir()
            external.mkdir()
            for name in ('setup.py', 'pyproject.toml', 'LICENSE'):
                shutil.copy2(ROOT / name, source / name)
            shutil.copytree(ROOT / 'scripts', source / 'scripts',
                            ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
            env = dict(os.environ, PIP_DISABLE_PIP_VERSION_CHECK='1')
            subprocess.run([sys.executable, 'setup.py', 'sdist', '--dist-dir', str(artifacts)],
                           cwd=source, env=env, check=True, capture_output=True, text=True)
            archive = next(artifacts.glob('*.tar.gz'))
            subprocess.run([sys.executable, '-m', 'pip', 'wheel', '--no-index', '--no-deps',
                            '--no-build-isolation', '--wheel-dir', str(artifacts), str(archive)],
                           cwd=external, env=env, check=True, capture_output=True, text=True)
            wheel = next(artifacts.glob('*.whl'))
            with zipfile.ZipFile(wheel) as package:
                self.assertIn('kotlin_plugin_matrix/kotlin-plugin-matrix.py', package.namelist())
                self.assertIn('kotlin_plugin_matrix/imported_ide_project.py', package.namelist())
                self.assertIn('kotlin_plugin_matrix/editor_testing_config.py', package.namelist())
                self.assertIn('kotlin_plugin_matrix/editor_project_model.py', package.namelist())
                self.assertIn('kotlin_plugin_matrix/prototype_editor_project.py', package.namelist())
                self.assertFalse(any('/test_' in item for item in package.namelist()))
                metadata = package.read(next(item for item in package.namelist() if item.endswith('.dist-info/METADATA'))).decode()
                self.assertIn('Requires-Dist: tomli', metadata)
                self.assertIn('python_version < "3.11"', metadata)
            venv = root / 'venv'
            subprocess.run([sys.executable, '-m', 'venv', str(venv)], check=True, capture_output=True, text=True)
            python = venv / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
            subprocess.run([str(python), '-m', 'pip', 'install', '--no-index', '--no-deps', '--find-links',
                            str(artifacts), 'kotlin-plugin-matrix==0.0.0.dev0'],
                           cwd=external, env=env, check=True, capture_output=True, text=True)
            command = venv / ('Scripts/kotlin-plugin-matrix.exe' if os.name == 'nt' else 'bin/kotlin-plugin-matrix')
            result = subprocess.run([str(command), '--help'], cwd=external, env=env, check=True,
                                    capture_output=True, text=True)
            self.assertIn('{compiler,ide}', result.stdout)
            version = subprocess.run([str(command), '--version'], cwd=external, env=env, check=True,
                                     capture_output=True, text=True)
            self.assertIn('0.0.0.dev0', version.stdout)
            missing_project = subprocess.run([str(command), 'compiler', '2.4.20'], cwd=external,
                                             env=env, capture_output=True, text=True)
            self.assertNotEqual(missing_project.returncode, 0)
            self.assertIn('use --driver', missing_project.stderr)
            if os.name != 'nt':
                driver = external / 'driver'
                driver.write_text('#!/bin/sh\n[ "$1" = --kotlin-version ] && [ "$2" = 2.4.20 ]\n')
                driver.chmod(0o755)
                subprocess.run([str(command), 'compiler', '2.4.20', '--driver', str(driver)],
                               cwd=external, env=env, check=True, capture_output=True, text=True)


if __name__ == '__main__':
    unittest.main()
