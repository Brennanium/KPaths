import hashlib
import importlib.util
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

SPEC = importlib.util.spec_from_file_location("assemble_kefs", Path(__file__).with_name("assemble-kefs-maven-repository.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def make_repository(root, version, content="valid"):
    base = root / MODULE.ARTIFACT_PATH
    directory = base / version
    directory.mkdir(parents=True)
    jar = directory / f"{MODULE.VERIFIER.ARTIFACT}-{version}.jar"
    with ZipFile(jar, "w") as archive:
        archive.writestr("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar", "dev.kpaths.compiler.PathsPluginRegistrar\n")
        archive.writestr("payload", content)
    Path(f"{jar}.md5").write_text(hashlib.md5(jar.read_bytes(), usedforsecurity=False).hexdigest())
    (directory / f"{MODULE.VERIFIER.ARTIFACT}-{version}.pom").write_text(f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>{MODULE.VERIFIER.GROUP}</groupId><artifactId>{MODULE.VERIFIER.ARTIFACT}</artifactId><version>{version}</version></project>')
    (base / "maven-metadata.xml").write_text(f'<metadata><groupId>{MODULE.VERIFIER.GROUP}</groupId><artifactId>{MODULE.VERIFIER.ARTIFACT}</artifactId><versioning><versions><version>{version}</version></versions></versioning></metadata>')


class AssembleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.old, self.new, self.out = [self.root / name for name in ("old", "new", "out")]
        self.v1 = "2.4.20-ij262-52-0.2.0"
        self.v2 = "2.4.21-ij263-1-0.3.0"
        make_repository(self.old, self.v1)
        make_repository(self.new, self.v2)

    def test_merges_versions_and_retains_checksums(self):
        MODULE.assemble([self.old, self.new], self.out)
        self.assertEqual(set(MODULE.versions(self.out)), {self.v1, self.v2})
        base = self.out / MODULE.ARTIFACT_PATH
        self.assertTrue((base / "maven-metadata.xml.md5").is_file())
        self.assertEqual(list((base / self.v1).glob("*.jar.md5"))[0].read_bytes(), list((self.old / MODULE.ARTIFACT_PATH / self.v1).glob("*.jar.md5"))[0].read_bytes())

    def test_conflicting_existing_version_fails_without_output(self):
        conflicting = self.root / "conflicting"
        make_repository(conflicting, self.v1, "different")
        with self.assertRaisesRegex(ValueError, "Conflicting immutable"):
            MODULE.assemble([self.old, conflicting], self.out)
        self.assertFalse(self.out.exists())

    def test_missing_checksum_does_not_publish_partial_repository(self):
        next((self.new / MODULE.ARTIFACT_PATH).rglob("*.jar.md5")).unlink()
        with self.assertRaises(FileNotFoundError):
            MODULE.assemble([self.old, self.new], self.out)
        self.assertFalse(self.out.exists())

    def test_metadata_must_cover_all_directories(self):
        (self.old / MODULE.ARTIFACT_PATH / "unlisted").mkdir()
        with self.assertRaisesRegex(ValueError, "directories differ"):
            MODULE.assemble([self.old], self.out)

    def test_duplicate_identical_is_allowed(self):
        MODULE.assemble([self.old, self.old], self.out)
        self.assertEqual(MODULE.versions(self.out), [self.v1])

    def test_explicit_replacement_uses_last_input(self):
        replacement = self.root / "replacement"
        make_repository(replacement, self.v1, "new contents")
        MODULE.assemble([self.old, replacement], self.out, replace_versions=[self.v1])
        original = next((replacement / MODULE.ARTIFACT_PATH / self.v1).glob("*.jar"))
        result = next((self.out / MODULE.ARTIFACT_PATH / self.v1).glob("*.jar"))
        self.assertEqual(original.read_bytes(), result.read_bytes())

    def test_delete_version_and_empty_repository(self):
        MODULE.assemble([self.old], self.out, delete_versions=[self.v1])
        self.assertEqual(MODULE.versions(self.out), [])
        self.assertFalse((self.out / MODULE.ARTIFACT_PATH / self.v1).exists())

    def test_delete_optional_file_but_reject_essential_or_escaping_paths(self):
        directory = self.old / MODULE.ARTIFACT_PATH / self.v1
        (directory / "notes.txt").write_text("temporary")
        optional = str(MODULE.ARTIFACT_PATH / self.v1 / "notes.txt")
        MODULE.assemble([self.old], self.out, delete_files=[optional])
        self.assertFalse((self.out / optional).exists())
        for path in (str(MODULE.ARTIFACT_PATH / self.v1 / next(directory.glob("*.jar.md5")).name), "../../outside"):
            with self.assertRaises(ValueError):
                MODULE.assemble([self.old], self.root / "bad", delete_files=[path])
            self.assertFalse((self.root / "bad").exists())

    def test_replace_requires_existing_and_incoming_versions(self):
        with self.assertRaisesRegex(ValueError, "both existing and incoming"):
            MODULE.assemble([self.old], self.out, replace_versions=[self.v1])
        self.assertFalse(self.out.exists())

    def test_selection_does_not_publish_unselected_versions(self):
        MODULE.assemble([self.old, self.new], self.out, select_versions=[self.v2])
        self.assertEqual(MODULE.versions(self.out), [self.v2])

    def test_broken_version_can_be_deleted_or_replaced(self):
        next((self.old / MODULE.ARTIFACT_PATH).rglob("*.jar.md5")).unlink()
        MODULE.assemble([self.old], self.out, delete_versions=[self.v1])
        self.assertEqual(MODULE.versions(self.out), [])
        replacement = self.root / "replacement"
        make_repository(replacement, self.v1, "repaired")
        repaired = self.root / "repaired"
        MODULE.assemble([self.old, replacement], repaired, replace_versions=[self.v1])
        self.assertEqual(MODULE.versions(repaired), [self.v1])

    def test_download_artifact_single_and_multiple_layouts(self):
        script = Path(__file__).with_name("assemble-kefs-artifacts.sh")
        # Execute the actual workflow helper from the repository root.
        single = subprocess.run(["bash", str(script), str(self.old), str(self.out), str(self.root / "absent")], capture_output=True, text=True)
        self.assertEqual(single.returncode, 0, single.stdout + single.stderr)
        self.assertEqual(MODULE.versions(self.out), [self.v1])
        downloads = self.root / "downloads"
        downloads.mkdir()
        shutil.copytree(self.old, downloads / "kefs-maven-one")
        shutil.copytree(self.new, downloads / "kefs-maven-two")
        merged = self.root / "merged"
        multi = subprocess.run(["bash", str(script), str(downloads), str(merged), str(self.root / "absent")], capture_output=True, text=True)
        self.assertEqual(multi.returncode, 0, multi.stdout + multi.stderr)
        self.assertEqual(set(MODULE.versions(merged)), {self.v1, self.v2})


if __name__ == "__main__":
    unittest.main()
