#!/usr/bin/env python3
"""Test an IDE-compatible KPaths compiler artifact and publish it to a local repository."""
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MATRIX = ROOT / "kpaths-kefs-ide-fixture/ide-compatibility.json"


def trust_fixture_project(ide: Path, fixture: Path) -> None:
    """Trust only this test project in the Gradle-created, isolated IDE configuration."""
    candidates = (ide / "Contents/Resources/product-info.json", ide / "product-info.json")
    info_path = next((path for path in candidates if path.is_file()), None)
    if info_path is None:
        raise ValueError(f"No product-info.json found in {ide}")
    info = json.loads(info_path.read_text())
    code, build = info["productCode"], info["buildNumber"]
    config = fixture / ".intellijPlatform/sandbox/kpaths-kefs-ide-fixture" / f"{code}-{code}-{build}" / "config_runIde/options/trusted-paths.xml"
    config.parent.mkdir(parents=True, exist_ok=True)
    project = ET.parse(config).getroot() if config.exists() else ET.Element("application")
    component = project.find("component[@name='Trusted.Paths']")
    if component is None:
        component = ET.SubElement(project, "component", name="Trusted.Paths")
    option = component.find("option[@name='TRUSTED_PROJECT_PATHS']")
    if option is None:
        option = ET.SubElement(component, "option", name="TRUSTED_PROJECT_PATHS")
    mapping = option.find("map")
    if mapping is None:
        mapping = ET.SubElement(option, "map")
    path = str(fixture / "consumer")
    entry = next((item for item in mapping.findall("entry") if item.get("key") == path), None)
    if entry is None:
        entry = ET.SubElement(mapping, "entry", key=path)
    entry.set("value", "true")
    ET.indent(project, space="  ")
    ET.ElementTree(project).write(config, encoding="unicode", xml_declaration=True)


def stop_fixture_ides(report: Path, mode: str) -> None:
    """Stop only orphaned IDEs launched with this fixture's explicit probe properties."""
    result = subprocess.run(["ps", "-axo", "pid=,command="], capture_output=True, text=True, check=True)
    required = (f"-Dkpaths.consumerProbe={report}", f"-Dkpaths.consumerExpected={mode}",
                "-Didea.plugin.in.sandbox.mode=true")
    for line in result.stdout.splitlines():
        fields = line.strip().split(None, 1)
        if len(fields) != 2 or not fields[0].isdigit() or not all(value in fields[1] for value in required):
            continue
        pid = int(fields[0])
        try:
            os.kill(pid, signal.SIGTERM)
            for _ in range(30):
                time.sleep(0.2)
                try:
                    os.kill(pid, 0)
                except ProcessLookupError:
                    break
            else:
                os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def startup_failure(log: Path) -> bool:
    """Android Studio can leave its launcher alive behind a fatal startup dialog."""
    if not log.exists():
        return False
    with log.open('rb') as source:
        source.seek(0, os.SEEK_END)
        size = source.tell()
        source.seek(max(0, size - 128 * 1024))
        return b'**Start Failed**' in source.read()


