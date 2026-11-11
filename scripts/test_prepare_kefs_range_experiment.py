import importlib.util
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("kefs_range", Path(__file__).with_name("prepare-kefs-range-experiment.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RangeExperimentTests(unittest.TestCase):
    def test_only_the_manifest_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.jar"
            output = Path(directory) / "output.jar"
            with ZipFile(source, "w") as jar:
                jar.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\n\r\n")
                jar.writestr("example/Plugin.class", b"class bytes")
            module.create_candidate(source, output, "261", "262.*")
            with ZipFile(source) as original, ZipFile(output) as candidate:
                self.assertEqual(original.read("example/Plugin.class"), candidate.read("example/Plugin.class"))
                self.assertIn(b"Kotlin-Compiler-Plugin-Since-Build: 261", candidate.read("META-INF/MANIFEST.MF"))
                self.assertIn(b"Kotlin-Compiler-Plugin-Until-Build: 262.*", candidate.read("META-INF/MANIFEST.MF"))
            with self.assertRaises(ValueError):
                module.create_candidate(source, output, "261", "262.*")

    def test_rejects_a_signed_jar_without_creating_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.jar"
            output = Path(directory) / "output.jar"
            with ZipFile(source, "w") as jar:
                jar.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\n\r\n")
                jar.writestr("META-INF/SIGNATURE.SF", b"signature")
            with self.assertRaises(ValueError):
                module.create_candidate(source, output, "261", "262.*")
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
