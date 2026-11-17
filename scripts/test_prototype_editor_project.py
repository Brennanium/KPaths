import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).parent))
from unittest import mock

spec = importlib.util.spec_from_file_location('prototype_editor', Path(__file__).with_name('prototype_editor_project.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PrototypeEditorProjectTest(unittest.TestCase):
    def test_roles_become_gradle_tasks_without_hardcoded_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'sources'
            source.mkdir()
            (source / 'Example.kt').write_text('val value = 1')
            recorded = []
            model = {'schemaVersion': 1, 'kotlinVersion': '2.4.21', 'gradlePlugin': {}, 'dependencies': []}

            def fake_gradle(command, **_):
                recorded.append(command)
                model_arg = next(item for item in command if item.startswith('-PeditorTesting.modelFile='))
                Path(model_arg.split('=', 1)[1]).write_text(json.dumps(model))

            with mock.patch.object(module, 'project_root', return_value=root), \
                 mock.patch.object(module.subprocess, 'run', side_effect=fake_gradle), \
                 mock.patch.object(module, 'generate_project', return_value=root / 'generated') as generate:
                module.main(['--gradle-plugin-project', ':plugin', '--compiler-plugin-project', ':compiler',
                             '--runtime-project', ':runtime', '--source-dir', str(source),
                             '--project-dir', str(root / 'generated'), '--kotlin-version', '2.5.0', '--offline'])
            command = recorded[0]
            self.assertIn(':plugin:publishAllPublicationsToConsumerVerificationRepository', command)
            self.assertIn(':compiler:publishJvmPublicationToConsumerVerificationRepository', command)
            self.assertIn(':runtime:publishKotlinMultiplatformPublicationToConsumerVerificationRepository', command)
            self.assertIn('-PeditorTesting.kotlinVersion=2.5.0', command)
            self.assertIn('--offline', command)
            self.assertEqual(generate.call_args.args[4], {'Example.kt': 'val value = 1'})
            self.assertFalse(any('0.1.0' in item for item in command))

    def test_uses_toml_defaults_and_allows_role_overrides(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'editor-testing.toml').write_text(
                '[project]\ngradle-plugin = ":plugin"\ncompiler-plugin = ":compiler"\n'
                'runtime = [":runtime"]\n[ide]\nintegration = "kefs"\n')
            model = {'schemaVersion': 1, 'kotlinVersion': '2.4.21'}
            with mock.patch.object(module, 'project_root', return_value=root), \
                 mock.patch.object(module, 'prepare_checkout', return_value=model) as prepare, \
                 mock.patch.object(module, 'generate_project', return_value=root / 'generated'):
                module.main(['--project-dir', str(root / 'generated'), '--runtime-project', ':other'])
            prepare.assert_called_once_with(root, ':plugin', ':compiler', [':other'], None, False)


if __name__ == '__main__':
    unittest.main()
