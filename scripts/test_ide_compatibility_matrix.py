import unittest
from scripts.ide_compatibility_matrix import select


class IdeCompatibilityMatrixTest(unittest.TestCase):
    entries = [
        {'product': 'Android Studio', 'build': '261.1.2.3.4'},
        {'product': 'Android Studio', 'build': '261.1.3.2.1'},
        {'product': 'Android Studio', 'build': '262.3.2.1.1'},
    ]

    def test_pr_tests_latest_per_platform_branch(self):
        self.assertEqual(select(self.entries, 'pull_request', ''), ['261.1.3.2.1', '262.3.2.1.1'])

    def test_main_runs_full_matrix(self):
        self.assertEqual(select(self.entries, 'push', ''), [e['build'] for e in self.entries])

    def test_manual_versions_override_matrix(self):
        self.assertEqual(select([], 'workflow_dispatch', '2026.2.2.8, latest-stable'), ['2026.2.2.8', 'latest-stable'])

    def test_invalid_and_duplicate_versions_fail(self):
        for versions in ['invalid', '2026.2.2.8;echo', 'latest-stable latest-stable']:
            with self.assertRaises(ValueError):
                select(self.entries, 'workflow_dispatch', versions)
        with self.assertRaises(ValueError):
            select([], 'push', '')
        with self.assertRaises(ValueError):
            select(self.entries + [self.entries[0]], 'push', '')


if __name__ == '__main__':
    unittest.main()
