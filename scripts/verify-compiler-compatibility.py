#!/usr/bin/env python3
"""Run the same published-plugin consumer contracts against the supported Kotlin versions."""
import argparse
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parent.parent
matrix = root / "gradle/kpaths-supported-kotlin-versions.txt"
versions = [line.strip() for line in matrix.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith("#")]
if not versions or len(versions) != len(set(versions)):
    raise SystemExit("The compiler compatibility matrix must contain distinct versions")
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("versions", nargs="*", help="Subset of supported Kotlin versions to test (default: all)")
parser.add_argument("--native", action="store_true", help="Also publish local artifacts and run iOS simulator tests (macOS arm64)")
args = parser.parse_args()
selected = args.versions or versions
unknown = set(selected) - set(versions)
if unknown:
    parser.error("Not in the supported matrix: " + ", ".join(sorted(unknown)))
if args.native:
    import platform
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        parser.error("--native requires macOS arm64 and an available iOS simulator")
    subprocess.run([
        str(root / "gradlew"), "--no-configuration-cache", "-I", "gradle/local-test-repository.init.gradle",
        ":kpaths-gradle-plugin:publishAllPublicationsToConsumerVerificationRepository",
        ":kpaths-compiler-plugin:publishJvmPublicationToConsumerVerificationRepository",
        ":kpaths-core:publishKotlinMultiplatformPublicationToConsumerVerificationRepository",
        ":kpaths-core:publishIosSimulatorArm64PublicationToConsumerVerificationRepository",
        ":kpaths-annotations:publishKotlinMultiplatformPublicationToConsumerVerificationRepository",
        ":kpaths-annotations:publishIosSimulatorArm64PublicationToConsumerVerificationRepository",
    ], cwd=root, check=True)
for version in selected:
    print(f"\n=== Kotlin consumer {version} ===", flush=True)
    subprocess.run([
        str(root / "gradlew"), ":kpaths-gradle-plugin:test",
        ":kpaths-compiler-plugin-fixture:compilerPluginContractTest",
        f"-Pkpaths.testKotlinVersion={version}", "--no-configuration-cache",
    ], cwd=root, check=True)
    if args.native:
        subprocess.run([
            str(root / "gradlew"), "-p", "kpaths-compiler-plugin-compatibility-fixture",
            "clean", "iosSimulatorArm64Test", f"-Pkpaths.testKotlinVersion={version}",
            "--no-configuration-cache",
        ], cwd=root, check=True)
