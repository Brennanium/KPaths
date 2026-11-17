import tempfile
import unittest
from pathlib import Path
from scripts.ide_sandbox import trust_project


class IdeSandboxTests(unittest.TestCase):
    def test_trust_is_idempotent_and_keeps_other_projects(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config, first, second = root / 'config', root / 'first project', root / 'second'
            trust_project(config, first)
            trust_project(config, first)
            trust_project(config, second)
            contents = (config / 'options/trusted-paths.xml').read_text()
            self.assertEqual(contents.count(str(first)), 1)
            self.assertEqual(contents.count(str(second)), 1)
