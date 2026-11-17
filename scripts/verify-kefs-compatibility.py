#!/usr/bin/env python3
"""Test an IDE-compatible KPaths compiler artifact and publish it to a local repository."""
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from ide_sandbox import trust_project
from imported_ide_project import prepare_imported_project
from matrix_project_root import project_root

ROOT = project_root()
MATRIX = ROOT / "kpaths-kefs-ide-fixture/ide-compatibility.json"


def trust_fixture_project(fixture: Path, project: Path, run_id: str, mode: str, attempt: int) -> None:
    """Trust only this test project in the Gradle-created, isolated IDE configuration."""
    config = fixture / f"build/ide-probe/{run_id}/{mode}-{attempt}/config"
    trust_project(config, project)


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


def publish_ide_artifact(command: list[str]) -> None:
    """Retry the known IntelliJ Gradle plugin descriptor filesystem race once."""
    for attempt in range(2):
        # Use a fresh single-use daemon and invalidate failed dependency lookups on retry;
        # repeating in the original daemon retains the failed bundled-plugin resolution.
        invocation = command if attempt == 0 else [*command, "--no-daemon", "--refresh-dependencies"]
        process = subprocess.Popen(invocation, cwd=ROOT, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, bufsize=1)
        closed_fs = False
        missing_descriptor = False
        assert process.stdout is not None
        with process.stdout:
            for line in process.stdout:
                print(line, end='', flush=True)
                closed_fs |= 'ClosedFileSystemException' in line
                missing_descriptor |= 'Unable to read descriptor [plugin.xml]' in line
        result = process.wait()
        if result == 0:
            return
        if attempt == 0 and closed_fs and missing_descriptor:
            # A failed scan can persist an incomplete index. Preserve only this IDE's
            # generated index for diagnosis and let the next invocation rebuild it.
            ide_arg = next((arg.split('=', 1)[1] for arg in command if arg.startswith('-Pkpaths.idePath=')), None)
            if ide_arg:
                ide = Path(ide_arg)
                candidates = (ide / 'Contents/Resources/product-info.json', ide / 'product-info.json')
                info_path = next((path for path in candidates if path.is_file()), None)
                if info_path:
                    info = json.loads(info_path.read_text())
                    prefix = f"{info['productCode']}-{info['buildNumber']}-"
                    directory = ROOT / 'kpaths-kefs-ide-fixture/.intellijPlatform/ides/layoutIndex'
                    for index in directory.glob('*.json'):
                        if index.name.startswith(prefix):
                            saved = index.with_name(index.name + f'.failed-{time.time_ns()}')
                            index.replace(saved)
                            print(f'Preserved incomplete IDE layout index: {saved}', flush=True)
            print('IntelliJ plugin descriptor filesystem closed unexpectedly; retrying once.', flush=True)
            continue
        raise subprocess.CalledProcessError(result, invocation)


class AnalyticsStartupError(RuntimeError):
    """Android Studio's intermittent analytics initialization failure during startup."""


def run_consumer_mode(ide: Path, kefs: str, fixture: Path, project: Path, report: Path, mode: str, log: Path, attempt: int, run_id: str) -> None:
    report.unlink(missing_ok=True)
    trust_fixture_project(fixture, project, run_id, mode, attempt)
    command = [
        str(ROOT / "gradlew"), "--no-daemon", "-p", str(fixture), "runIde",
        f"--args={project}", f"-Pkpaths.consumerProject={project}", f"-Pkpaths.idePath={ide}", f"-Pkpaths.kefsVersion={kefs}", f"-Pkpaths.consumerExpected={mode}", f"-Pkpaths.consumerAttempt={attempt}", f"-Pkpaths.consumerRunId={run_id}",
    ]
    with log.open("w") as output:
        process = subprocess.Popen(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            # Includes Gradle startup, cold IDE import/indexing, and probe shutdown.
            deadline = time.monotonic() + 720
            while time.monotonic() < deadline:
                if report.exists():
                    result = report.read_text()
                    if result.startswith("ERROR:"):
                        raise RuntimeError(f"{result.strip()}; see {log}")
                    if result.startswith(f"PASS ({mode})"):
                        try:
                            exit_code = process.wait(timeout=45)
                        except subprocess.TimeoutExpired:
                            raise RuntimeError(f"IDE passed but did not exit; see {log}")
                        if exit_code != 0:
                            raise RuntimeError(f"IDE exited with status {exit_code} after passing; see {log}")
                        print(f"Imported consumer: PASS ({mode})", flush=True)
                        return
                    if result.startswith("TIMEOUT"):
                        raise RuntimeError(f"IDE consumer probe timed out; see {log} and {report}")
                if startup_failure(log):
                    if "call to AnalyticsSettings before initialization" in log.read_text(errors="replace"):
                        raise AnalyticsStartupError(f"Android Studio analytics initialization failed; see {log}")
                    raise RuntimeError(f"Android Studio reported a fatal startup error; see {log}")
                if process.poll() is not None:
                    raise RuntimeError(f"IDE consumer probe exited early; see {log}")
                time.sleep(2)
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


def verify_imported_consumer(ide: Path, kefs: str) -> None:
    fixture = ROOT / "kpaths-kefs-ide-fixture"
    report = fixture / "build/consumer-probe.txt"
    run_id = str(time.time_ns())
    # Every scenario uses a disposable imported project; no checked-in consumer or IDE
    # settings are changed. The additional file also exercises multi-file source hosting.
    source = (fixture / "consumer/src/main/kotlin/Example.kt").read_text()
    source += "\nval hostedSupport = HostedSupport.value\n"
    sources = {"Example.kt": source, "HostedSupport.kt": 'package example\nobject HostedSupport { val value = 42 }\n'}
    (fixture / "build").mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="kpaths-imported-source-") as temporary:
        project = prepare_imported_project(fixture / "consumer", Path(temporary).resolve() / "consumer",
                                           ROOT / "build/consumer-verification/repository", sources)
        for mode in ("enabled", "disabled", "diagnostic"):
            source_file = project / "src/main/kotlin/Example.kt"
            source_file.write_text(source + ('\nval diagnosticProbe: Int = "not an integer"\n' if mode == "diagnostic" else ''))
            config_mode = "disabled" if mode == "disabled" else "enabled"
            subprocess.run([sys.executable, str(fixture / "configure-consumer.py"), config_mode,
                            "--project", str(project)], cwd=ROOT, check=True)
            log = fixture / f"build/consumer-probe-{mode}.log"
            try:
                run_consumer_mode(ide, kefs, fixture, project, report, mode, log, 1, run_id)
            except AnalyticsStartupError:
                saved = log.with_name(f"consumer-probe-{mode}-startup-retry.log")
                log.replace(saved)
                print(f"Android Studio analytics startup failed; retrying once (first log: {saved}).", flush=True)
                run_consumer_mode(ide, kefs, fixture, project, report, mode, log, 2, run_id)


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
    publish_ide_artifact([
        wrapper, "-p", "kpaths-kefs-ide-fixture",
        "publishIdeCompilerPluginPublicationToKefsLocalRepository",
        f"-Pkpaths.idePath={ide}", f"-Pkpaths.ideKotlinVersion={version}", f"-Pkpaths.kefsVersion={kefs}",
    ])
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