def verify_imported_consumer(ide: Path, kefs: str) -> None:
    fixture = ROOT / "kpaths-kefs-ide-fixture"
    settings = fixture / "consumer/.idea/kotlin-plugins.xml"
    previous = settings.read_bytes() if settings.exists() else None
    report = fixture / "build/consumer-probe.txt"
    try:
        trust_fixture_project(ide, fixture)
        for mode in ("enabled", "disabled"):
            subprocess.run([sys.executable, str(fixture / "configure-consumer.py"), mode], cwd=ROOT, check=True)
            report.unlink(missing_ok=True)
            log = fixture / f"build/consumer-probe-{mode}.log"
            log.parent.mkdir(parents=True, exist_ok=True)
            command = [
                str(ROOT / "gradlew"), "--no-daemon", "-p", str(fixture), "runIde",
                f"--args={fixture / 'consumer'}", f"-Pkpaths.idePath={ide}", f"-Pkpaths.kefsVersion={kefs}", f"-Pkpaths.consumerExpected={mode}",
            ]
            with log.open("w") as output:
                process = subprocess.Popen(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
                try:
                    # Includes Gradle startup, cold IDE import/indexing, and probe shutdown.
                    deadline = time.monotonic() + 720
                    while time.monotonic() < deadline:
                        if report.exists():
                            result = report.read_text()
                            if result.startswith(f"PASS ({mode})"):
                                try:
                                    exit_code = process.wait(timeout=45)
                                except subprocess.TimeoutExpired:
                                    raise RuntimeError(f"IDE passed but did not exit; see {log}")
                                if exit_code != 0:
                                    raise RuntimeError(f"IDE exited with status {exit_code} after passing; see {log}")
                                print(f"Imported consumer: PASS ({mode})", flush=True)
                                break
                            if result.startswith("TIMEOUT"):
                                raise RuntimeError(f"IDE consumer probe timed out; see {log} and {report}")
                        if process.poll() is not None:
                            raise RuntimeError(f"IDE consumer probe exited early; see {log}")
                        if startup_failure(log):
                            raise RuntimeError(f"Android Studio reported a fatal startup error; see {log}")
                        time.sleep(2)
                    else:
                        raise RuntimeError(f"IDE consumer probe timed out; see {log} and {report}")
                finally:
                    if process.poll() is None:
                        try:
                            os.killpg(process.pid, signal.SIGTERM)
                        except ProcessLookupError:
                            pass
                        try:
                            process.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            try:
                                os.killpg(process.pid, signal.SIGKILL)
                            except ProcessLookupError:
                                pass
                            process.wait()
                    stop_fixture_ides(report, mode)
    finally:
        if previous is None:
            settings.unlink(missing_ok=True)
        else:
            settings.write_bytes(previous)


def select_ide(ide_path: Path, matrix: Path, requested_version: str | None, requested_kefs: str | None, allow_untested: bool = False) -> tuple[str, str, str]:
    candidates = [ide_path / "Contents/Resources/product-info.json", ide_path / "product-info.json"]
    info_path = next((path for path in candidates if path.is_file()), None)
    if info_path is None:
        raise ValueError(f"No product-info.json found in {ide_path}")
    info = json.loads(info_path.read_text())
    product, build = info["name"], info["buildNumber"]
    entries = json.loads(matrix.read_text())["tested"]
    matching = [entry for entry in entries if entry["product"] == product and entry["build"] == build]
    if len(matching) > 1:
        raise ValueError(f"Duplicate IDE matrix entry for {product} {build}")
    if matching:
        recorded = matching[0]["kotlinIdeVersion"]
        kefs = matching[0]["kefsVersion"]
        if allow_untested and (requested_version and requested_version != recorded or requested_kefs and requested_kefs != kefs):
            return f"{product} {build} (untested KEFS/compiler combination)", requested_version or recorded, requested_kefs or kefs
        if requested_version and requested_version != recorded:
            raise ValueError(f"The matrix specifies IDE compiler {recorded}, not {requested_version}")
        if requested_kefs and requested_kefs != kefs:
            raise ValueError(f"The matrix specifies KEFS {kefs}, not {requested_kefs}")
        return f"{product} {build}", recorded, kefs
    if not requested_version or not requested_kefs:
        raise ValueError(
            f"{product} {build} is not in the tested IDE matrix. "
            "Pass --ide-kotlin-version and --kefs-version to test it."
        )
    return f"{product} {build} (not yet in the tested matrix)", requested_version, requested_kefs


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ide-path", required=True, type=Path, help="Path to the installed IDE")
    parser.add_argument("--ide-kotlin-version", help="KEFS compiler version for an unlisted IDE")
    parser.add_argument("--kefs-version", help="KEFS plugin version for an unlisted IDE")
    parser.add_argument("--ide-probe", action="store_true", help="Also launch an isolated IDE twice to check the imported consumer")
    parser.add_argument("--allow-untested-combination", action="store_true", help="Allow a known IDE with different explicitly selected KEFS/compiler versions")
    args = parser.parse_args()
    ide = args.ide_path.expanduser().resolve()
    try:
        label, version, kefs = select_ide(ide, MATRIX, args.ide_kotlin_version, args.kefs_version, args.allow_untested_combination)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        parser.error(str(error))
    print(f"Verifying {label} with KEFS {kefs} and IDE compiler {version}", flush=True)
    wrapper = str(ROOT / "gradlew")
    subprocess.run([
        wrapper, "--no-configuration-cache", "-I", "gradle/local-test-repository.init.gradle",
        ":kpaths-gradle-plugin:publishAllPublicationsToConsumerVerificationRepository",
        ":kpaths-compiler-plugin:publishJvmPublicationToConsumerVerificationRepository",
        ":kpaths-core:publishKotlinMultiplatformPublicationToConsumerVerificationRepository",
        ":kpaths-core:publishJvmPublicationToConsumerVerificationRepository",
        ":kpaths-annotations:publishKotlinMultiplatformPublicationToConsumerVerificationRepository",
        ":kpaths-annotations:publishJvmPublicationToConsumerVerificationRepository",
        ":kpaths-compiler-plugin:jvmJar", ":kpaths-compiler-plugin-contributor:jvmJar",
        ":kpaths-core:jvmJar", ":kpaths-annotations:jvmJar",
    ], cwd=ROOT, check=True)
    subprocess.run([
        wrapper, "-p", "kpaths-kefs-ide-fixture",
        "publishIdeCompilerPluginPublicationToKefsLocalRepository",
        f"-Pkpaths.idePath={ide}", f"-Pkpaths.ideKotlinVersion={version}", f"-Pkpaths.kefsVersion={kefs}",
    ], cwd=ROOT, check=True)
    subprocess.run([
        wrapper, "-p", "kpaths-kefs-ide-fixture/consumer", "build",
    ], cwd=ROOT, check=True)
    repository = ROOT / "kpaths-kefs-ide-fixture/build/kefs-repository"
    print(f"Verified IDE artifact published locally to {repository}")
    print("Verified the standalone Gradle consumer build and KEFS highlighting in the IDE test fixture.")
    if args.ide_probe:
        verify_imported_consumer(ide, kefs)
    else:
        print("Pass --ide-probe to verify resolution in the imported consumer IDE.")
    print("No release or user IDE was configured.")


if __name__ == "__main__":
    main()
