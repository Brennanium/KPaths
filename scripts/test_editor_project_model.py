import tempfile
import unittest
from pathlib import Path
from editor_project_model import generate_project


class EditorProjectModelTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.wrapper = self.root / 'wrapper'
        (self.wrapper / 'gradle/wrapper').mkdir(parents=True)
        (self.wrapper / 'gradle/wrapper/gradle-wrapper.properties').write_text('wrapper')
        for name in ('gradlew', 'gradlew.bat'):
            (self.wrapper / name).write_text(name)
        self.model = {
            'schemaVersion': 1, 'kotlinVersion': '2.4.21',
            'gradlePlugin': {'id': 'dev.example.compiler', 'version': '0.0.0-local'},
            'dependencies': [{'group': 'dev.example', 'name': 'runtime', 'version': '0.0.0-local'}],
        }

    def test_generates_gradle_and_multiple_sources(self):
        result = generate_project(self.root / 'project', self.model, self.root / 'repo', self.wrapper,
                                  {'Example.kt': 'val x = 1', 'nested/Other.kt': 'val y = 2'})
        settings = (result / 'settings.gradle.kts').read_text()
        self.assertEqual(settings.count(str((self.root / 'repo').resolve())), 2)
        build = (result / 'build.gradle.kts').read_text()
        self.assertIn('kotlin("jvm") version "2.4.21"', build)
        self.assertIn('id("dev.example.compiler") version "0.0.0-local"', build)
        self.assertIn('implementation("dev.example:runtime:0.0.0-local")', build)
        self.assertEqual((result / 'src/main/kotlin/nested/Other.kt').read_text(), 'val y = 2')
        self.assertTrue((result / 'gradle/wrapper/gradle-wrapper.properties').exists())

    def test_plain_kotlin_needs_no_plugin_or_dependencies(self):
        model = {'schemaVersion': 1, 'kotlinVersion': '2.4.21'}
        result = generate_project(self.root / 'plain', model, self.root / 'repo', self.wrapper, {})
        self.assertNotIn('dev.example', (result / 'build.gradle.kts').read_text())

    def test_rejects_escaping_source_path_before_creating_project(self):
        with self.assertRaises(ValueError):
            generate_project(self.root / 'project', self.model, self.root / 'repo', self.wrapper, {'../Bad.kt': 'x'})
        self.assertFalse((self.root / 'project').exists())

    def test_rejects_unknown_schema(self):
        with self.assertRaises(ValueError):
            generate_project(self.root / 'project', {'schemaVersion': 2}, self.root / 'repo', self.wrapper, {})

    def test_escapes_kotlin_interpolation(self):
        self.model['gradlePlugin']['id'] = 'example.$unexpected'
        result = generate_project(self.root / 'project', self.model, self.root / 'repo', self.wrapper, {})
        self.assertIn('example.\\$unexpected', (result / 'build.gradle.kts').read_text())


if __name__ == '__main__':
    unittest.main()
