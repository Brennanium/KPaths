#!/usr/bin/env python3
"""Check that a KEFS IDE artifact is usable as a remote Maven repository."""
import argparse
import hashlib
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import BadZipFile, ZipFile

GROUP = "io.github.brennanium"
ARTIFACT = "kpaths-compiler-plugin-jvm"


def verify(repository: Path, ide_version: str, library_version: str) -> None:
    for value in (ide_version, library_version):
        if not re.fullmatch(r"[A-Za-z0-9]+(?:[.\-][A-Za-z0-9]+)*", value):
            raise ValueError(f"Invalid Maven version: {value!r}")
    version = f"{ide_version}-{library_version}"
    artifact_dir = repository / Path(*GROUP.split(".")) / ARTIFACT
    metadata = ET.parse(artifact_dir / "maven-metadata.xml").getroot()
    if metadata.findtext("groupId") != GROUP or metadata.findtext("artifactId") != ARTIFACT:
        raise ValueError("Maven metadata has incorrect artifact coordinates")
    if version not in [element.text for element in metadata.findall("./versioning/versions/version")]:
        raise ValueError(f"Maven metadata does not list {version}")
    directory = artifact_dir / version
    jar = directory / f"{ARTIFACT}-{version}.jar"
    checksum_file = Path(f"{jar}.md5")
    expected = checksum_file.read_text().strip().lower()
    actual = hashlib.md5(jar.read_bytes(), usedforsecurity=False).hexdigest()
    if not re.fullmatch(r"[0-9a-f]{32}", expected) or expected != actual:
        raise ValueError(f"Missing, malformed, or incorrect MD5 checksum for {jar.name}")
    with ZipFile(jar) as archive:
        if archive.testzip() is not None:
            raise ValueError(f"Corrupt JAR: {jar}")
        registrar = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
        if "dev.kpaths.compiler.PathsPluginRegistrar" not in archive.read(registrar).decode():
            raise ValueError("JAR does not register the KPaths compiler plugin")
    pom = ET.parse(directory / f"{ARTIFACT}-{version}.pom").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    if (pom.findtext("m:groupId", namespaces=namespace) != GROUP or
            pom.findtext("m:artifactId", namespaces=namespace) != ARTIFACT or
            pom.findtext("m:version", namespaces=namespace) != version):
        raise ValueError("POM does not match the expected artifact coordinates")
    print(f"Verified KEFS Maven artifact and MD5: {GROUP}:{ARTIFACT}:{version}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--ide-version", required=True)
    parser.add_argument("--library-version", required=True)
    args = parser.parse_args()
    try:
        verify(args.repository, args.ide_version, args.library_version)
    except (OSError, ValueError, KeyError, UnicodeError, ET.ParseError, BadZipFile) as error:
        sys.exit(f"Invalid KEFS Maven repository: {error}")
