#!/usr/bin/env python3
"""Run compiler-plugin regression suites against explicit Kotlin versions or dev families."""
import argparse
import json
import platform
import subprocess
import sys
from pathlib import Path

from kotlin_version_resolver import resolve

ROOT = Path(__file__).resolve().parent.parent


def read_matrix(path):
    values = [line.strip() for line in path.read_text().splitlines()
              if line.strip() and not line.lstrip().startswith("#")]
    if not values or len(values) != len(set(values)):
        raise ValueError(f"The compatibility matrix must contain distinct selectors: {path}")
    return values


def simulator_available():
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        return False
    try:
        result = subprocess.run(["xcrun", "simctl", "list", "devices", "available", "--json"],
                                capture_output=True, text=True, check=True, timeout=20)
        devices = json.loads(result.stdout)["devices"]
        return any(items for runtime, items in devices.items() if ".iOS-" in runtime)
    except (OSError, subprocess.SubprocessError, ValueError, KeyError):
        return False


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("versions", nargs="*", help="Exact versions or quoted dev selectors, e.g. 2.4.0 '2.5.0-dev*'")
    parser.add_argument("--preview", action="store_true", help="Use the preview selector list when no versions are supplied")
    native = parser.add_mutually_exclusive_group()
    native.add_argument("--native", action="store_true", help="Require the iOS simulator checks")
    native.add_argument("--jvm-only", action="store_true", help="Skip the iOS simulator checks")
    parser.add_argument("--ide-path", type=Path, help="Also verify KEFS with this installed IDE (a separate compatibility check)")
    parser.add_argument("--resolve-only", action="store_true", help="Print concrete versions without compiling")
    parser.add_argument("--report-json", type=Path, help="Write a machine-readable result summary")
    args = parser.parse_args(argv)
    try:
        matrix = ROOT / ("gradle/kpaths-preview-kotlin-versions.txt" if args.preview
                         else "gradle/kpaths-supported-kotlin-versions.txt")
        selectors = args.versions or read_matrix(matrix)
        resolved = resolve(selectors)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    unique = list(dict.fromkeys(version for _, version in resolved))
    print("Resolved Kotlin targets:", flush=True)
    for selector, version in resolved:
        print(f"  {selector} -> {version}" if selector != version else f"  {version}", flush=True)
    if args.resolve_only:
        return 0
    available = simulator_available() if not args.jvm_only else False
    if args.native and not available:
        parser.error("--native requires macOS arm64 with an available iOS simulator")
    run_native = args.native or (not args.jvm_only and available)
    if not run_native:
        print("Native checks: NOT RUN (use --native on Apple Silicon with an iOS simulator).", flush=True)
    if args.ide_path is None:
        print("IDE/KEFS checks: NOT RUN (pass --ide-path; IDE compatibility is separate from Kotlin versions).", flush=True)

    results = []
    print("Preparing local artifacts for independent consumers...", flush=True)
    publications = [
            str(ROOT / "gradlew"), "--no-configuration-cache", "-I", "gradle/local-test-repository.init.gradle",
            ":kpaths-gradle-plugin:publishAllPublicationsToConsumerVerificationRepository",
            ":kpaths-compiler-plugin:publishJvmPublicationToConsumerVerificationRepository",
            ":kpaths-core:publishKotlinMultiplatformPublicationToConsumerVerificationRepository",
            ":kpaths-annotations:publishKotlinMultiplatformPublicationToConsumerVerificationRepository",
            ":kpaths-core:publishJvmPublicationToConsumerVerificationRepository",
            ":kpaths-annotations:publishJvmPublicationToConsumerVerificationRepository",
    ]
    if run_native:
        publications += [":kpaths-core:publishIosSimulatorArm64PublicationToConsumerVerificationRepository",
                         ":kpaths-annotations:publishIosSimulatorArm64PublicationToConsumerVerificationRepository"]
    consumer_ready = subprocess.run(publications, cwd=ROOT).returncode == 0
    for version in unique:
        print(f"\n=== Kotlin {version}: JVM compiler and consumer regression ===", flush=True)
        jvm = subprocess.run([
            str(ROOT / "gradlew"), ":kpaths-gradle-plugin:test",
            ":kpaths-compiler-plugin-fixture:jvmTest",
            ":kpaths-compiler-plugin-fixture:compilerPluginContractTest",
            f"-Pkpaths.testKotlinVersion={version}", "-Pkpaths.snapshotRecording=none",
            "--continue", "--no-configuration-cache",
        ], cwd=ROOT).returncode == 0
        consumer = "failed-setup"
        if consumer_ready:
            print(f"\n=== Kotlin {version}: independent JVM consumer runtime ===", flush=True)
            consumer = "passed" if subprocess.run([
                str(ROOT / "gradlew"), "-p", "kpaths-compiler-plugin-compatibility-fixture",
                "clean", "jvmTest", f"-Pkpaths.testKotlinVersion={version}",
                "--no-configuration-cache",
            ], cwd=ROOT).returncode == 0 else "failed"
        native_status = "not-run"
        if run_native and consumer_ready:
            print(f"\n=== Kotlin {version}: iOS simulator consumer ===", flush=True)
            native_status = "passed" if subprocess.run([
                str(ROOT / "gradlew"), "-p", "kpaths-compiler-plugin-compatibility-fixture",
                "clean", "iosSimulatorArm64Test", f"-Pkpaths.testKotlinVersion={version}",
                "--no-configuration-cache",
            ], cwd=ROOT).returncode == 0 else "failed"
        elif run_native:
            native_status = "failed-setup"
        results.append({"version": version, "jvm": "passed" if jvm else "failed", "jvmConsumer": consumer, "iosSimulator": native_status})
    ide = "not-run"
    if args.ide_path is not None:
        print("\n=== Separate Android Studio/KEFS check ===", flush=True)
        ide = "passed" if subprocess.run([
            sys.executable, str(ROOT / "scripts/verify-kefs-compatibility.py"),
            "--ide-path", str(args.ide_path),
        ], cwd=ROOT).returncode == 0 else "failed"
    report = {"selectors": [{"input": selector, "resolved": version} for selector, version in resolved],
              "results": results, "ideKefs": ide}
    print("\nCompatibility results:", flush=True)
    for item in results:
        print(f"  {item['version']}: JVM suite {item['jvm']}; JVM consumer {item['jvmConsumer']}; iOS simulator {item['iosSimulator']}", flush=True)
    print(f"  Separate IDE/KEFS check: {ide}", flush=True)
    if args.report_json:
        args.report_json.parent.mkdir(parents=True, exist_ok=True)
        args.report_json.write_text(json.dumps(report, indent=2) + "\n")
        print(f"  Report: {args.report_json}", flush=True)
    return int(any(item["jvm"] != "passed" or item["jvmConsumer"] != "passed" or item["iosSimulator"].startswith("failed") for item in results) or ide == "failed")


if __name__ == "__main__":
    sys.exit(main())
