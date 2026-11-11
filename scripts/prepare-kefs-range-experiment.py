#!/usr/bin/env python3
"""Create a test-only JAR for investigating KEFS's internal DevKit detection."""
from __future__ import annotations

import argparse
from pathlib import Path
from zipfile import ZipFile


def create_candidate(source: Path, destination: Path, since: str, until: str) -> None:
    if source.resolve() == destination.resolve() or destination.exists():
        raise ValueError("The output must be a new file, different from the source JAR")
    for value in (since, until):
        if not value or not all(c.isdigit() or c in ".*" for c in value):
            raise ValueError("IDE build bounds must contain only numbers, dots, or *")
    with ZipFile(source) as original:
        names = original.namelist()
        if len(names) != len(set(names)) or "META-INF/MANIFEST.MF" not in names:
            raise ValueError("The JAR must have a single manifest and no duplicate entries")
        if any(name.upper().startswith("META-INF/") and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC")) for name in names):
            raise ValueError("Signed JARs cannot be modified by this experiment")
        manifest = original.read("META-INF/MANIFEST.MF")
        if b"Kotlin-Compiler-Plugin-Since-Build:" in manifest or b"Kotlin-Compiler-Plugin-Until-Build:" in manifest:
            raise ValueError("The source JAR already declares DevKit compatibility")
        if b"\n\n" in manifest.rstrip(b"\r\n").replace(b"\r\n", b"\n"):
            raise ValueError("Multi-section manifests are not supported by this experiment")
        # Preserve the existing manifest attributes and insert before its final empty line.
        manifest = manifest.rstrip(b"\r\n") + (
            f"\r\nKotlin-Compiler-Plugin-Since-Build: {since}"
            f"\r\nKotlin-Compiler-Plugin-Until-Build: {until}\r\n\r\n"
        ).encode("ascii")
        destination.parent.mkdir(parents=True, exist_ok=True)
        with ZipFile(destination, "x") as output:
            for entry in original.infolist():
                output.writestr(entry, manifest if entry.filename == "META-INF/MANIFEST.MF" else original.read(entry))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--since", required=True)
    parser.add_argument("--until", required=True)
    args = parser.parse_args()
    try:
        create_candidate(args.source, args.destination, args.since, args.until)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print(f"Created experimental range-tagged JAR: {args.destination}")
    print("This is not a release artifact or a claim of DevKit compatibility.")


if __name__ == "__main__":
    main()
