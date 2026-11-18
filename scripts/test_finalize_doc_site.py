import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('finalize_docs', Path(__file__).with_name('finalize-doc-site.py'))
docs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(docs)


class FinalizeDocSiteTest(unittest.TestCase):
    def test_injects_release_and_api_assets_and_builds_version_list_idempotently(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('assets/javascripts/site.js', 'assets/stylesheets/kpaths.css', 'assets/stylesheets/dokka.css', 'assets/images/kpaths-mark.svg'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('asset')
            page = '<!DOCTYPE html><html><head></head><body>content</body></html>'
            for name in ('index.html', 'api/index.html', 'api/deep/item.html', '0.1.0/index.html',
                         '0.1.0/api/index.html', '0.2.0/index.html'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(page)
            fragment = root / 'api/navigation.html'
            fragment.write_text('<nav>fragment</nav>')
            docs.finalize(root)
            self.assertEqual(json.loads((root / 'versions.json').read_text()), [
                {'label': 'main', 'path': ''}, {'label': '0.2.0', 'path': '0.2.0/'},
                {'label': '0.1.0', 'path': '0.1.0/'}])
            self.assertIn('../../assets/stylesheets/dokka.css', (root / 'api/deep/item.html').read_text())
            self.assertIn('../assets/stylesheets/kpaths.css', (root / '0.1.0/index.html').read_text())
            self.assertIn('../../assets/stylesheets/dokka.css', (root / '0.1.0/api/index.html').read_text())
            self.assertEqual(fragment.read_text(), '<nav>fragment</nav>')
            self.assertEqual((root / 'api/images/kpaths-mark.svg').read_text(), 'asset')
            self.assertEqual((root / '0.1.0/api/images/kpaths-mark.svg').read_text(), 'asset')
            first = (root / 'api/index.html').read_text()
            docs.finalize(root)
            self.assertEqual((root / 'api/index.html').read_text(), first)

    def test_rejects_incomplete_site(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                docs.finalize(Path(directory))


if __name__ == '__main__':
    unittest.main()
