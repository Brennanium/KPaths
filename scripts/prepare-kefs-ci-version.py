#!/usr/bin/env python3
"""Set a temporary KPaths version in a disposable CI checkout."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def prepare(root: Path, version: str) -> None:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", version):
        raise ValueError("Expected a version such as 0.2.0-ci.1")
    properties = root / "gradle.properties"
    consumer = root / "kpaths-kefs-ide-fixture/consumer/build.gradle.kts"
    props_text = properties.read_text()
    match = re.findall(r"(?m)^VERSION_NAME=([^\r\n]+)$", props_text)
    if len(match) != 1:
        raise ValueError("Expected exactly one VERSION_NAME in gradle.properties")
    previous = match[0]
    consumer_text = consumer.read_text()
    old = [f'id("dev.kpaths.compiler") version "{previous}"',
           f'io.github.brennanium:kpaths-core:{previous}',
           f'io.github.brennanium:kpaths-annotations:{previous}']
    if any(consumer_text.count(value) != 1 for value in old):
        raise ValueError("Consumer dependencies do not match VERSION_NAME")
    new_consumer = consumer_text
    for value in old:
        new_consumer = new_consumer.replace(value, value.replace(previous, version))
    new_props = props_text.replace(f"VERSION_NAME={previous}", f"VERSION_NAME={version}")
    properties.write_text(new_props)
    consumer.write_text(new_consumer)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: prepare-kefs-ci-version.py VERSION (in a disposable checkout)")
    try:
        prepare(ROOT, sys.argv[1])
    except ValueError as error:
        raise SystemExit(str(error)) from error
