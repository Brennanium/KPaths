import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("fetch-kefs-maven.sh").resolve()


class FetchMavenTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.remote = self.root / "remote.git"
        self.repo = self.root / "work"
        self.dest = self.root / "export"
        self.run_git("init", "--bare", str(self.remote), cwd=self.root)
        self.run_git("init", str(self.repo), cwd=self.root)
        self.run_git("remote", "add", "origin", str(self.remote))

    def run_git(self, *args, cwd=None):
        subprocess.run(["git", *args], cwd=cwd or self.repo, check=True, capture_output=True)

    def fetch(self):
        return subprocess.run(["bash", str(SCRIPT), str(self.dest)], cwd=self.repo, capture_output=True, text=True)

    def test_absent_branch_is_allowed(self):
        self.assertEqual(self.fetch().returncode, 0)
        self.assertFalse(self.dest.exists())

    def test_existing_branch_is_exported(self):
        (self.repo / "marker").write_text("existing repository")
        self.run_git("add", "marker")
        self.run_git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "test")
        self.run_git("push", "origin", "HEAD:refs/heads/kpaths-kefs-maven")
        result = self.fetch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.dest / "marker").read_text(), "existing repository")

    def test_remote_failure_does_not_look_like_missing_branch(self):
        self.run_git("remote", "set-url", "origin", str(self.root / "does-not-exist"))
        self.assertNotEqual(self.fetch().returncode, 0)
        self.assertFalse(self.dest.exists())


if __name__ == "__main__":
    unittest.main()
