import tempfile
import unittest
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).parent))
from editor_testing_config import read_config

VALID = '''[project]
gradle-plugin = ":plugin"
compiler-plugin = ":compiler"
runtime = [":core", ":annotations"]
[ide]
integration = "kefs"
'''


class EditorTestingConfigTest(unittest.TestCase):
    def parse(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'editor-testing.toml'
            path.write_text(text)
            return read_config(path)

    def test_minimal_roles(self):
        self.assertEqual(self.parse(VALID)['project']['runtime'], [':core', ':annotations'])

    def test_rejects_invalid_and_duplicate_paths(self):
        for value in ('["core"]', '[":core", ":core"]', '[]', '"not an array"'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.parse(VALID.replace('[":core", ":annotations"]', value))

    def test_rejects_unknown_options_and_integration(self):
        for value in (VALID + 'extra = "value"\n', VALID.replace('"kefs"', '"unknown"')):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.parse(value)


if __name__ == '__main__':
    unittest.main()
