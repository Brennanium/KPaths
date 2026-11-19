import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class KnapshotsEditorDriverTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        shutil.copy2(Path(__file__).with_name("knapshots-editor-driver.sh"), self.root / "scripts")
        gradle = self.root / "gradlew"
        gradle.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> gradle-calls.txt\n")
        gradle.chmod(0o755)
        tools = self.root / "tools"
        tools.mkdir()
        python = tools / "python3"
        python.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> python-calls.txt\n")
        python.chmod(0o755)
        self.checkout = self.root / "knapshots"
        output = self.checkout / "knapshots-intellij/build/editor-adapter-path.txt"
        output.parent.mkdir(parents=True)
        output.write_text(str(self.root / "adapter.zip") + "\n")

    def test_prepares_the_plugin_then_runs_all_editor_scenarios(self):
        result = subprocess.run(
            [str(self.root / "scripts/knapshots-editor-driver.sh"), "--ide-path", "/test/IDE",
             "--ide-kotlin-version", "2.4.20-ij262-52", "--kefs-version", "0.5.0-262"],
            cwd=self.root, env={**os.environ, "PATH": str(self.root / "tools") + os.pathsep + os.environ["PATH"],
                                "KPATHS_PYTHON": str(self.root / "tools/python3"),
                                "KNAPSHOTS_CHECKOUT": str(self.checkout)}, text=True, capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = (self.root / "gradle-calls.txt").read_text()
        self.assertIn(":knapshots-intellij:writeEditorAdapterPath", calls)
        adapter_call = next(line for line in calls.splitlines() if ":knapshots-intellij:writeEditorAdapterPath" in line)
        self.assertIn("-Dorg.gradle.jvmargs=-Xmx2g", adapter_call)
        self.assertIn("-Dorg.gradle.workers.max=2", adapter_call)
        for mode in ("enabled", "disabled", "diagnostic"):
            self.assertIn(f"-Pkpaths.editorMode={mode}", calls)
        self.assertNotIn("--rerun-tasks", calls)
        self.assertIn("--ide-kotlin-version 2.4.20-ij262-52", (self.root / "python-calls.txt").read_text())

    def test_missing_ide_configuration_fails(self):
        result = subprocess.run([str(self.root / "scripts/knapshots-editor-driver.sh")],
                                cwd=self.root, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("IDE, IDE Kotlin, and KEFS versions are required", result.stderr)


if __name__ == "__main__":
    unittest.main()
