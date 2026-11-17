import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
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


if __name__ == '__main__':
    unittest.main()
