import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class KnapshotsCompilerDriverTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        source = Path(__file__).with_name("knapshots-compiler-driver.sh")
        shutil.copy2(source, self.root / "scripts/knapshots-compiler-driver.sh")
        gradle = self.root / "gradlew"
        gradle.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> calls.txt\ncase \"$*\" in *\"$FAIL_TASK\"*) [ -z \"$FAIL_TASK\" ] || exit 1;; esac\n")
        gradle.chmod(0o755)

    def run_driver(self, *args, fail_task=""):
        return subprocess.run(
            [str(self.root / "scripts/knapshots-compiler-driver.sh"), "--kotlin-version", "2.4.21", *args],
            cwd=self.root, env={**os.environ, "FAIL_TASK": fail_task}, text=True, capture_output=True,
        )

    def test_jvm_runs_regressions_and_consumer(self):
        result = self.run_driver("--platform", "jvm")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("KNAPSHOTS_CHECK jvm passed", result.stdout)
        self.assertIn("KNAPSHOTS_CHECK jvm-consumer passed", result.stdout)
        self.assertIn("KNAPSHOTS_CHECK native not-run", result.stdout)
        self.assertNotIn("iosSimulatorArm64Test", (self.root / "calls.txt").read_text())

    def test_native_can_run_alongside_jvm(self):
        result = self.run_driver("--platform", "jvm,native")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("KNAPSHOTS_CHECK native passed", result.stdout)
        self.assertIn("iosSimulatorArm64Test", (self.root / "calls.txt").read_text())

    def test_failed_artifact_preparation_does_not_hide_failure(self):
        result = self.run_driver("--platform", "jvm", fail_task="publishAllPublications")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("KNAPSHOTS_CHECK artifacts failed", result.stdout)
        self.assertIn("KNAPSHOTS_CHECK jvm-consumer not-run", result.stdout)

    def test_unknown_platform_is_rejected(self):
        result = self.run_driver("--platform", "jvm,unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unsupported platform: unknown", result.stderr)


if __name__ == "__main__":
    unittest.main()
