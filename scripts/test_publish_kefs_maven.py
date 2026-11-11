import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.test_assemble_kefs_maven_repository import make_repository

SCRIPTS = Path(__file__).parent


class PublishMavenTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.remote = self.root / "remote.git"
        self.repo = self.root / "work"
        self.run_git("init", "--bare", str(self.remote), cwd=self.root)
        self.run_git("init", str(self.repo), cwd=self.root)
        self.run_git("remote", "add", "origin", str(self.remote))
        (self.repo / "scripts").mkdir()
        for name in ("publish-kefs-maven.sh", "fetch-kefs-maven.sh", "assemble-kefs-maven-repository.py", "verify-kefs-maven-repository.py"):
            shutil.copy(SCRIPTS / name, self.repo / "scripts" / name)
        self.run_git("add", "scripts")
        self.run_git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "test")

    def run_git(self, *args, cwd=None):
        return subprocess.run(["git", *args], cwd=cwd or self.repo, check=True, capture_output=True, text=True)

    def publish(self, path, *options):
        return subprocess.run(["bash", "scripts/publish-kefs-maven.sh", str(path), *options], cwd=self.repo, capture_output=True, text=True, env={**os.environ, "RUNNER_TEMP": str(self.root)})

    def test_initial_publish_update_rerun_and_immutable_conflict(self):
        first, second, conflicting = (self.root / name for name in ("first", "second", "conflicting"))
        v1, v2 = "2.4.20-ij262-52-0.2.0", "2.4.21-ij263-1-0.3.0"
        make_repository(first, v1)
        make_repository(second, v2)
        make_repository(conflicting, v1, "different")
        for incoming in (first, second, second):
            result = self.publish(incoming)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        ref = "refs/heads/kpaths-kefs-maven"
        metadata = self.run_git("--git-dir", str(self.remote), "show", f"{ref}:io/github/brennanium/kpaths-compiler-plugin-jvm/maven-metadata.xml").stdout
        self.assertIn(v1, metadata)
        self.assertIn(v2, metadata)
        before = self.run_git("--git-dir", str(self.remote), "rev-parse", ref).stdout
        result = self.publish(conflicting)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Conflicting immutable", result.stderr)
        self.assertEqual(before, self.run_git("--git-dir", str(self.remote), "rev-parse", ref).stdout)
        result = self.publish(conflicting, "--replace-version", v1)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        result = self.publish("-", "--delete-version", v2)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        metadata = self.run_git("--git-dir", str(self.remote), "show", f"{ref}:io/github/brennanium/kpaths-compiler-plugin-jvm/maven-metadata.xml").stdout
        self.assertIn(v1, metadata)
        self.assertNotIn(v2, metadata)
        files = self.run_git("--git-dir", str(self.remote), "ls-tree", "-r", "--name-only", ref).stdout
        self.assertNotIn(v2, files)

    def test_delete_individual_optional_file(self):
        version = "2.4.20-ij262-52-0.2.0"
        repository = self.root / "first"
        make_repository(repository, version)
        relative = f"io/github/brennanium/kpaths-compiler-plugin-jvm/{version}/optional.txt"
        (repository / relative).write_text("remove me")
        result = self.publish(repository)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        result = self.publish("-", "--delete-file", relative)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        files = self.run_git("--git-dir", str(self.remote), "ls-tree", "-r", "--name-only", "refs/heads/kpaths-kefs-maven").stdout
        self.assertNotIn(relative, files)
        self.assertIn(f"{version}.jar", files)


if __name__ == "__main__":
    unittest.main()
