import tempfile
import unittest
from pathlib import Path

from imported_ide_project import prepare_imported_project


class ImportedIdeProjectTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.template = self.root / 'template'
        (self.template / 'src/main/kotlin').mkdir(parents=True)
        (self.template / 'src/main/kotlin/Old.kt').write_text('old')
        (self.template / '.idea').mkdir()
        (self.template / '.idea/private.xml').write_text('private')
        (self.template / 'settings.gradle.kts').write_text(
            'uri("../../build/consumer-verification/repository")\n' * 2
        )

    def test_sources_and_repository_are_isolated(self):
        repo = self.root / 'repo'
        project = prepare_imported_project(self.template, self.root / 'project', repo,
            {'Example.kt': 'val x = helper', 'nested/Helper.kt': 'val helper = 1'})
        self.assertEqual((project / 'src/main/kotlin/Example.kt').read_text(), 'val x = helper')
        self.assertEqual((project / 'src/main/kotlin/nested/Helper.kt').read_text(), 'val helper = 1')
        self.assertFalse((project / 'src/main/kotlin/Old.kt').exists())
        self.assertFalse((project / '.idea').exists())
        self.assertEqual((project / 'settings.gradle.kts').read_text().count(str(repo)), 2)
        self.assertTrue((self.template / 'src/main/kotlin/Old.kt').exists())

    def test_rejects_source_outside_project(self):
        with self.assertRaises(ValueError):
            prepare_imported_project(self.template, self.root / 'project', self.root / 'repo',
                                     {'../escape.kt': 'bad'})
        self.assertFalse((self.root / 'project/src/main/escape.kt').exists())

    def test_rejects_unexpected_template(self):
        (self.template / 'settings.gradle.kts').write_text('changed')
        with self.assertRaises(ValueError):
            prepare_imported_project(self.template, self.root / 'project', self.root / 'repo', {})


if __name__ == '__main__':
    unittest.main()
