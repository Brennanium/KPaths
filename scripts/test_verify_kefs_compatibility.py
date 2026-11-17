import importlib.util
import io
import json
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
spec = importlib.util.spec_from_file_location("verify_kefs", Path(__file__).with_name("verify-kefs-compatibility.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class KefsMatrixTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
        consumer = root / "kpaths-kefs-ide-fixture/consumer"
        (consumer / "src/main/kotlin").mkdir(parents=True)
        (consumer / "src/main/kotlin/Example.kt").write_text("package example\n")
        (consumer / "settings.gradle.kts").write_text('uri("../../build/consumer-verification/repository")\n' * 2)
        (root / 'gradle/wrapper').mkdir(parents=True)
        (root / 'gradle/wrapper/gradle-wrapper.properties').write_text('wrapper')
        for name in ('gradlew', 'gradlew.bat'):
            (root / name).write_text(name)
        self.model = {'schemaVersion': 1, 'kotlinVersion': '2.4.21',
                      'gradlePlugin': {'id': 'example.plugin', 'version': 'local'},
                      'compilerPlugin': {'group': 'example', 'name': 'compiler'},
                      'dependencies': []}
        self.ide = root / "Studio.app"
        info = self.ide / "Contents/Resources/product-info.json"
        info.parent.mkdir(parents=True)
        info.write_text(json.dumps({"name": "Android Studio", "buildNumber": "262.1", "productCode": "AI"}))
        self.matrix = root / "matrix.json"
        self.matrix.write_text(json.dumps({"tested": [{"product": "Android Studio", "build": "262.1", "kotlinIdeVersion": "2.4.20-ij262-52", "kefsVersion": "0.5.0-262"}]}))

    def test_known_ide_uses_the_tested_versions(self):
        self.assertEqual(module.select_ide(self.ide, self.matrix, None, None),
                         ("Android Studio 262.1", "2.4.20-ij262-52", "0.5.0-262"))

    def test_mislabelled_known_ide_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "IDE compiler"):
            module.select_ide(self.ide, self.matrix, "2.4.20-ij262-999", None)
        with self.assertRaisesRegex(ValueError, "KEFS"):
            module.select_ide(self.ide, self.matrix, None, "0.0.0")

    def test_unlisted_ide_needs_explicit_versions_but_is_not_blocked(self):
        self.matrix.write_text('{"tested": []}')
        with self.assertRaisesRegex(ValueError, "not in the tested IDE matrix"):
            module.select_ide(self.ide, self.matrix, None, None)
        self.assertEqual(module.select_ide(self.ide, self.matrix, "2.5.0-ij999-1", "0.6.0"),
                         ("Android Studio 262.1 (not yet in the tested matrix)", "2.5.0-ij999-1", "0.6.0"))

    def test_known_ide_can_test_a_new_explicit_combination(self):
        self.assertEqual(module.select_ide(self.ide, self.matrix, None, "0.6.0", allow_untested=True),
                         ("Android Studio 262.1 (untested KEFS/compiler combination)", "2.4.20-ij262-52", "0.6.0"))

    def test_orphan_cleanup_only_targets_this_sandbox_probe(self):
        report = Path('/tmp/kpaths-probe.txt')
        processes = ('101 java -Didea.plugin.in.sandbox.mode=true -Dkpaths.consumerProbe=/tmp/kpaths-probe.txt -Dkpaths.consumerExpected=enabled\n'
                     '102 java -Dkpaths.consumerProbe=/tmp/kpaths-probe.txt -Dkpaths.consumerExpected=enabled\n'
                     '103 java -Didea.plugin.in.sandbox.mode=true -Dkpaths.consumerProbe=/tmp/kpaths-probe.txt -Dkpaths.consumerExpected=disabled\n')
        with mock.patch.object(module.subprocess, 'run', return_value=mock.Mock(stdout=processes)), \
             mock.patch.object(module.time, 'sleep'), \
             mock.patch.object(module.os, 'kill', side_effect=[None, ProcessLookupError]) as kill:
            module.stop_fixture_ides(report, 'enabled')
        self.assertEqual(kill.call_args_list, [mock.call(101, module.signal.SIGTERM), mock.call(101, 0)])

    def test_project_trust_is_limited_to_fixture_sandbox(self):
        fixture = Path(self.tmp.name) / 'fixture'
        module.trust_fixture_project(fixture, fixture / 'consumer', '123', 'enabled', 1)
        config = fixture / 'build/ide-probe/123/enabled-1/config/options/trusted-paths.xml'
        self.assertIn(str(fixture / 'consumer'), config.read_text())
        module.trust_fixture_project(fixture, fixture / 'consumer', '123', 'enabled', 1)
        self.assertEqual(config.read_text().count(str(fixture / 'consumer')), 1)
        module.trust_fixture_project(fixture, fixture / 'consumer', '123', 'disabled', 1)
        self.assertTrue((fixture / 'build/ide-probe/123/disabled-1/config/options/trusted-paths.xml').exists())

    def test_sdk_settings_are_staged_in_the_disposable_project(self):
        root = Path(self.tmp.name)
        fixture = root / 'kpaths-kefs-ide-fixture'
        project = root / 'project'
        project.mkdir()
        fixture_local = fixture / 'consumer/local.properties'
        fixture_local.write_text('sdk.dir=/configured/sdk\n')
        with mock.patch.dict(module.os.environ, {'ANDROID_HOME': '/other/sdk'}, clear=True):
            module.stage_android_sdk(fixture, project)
        self.assertEqual((project / 'local.properties').read_text(), 'sdk.dir=/configured/sdk\n')
        fixture_local.unlink()
        with mock.patch.dict(module.os.environ, {'ANDROID_HOME': '/sdk with spaces'}, clear=True):
            module.stage_android_sdk(fixture, project)
        self.assertIn('sdk.dir=/sdk\\ with\\ spaces', (project / 'local.properties').read_text())

    def test_fatal_ide_startup_dialog_is_reported(self):
        log = Path(self.tmp.name) / 'idea.log'
        self.assertFalse(module.startup_failure(log))
        log.write_text('Booting IDE\n**Start Failed**\nInternal error\n')
        self.assertTrue(module.startup_failure(log))

    def test_retries_only_the_known_analytics_startup_failure(self):
        root = Path(self.tmp.name)
        calls = []
        def run_mode(_ide, _kefs, _fixture, _project, _report, mode, log, attempt, _run_id):
            self.assertEqual(_project, _project.resolve())
            calls.append((mode, attempt))
            if calls == [('enabled', 1)]:
                log.write_text('analytics initialization failed')
                raise module.AnalyticsStartupError('startup')
        with mock.patch.object(module, 'ROOT', root), \
             mock.patch.object(module, 'trust_fixture_project'), \
             mock.patch.object(module.subprocess, 'run'), \
             mock.patch.object(module, 'run_consumer_mode', side_effect=run_mode):
            module.verify_imported_consumer(self.ide, '0.5.0', self.model)
        self.assertEqual(calls, [('enabled', 1), ('enabled', 2), ('disabled', 1), ('diagnostic', 1)])
        saved = root / 'kpaths-kefs-ide-fixture/build/consumer-probe-enabled-startup-retry.log'
        self.assertEqual(saved.read_text(), 'analytics initialization failed')

    def test_other_probe_failures_are_not_retried(self):
        with mock.patch.object(module, 'ROOT', Path(self.tmp.name)), \
             mock.patch.object(module, 'trust_fixture_project'), \
             mock.patch.object(module.subprocess, 'run'), \
             mock.patch.object(module, 'run_consumer_mode', side_effect=RuntimeError('resolution failed')) as run:
            with self.assertRaisesRegex(RuntimeError, 'resolution failed'):
                module.verify_imported_consumer(self.ide, '0.5.0', self.model)
        self.assertEqual(run.call_count, 1)

    def test_only_the_descriptor_filesystem_race_is_retried(self):
        failed = mock.Mock(stdout=io.StringIO('Unable to read descriptor [plugin.xml]\nClosedFileSystemException\n'))
        failed.wait.return_value = 1
        succeeded = mock.Mock(stdout=io.StringIO('BUILD SUCCESSFUL\n'))
        succeeded.wait.return_value = 0
        with mock.patch.object(module.subprocess, 'Popen', side_effect=[failed, succeeded]) as start:
            module.publish_ide_artifact(['gradle'])
        self.assertEqual(start.call_count, 2)
        self.assertEqual(start.call_args_list[1].args[0], ['gradle', '--no-daemon', '--refresh-dependencies'])
        other = mock.Mock(stdout=io.StringIO('Test assertion failed\n'))
        other.wait.return_value = 1
        with mock.patch.object(module.subprocess, 'Popen', return_value=other) as start:
            with self.assertRaises(module.subprocess.CalledProcessError):
                module.publish_ide_artifact(['gradle'])
        self.assertEqual(start.call_count, 1)

    def test_descriptor_retry_preserves_and_rebuilds_only_selected_ide_index(self):
        root = Path(self.tmp.name)
        directory = root / 'kpaths-kefs-ide-fixture/.intellijPlatform/ides/layoutIndex'
        directory.mkdir(parents=True)
        selected = directory / 'AI-262.1-hash.json'
        other = directory / 'AI-261.2-hash.json'
        selected.write_text('incomplete')
        other.write_text('other')
        failed = mock.Mock(stdout=io.StringIO('Unable to read descriptor [plugin.xml]\nClosedFileSystemException\n'))
        failed.wait.return_value = 1
        succeeded = mock.Mock(stdout=io.StringIO('ok\n'))
        succeeded.wait.return_value = 0
        with mock.patch.object(module, 'ROOT', root), \
             mock.patch.object(module.subprocess, 'Popen', side_effect=[failed, succeeded]):
            module.publish_ide_artifact(['gradle', f'-Pkpaths.idePath={self.ide}'])
        self.assertFalse(selected.exists())
        self.assertEqual(other.read_text(), 'other')
        self.assertEqual(next(directory.glob('AI-262.1-hash.json.failed-*')).read_text(), 'incomplete')

    def test_duplicate_matrix_entries_are_rejected(self):
        entry = json.loads(self.matrix.read_text())["tested"][0]
        self.matrix.write_text(json.dumps({"tested": [entry, entry]}))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            module.select_ide(self.ide, self.matrix, None, None)

    def test_consumer_settings_are_restored_if_the_ide_exits_early(self):
        root = Path(self.tmp.name)
        settings = root / "kpaths-kefs-ide-fixture/consumer/.idea/kotlin-plugins.xml"
        settings.parent.mkdir(parents=True)
        settings.write_bytes(b"original settings")
        process = mock.Mock()
        process.poll.return_value = 1

        def configure(*args, **kwargs):
            self.assertNotIn(str(settings), args[0])

        with mock.patch.object(module, "ROOT", root), \
             mock.patch.object(module.subprocess, "run", side_effect=configure), \
             mock.patch.object(module.subprocess, "Popen", return_value=process), \
             mock.patch.object(module, "stop_fixture_ides"):
            with self.assertRaisesRegex(RuntimeError, "exited early"):
                module.verify_imported_consumer(self.ide, "0.5.0-262", self.model)
        self.assertEqual(settings.read_bytes(), b"original settings")
        self.assertEqual(list((root / "kpaths-kefs-ide-fixture/build").glob("imported-source-*")), [])


if __name__ == "__main__":
    unittest.main()
