"""Opt-in Gradle integration test for the editor project metadata prototype."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


@unittest.skipUnless(os.environ.get('KPATHS_RUN_EDITOR_MODEL_INTEGRATION') == '1', 'opt-in Gradle integration test')
class EditorTestingModelIntegrationTest(unittest.TestCase):
    def test_export_uses_build_metadata_and_honors_version_override(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'model.json'
            command = [str(ROOT / 'gradlew'), '--no-configuration-cache', '-I',
                       str(ROOT / 'gradle/editor-testing-model.init.gradle'), 'exportEditorTestingModel',
                       '-PeditorTesting.gradlePluginProject=:kpaths-gradle-plugin',
                       '-PeditorTesting.runtimeProjects=:kpaths-core,:kpaths-annotations',
                       '-PeditorTesting.compilerPluginProject=:kpaths-compiler-plugin',
                       f'-PeditorTesting.modelFile={output}']
            subprocess.run(command + ['-PeditorTesting.kotlinVersion=2.4.20'], cwd=ROOT, check=True, capture_output=True, text=True)
            override = json.loads(output.read_text())
            self.assertEqual(override['kotlinVersion'], '2.4.20')
            self.assertEqual(override['gradlePlugin']['id'], 'dev.kpaths.gradle-plugin')
            self.assertEqual(override['compilerPlugin']['name'], 'kpaths-compiler-plugin-jvm')
            self.assertEqual([item['name'] for item in override['dependencies']], ['kpaths-core', 'kpaths-annotations'])
            subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True)
            default = json.loads(output.read_text())
            self.assertNotEqual(default['kotlinVersion'], override['kotlinVersion'])
            # Match the build's version catalog instead of baking its current version into the test.
            version_line = next(line for line in (ROOT / 'gradle/libs.versions.toml').read_text().splitlines()
                                if line.startswith('kotlin = '))
            self.assertEqual(default['kotlinVersion'], version_line.split('"')[1])

    def test_generates_and_compiles_consumer_from_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sources = root / 'sources'
            sources.mkdir()
            (sources / 'Example.kt').write_text(
                'import dev.kpaths.annotations.Pathable\n'
                '@Pathable data class Person(val name: String)\n'
                'val name = Person.Paths.name\n'
            )
            project = root / 'generated'
            command = [sys.executable, str(ROOT / 'scripts/prototype_editor_project.py'),
                       '--source-dir', str(sources), '--project-dir', str(project), '--compile']
            subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True)
            self.assertTrue((project / 'build/classes/kotlin/main/ExampleKt.class').exists())


if __name__ == '__main__':
    unittest.main()
