import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("prepare-kefs-ci-version.py")
SPEC = importlib.util.spec_from_file_location("prepare_kefs_ci_version", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PrepareVersionTest(unittest.TestCase):
    def test_rewrites_consumer_and_rejects_invalid_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            consumer = root / "kpaths-kefs-ide-fixture/consumer/build.gradle.kts"
            consumer.parent.mkdir(parents=True)
            props = root / "gradle.properties"
            props.write_text("VERSION_NAME=0.1.0\n")
            consumer.write_text('''plugins {
  id("dev.kpaths.compiler") version "0.1.0"
}
dependencies {
  implementation("io.github.brennanium:kpaths-core:0.1.0")
  implementation("io.github.brennanium:kpaths-annotations:0.1.0")
}
''')
            MODULE.prepare(root, "0.2.0-ci.1")
            self.assertEqual(props.read_text(), "VERSION_NAME=0.2.0-ci.1\n")
            self.assertIn('id("dev.kpaths.compiler") version "0.2.0-ci.1"', consumer.read_text())
            self.assertEqual(consumer.read_text().count("0.2.0-ci.1"), 3)
            before = props.read_bytes(), consumer.read_bytes()
            with self.assertRaises(ValueError):
                MODULE.prepare(root, "../invalid")
            self.assertEqual(before, (props.read_bytes(), consumer.read_bytes()))
            consumer.write_text("unexpected")
            with self.assertRaises(ValueError):
                MODULE.prepare(root, "0.2.0")
            self.assertEqual(before[0], props.read_bytes())


if __name__ == "__main__":
    unittest.main()
