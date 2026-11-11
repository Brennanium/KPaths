#!/usr/bin/env python3
"""Merge verified KEFS Maven repositories into a new, immutable-version repository."""
import argparse
import hashlib
import importlib.util
import os
import re
import shutil
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath

SPEC = importlib.util.spec_from_file_location("kefs_verify", Path(__file__).with_name("verify-kefs-maven-repository.py"))
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)
ARTIFACT_PATH = Path("io/github/brennanium/kpaths-compiler-plugin-jvm")


def versions(repo, skip_validation=()):
    base = repo / ARTIFACT_PATH
    root = ET.parse(base / "maven-metadata.xml").getroot()
    if root.findtext("groupId") != VERIFIER.GROUP or root.findtext("artifactId") != VERIFIER.ARTIFACT:
        raise ValueError(f"Invalid metadata coordinates in {repo}")
    names = [element.text for element in root.findall("./versioning/versions/version")]
    if any(not name or not re.fullmatch(r"[A-Za-z0-9]+(?:[.\-][A-Za-z0-9]+)*", name) for name in names) or len(names) != len(set(names)):
        raise ValueError(f"Invalid or duplicate versions in {repo}")
    actual = {path.name for path in base.iterdir() if path.is_dir()}
    if actual != set(names):
        raise ValueError(f"Metadata and version directories differ in {repo}")
    for path in base.rglob("*"):
        if path.is_symlink():
            raise ValueError(f"Symlinks are not supported: {path}")
    for name in names:
        if name not in skip_validation:
            VERIFIER.verify_version(repo, name)
    return names


def assemble(inputs, output, replace_versions=(), delete_versions=(), delete_files=(), select_versions=()):
    if not inputs:
        raise ValueError("At least one repository is required")
    if output.exists():
        raise ValueError(f"Output already exists: {output}")
    replace_versions, delete_versions = set(replace_versions), set(delete_versions)
    if replace_versions & delete_versions:
        raise ValueError("A version cannot be replaced and deleted in the same operation")
    # Inputs are ordered: the first is existing state, later inputs are candidate replacements.
    all_versions = {}
    duplicates = set()
    for repo in inputs:
        # A broken version must still be removable or replaceable. The final assembled copy
        # is always validated after explicit maintenance operations are applied.
        for name in versions(repo, delete_versions | replace_versions):
            source = repo / ARTIFACT_PATH / name
            if name in all_versions:
                duplicates.add(name)
                old = all_versions[name]
                left = {p.relative_to(old): p.read_bytes() for p in old.rglob("*") if p.is_file()}
                right = {p.relative_to(source): p.read_bytes() for p in source.rglob("*") if p.is_file()}
                if left != right and name not in replace_versions:
                    raise ValueError(f"Conflicting immutable Maven version: {name}")
                if name in replace_versions:
                    all_versions[name] = source
            else:
                all_versions[name] = source
    if replace_versions - duplicates:
        raise ValueError(f"Cannot replace versions without both existing and incoming artifacts: {sorted(replace_versions - duplicates)}")
    select_versions = set(select_versions)
    if select_versions - all_versions.keys():
        raise ValueError(f"Cannot select missing versions: {sorted(select_versions - all_versions.keys())}")
    if select_versions:
        all_versions = {name: source for name, source in all_versions.items() if name in select_versions}
    if delete_versions - all_versions.keys():
        raise ValueError(f"Cannot delete missing versions: {sorted(delete_versions - all_versions.keys())}")
    for name in delete_versions:
        del all_versions[name]
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="kefs-assembly-", dir=output.parent) as tmp:
        staging = Path(tmp) / "repository"
        base = staging / ARTIFACT_PATH
        base.mkdir(parents=True)
        for name, source in sorted(all_versions.items()):
            shutil.copytree(source, base / name)
        for value in delete_files:
            path = PurePosixPath(value)
            prefix = ARTIFACT_PATH.parts
            if path.is_absolute() or ".." in path.parts or len(path.parts) != len(prefix) + 2 or path.parts[:len(prefix)] != prefix:
                raise ValueError(f"File must be inside a Maven version directory: {value}")
            name, filename = path.parts[-2:]
            essential = {f"{VERIFIER.ARTIFACT}-{name}.jar", f"{VERIFIER.ARTIFACT}-{name}.jar.md5", f"{VERIFIER.ARTIFACT}-{name}.pom"}
            if filename in essential:
                raise ValueError(f"{filename} is required; delete its complete version with --delete-version")
            target = staging.joinpath(*path.parts)
            if not target.is_file():
                raise ValueError(f"Cannot delete missing file: {value}")
            target.unlink()
        root = ET.Element("metadata")
        ET.SubElement(root, "groupId").text = VERIFIER.GROUP
        ET.SubElement(root, "artifactId").text = VERIFIER.ARTIFACT
        versioning = ET.SubElement(root, "versioning")
        listing = ET.SubElement(versioning, "versions")
        for name in sorted(all_versions):
            ET.SubElement(listing, "version").text = name
        ET.indent(root)
        metadata = ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n"
        (base / "maven-metadata.xml").write_bytes(metadata)
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            (base / f"maven-metadata.xml.{algorithm}").write_text(hashlib.new(algorithm, metadata, usedforsecurity=False).hexdigest())
        versions(staging)
        os.replace(staging, output)
    print(f"Assembled {len(all_versions)} verified Maven version(s) in {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--replace-version", action="append", default=[], help="Explicitly replace a version with the last matching input (repeatable)")
    parser.add_argument("--delete-version", action="append", default=[], help="Remove a complete version and update metadata (repeatable)")
    parser.add_argument("--delete-file", action="append", default=[], help="Remove a nonessential file by repository-relative path (repeatable)")
    parser.add_argument("--select-version", action="append", default=[], help="Keep only selected versions (repeatable)")
    parser.add_argument("repositories", type=Path, nargs="+")
    args = parser.parse_args()
    try:
        assemble(args.repositories, args.output, args.replace_version, args.delete_version, args.delete_file, args.select_version)
    except (OSError, ValueError, KeyError, UnicodeError, ET.ParseError, VERIFIER.BadZipFile) as error:
        parser.exit(1, f"Cannot assemble KEFS Maven repository: {error}\n")
