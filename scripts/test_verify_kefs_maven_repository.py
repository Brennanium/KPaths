import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

SPEC = importlib.util.spec_from_file_location("verify_kefs_maven_repository", Path(__file__).with_name("verify-kefs-maven-repository.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MavenRepositoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.base = self.root / "io/github/brennanium/kpaths-compiler-plugin-jvm"
        version = "2.4.20-ij262-52-0.2.0-ci.1"
        directory = self.base / version
        directory.mkdir(parents=True)
        self.jar = directory / f"kpaths-compiler-plugin-jvm-{version}.jar"
        with ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar", "dev.kpaths.compiler.PathsPluginRegistrar\n")
        self.md5 = Path(f"{self.jar}.md5")
        self.md5.write_text(hashlib.md5(self.jar.read_bytes(), usedforsecurity=False).hexdigest())
        (directory / f"kpaths-compiler-plugin-jvm-{version}.pom").write_text(f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>io.github.brennanium</groupId><artifactId>kpaths-compiler-plugin-jvm</artifactId><version>{version}</version></project>')
        (self.base / "maven-metadata.xml").write_text(f'<metadata><groupId>io.github.brennanium</groupId><artifactId>kpaths-compiler-plugin-jvm</artifactId><versioning><versions><version>{version}</version></versions></versioning></metadata>')

    def verify(self):
        MODULE.verify(self.root, "2.4.20-ij262-52", "0.2.0-ci.1")

    def test_valid_repository(self):
        self.verify()

    def test_missing_or_wrong_checksum(self):
        self.md5.unlink()
        with self.assertRaises(FileNotFoundError):
            self.verify()
        self.md5.write_text("0" * 32)
        with self.assertRaises(ValueError):
            self.verify()

    def test_missing_version(self):
        with self.assertRaises(ValueError):
            MODULE.verify(self.root, "2.4.20-ij262-52", "0.2.0-other")

    def test_wrong_pom_and_bad_version(self):
        next(self.base.rglob("*.pom")).write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"/>')
        with self.assertRaises(ValueError):
            self.verify()
        with self.assertRaises(ValueError):
            MODULE.verify(self.root, "../invalid", "0.2.0-ci.1")


if __name__ == "__main__":
    unittest.main()
