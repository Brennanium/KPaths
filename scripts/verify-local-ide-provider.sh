#!/usr/bin/env bash
# Example project driver: run the existing non-KEFS provider fixture against a selected IDE.
set -euo pipefail

if [ "$#" -ne 2 ] || [ "$1" != "--ide-path" ]; then
  echo 'Usage: verify-local-ide-provider.sh --ide-path <Android Studio>' >&2
  exit 2
fi

project_root="$(cd "$(dirname "$0")/.." && pwd)"
ide_path="$2"
cd "$project_root"
./gradlew :kpaths-compiler-plugin:jvmJar :kpaths-core:jvmJar :kpaths-annotations:jvmJar
./gradlew -p kpaths-compiler-plugin-ide-fixture test "-Pkpaths.idePath=$ide_path"
