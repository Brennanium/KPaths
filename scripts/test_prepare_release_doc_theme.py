import importlib.util
import tempfile
import unittest
from pathlib import Path

import yaml

spec = importlib.util.spec_from_file_location('release_theme', Path(__file__).with_name('prepare-release-doc-theme.py'))
theme = importlib.util.module_from_spec(spec)
spec.loader.exec_module(theme)


class PrepareReleaseDocThemeTest(unittest.TestCase):
    def test_uses_current_theme_with_release_content_and_navigation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, release = root / 'repo', root / 'release'
            logo = repo / 'docs/assets/images/kpaths-mark.svg'
            logo.parent.mkdir(parents=True)
            logo.write_text('current logo')
            (repo / 'mkdocs.yml').write_text(yaml.safe_dump({
                'theme': {'name': 'material', 'font': {'text': 'Inter'}},
                'nav': [{'Current': 'current.md'}],
                'extra_css': ['assets/stylesheets/kpaths.css'],
            }))
            (release / 'docs').mkdir(parents=True)
            (release / 'docs/index.md').write_text('release content')
            (release / 'mkdocs.yml').write_text(yaml.safe_dump({
                'theme': {'name': 'material'}, 'nav': [{'Release': 'index.md'}],
            }))

            config = yaml.safe_load(theme.prepare(repo, release, '0.1.0').read_text())
            self.assertEqual(config['theme']['font']['text'], 'Inter')
            self.assertEqual(config['nav'], [{'Release': 'index.md'}])
            self.assertEqual(config['edit_uri'], 'edit/0.1.0/docs/')
            self.assertEqual(config['extra_css'], [])
            staged = Path(config['docs_dir'])
            self.assertEqual((staged / 'index.md').read_text(), '---\nhide:\n  - navigation\n---\n\nrelease content')
            self.assertEqual((staged / 'assets/images/kpaths-mark.svg').read_text(), 'current logo')


if __name__ == '__main__':
    unittest.main()
