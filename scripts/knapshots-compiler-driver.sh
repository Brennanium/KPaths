#!/usr/bin/env bash
set -uo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
version=
platforms=jvm
while (($#)); do
  case "$1" in
    --kotlin-version) version=${2:?Missing Kotlin version}; shift 2 ;;
    --platform) platforms=${2:?Missing platform list}; shift 2 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done
[[ -n "$version" ]] || { echo 'A Kotlin version is required.' >&2; exit 2; }
IFS=, read -r -a selected <<< "$platforms"
for platform in "${selected[@]}"; do
  [[ "$platform" == jvm || "$platform" == native ]] || { echo "Unsupported platform: $platform" >&2; exit 2; }
done
[[ ${#selected[@]} -gt 0 ]] || { echo 'Select jvm, native, or both.' >&2; exit 2; }
cd "$root" || exit 1
failed=0
report() { printf 'KNAPSHOTS_CHECK %s %s\n' "$1" "$2"; }
run_check() {
  local name=$1
  shift
  if "$@"; then report "$name" passed; else report "$name" failed; failed=1; fi
}

publication=(./gradlew --no-configuration-cache -I gradle/local-test-repository.init.gradle
  :kpaths-gradle-plugin:publishAllPublicationsToConsumerVerificationRepository
  :kpaths-compiler-plugin:publishJvmPublicationToConsumerVerificationRepository
  :kpaths-core:publishKotlinMultiplatformPublicationToConsumerVerificationRepository
  :kpaths-annotations:publishKotlinMultiplatformPublicationToConsumerVerificationRepository)
case ",$platforms," in
  *",jvm,"*) publication+=( :kpaths-core:publishJvmPublicationToConsumerVerificationRepository :kpaths-annotations:publishJvmPublicationToConsumerVerificationRepository ) ;;
esac
case ",$platforms," in
  *",native,"*) publication+=( :kpaths-core:publishIosSimulatorArm64PublicationToConsumerVerificationRepository :kpaths-annotations:publishIosSimulatorArm64PublicationToConsumerVerificationRepository ) ;;
esac
if "${publication[@]}"; then ready=1; report artifacts passed; else ready=0; report artifacts failed; failed=1; fi

case ",$platforms," in
  *",jvm,"*)
    run_check jvm ./gradlew :kpaths-gradle-plugin:test :kpaths-compiler-plugin-fixture:jvmTest \
      :kpaths-compiler-plugin-fixture:compilerPluginContractTest "-Pkpaths.testKotlinVersion=$version" \
      -Pkpaths.snapshotRecording=none --continue --no-configuration-cache
    if ((ready)); then
      run_check jvm-consumer ./gradlew -p kpaths-compiler-plugin-compatibility-fixture clean jvmTest \
        "-Pkpaths.testKotlinVersion=$version" --no-configuration-cache
    else report jvm-consumer not-run; fi ;;
  *) report jvm not-run ;;
esac
case ",$platforms," in
  *",native,"*)
    if ((ready)); then
      run_check native ./gradlew -p kpaths-compiler-plugin-compatibility-fixture clean iosSimulatorArm64Test \
        "-Pkpaths.testKotlinVersion=$version" --no-configuration-cache
    else report native not-run; fi ;;
  *) report native not-run ;;
esac
exit "$failed"
