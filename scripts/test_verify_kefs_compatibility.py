import importlib.util
import json
import tempfile
import unittest
from unittest import mock
from pathlib import Path

spec = importlib.util.spec_from_file_location("verify_kefs", Path(__file__).with_name("verify-kefs-compatibility.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class KefsMatrixTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
        self.ide = root / "Studio.app"
        info = self.ide / "Contents/Resources/product-info.json"
        info.parent.mkdir(parents=True)
        info.write_text(json.dumps({"name": "Android Studio", "buildNumber": "262.1"}))
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
            settings.write_bytes(b"temporary settings")

        with mock.patch.object(module, "ROOT", root), \
             mock.patch.object(module.subprocess, "run", side_effect=configure), \
             mock.patch.object(module.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(RuntimeError, "exited early"):
                module.verify_imported_consumer(self.ide, "0.5.0-262")
        self.assertEqual(settings.read_bytes(), b"original settings")


if __name__ == "__main__":
    unittest.main()
