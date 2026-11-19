#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
ide=
ideKotlin=
kefs=
while (($#)); do
  case "$1" in
    --ide-path) ide=${2:?Missing IDE path}; shift 2 ;;
    --ide-kotlin-version) ideKotlin=${2:?Missing IDE Kotlin version}; shift 2 ;;
    --kefs-version) kefs=${2:?Missing KEFS version}; shift 2 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done
[[ -n "$ide" && -n "$ideKotlin" && -n "$kefs" ]] || { echo 'IDE, IDE Kotlin, and KEFS versions are required.' >&2; exit 2; }
cd "$root"
checkout=${KNAPSHOTS_CHECKOUT:-$root/../Knapshots}
[[ -d "$checkout" ]] || checkout=$root/.knapshots
if [[ -n ${KPATHS_PYTHON:-} ]]; then
  python=$KPATHS_PYTHON
elif command -v python3.11 >/dev/null 2>&1; then
  python=python3.11
else
  python=python3
fi

# KPaths owns its compiler artifacts and verifies the IDE-specific build before publication.
"$python" scripts/verify-kefs-compatibility.py --ide-path "$ide" --ide-kotlin-version "$ideKotlin" \
  --kefs-version "$kefs" --allow-untested-combination
./gradlew -p kpaths-compiler-plugin-ide-fixture test "-Pkpaths.idePath=$ide" --no-configuration-cache
./gradlew -p "$checkout" -Pknapshots.enableIntellijAdapter "-Pknapshots.idePath=$ide" \
  :knapshots-intellij:writeEditorAdapterPath --no-configuration-cache
export KNAPSHOTS_IDE_ADAPTER_ARCHIVE
KNAPSHOTS_IDE_ADAPTER_ARCHIVE=$(cat "$checkout/knapshots-intellij/build/editor-adapter-path.txt")
export KNAPSHOTS_IDE_PATH="$ide"
for mode in enabled disabled diagnostic; do
  ./gradlew :kpaths-editor-fixture:test -Pkpaths.editorIntegration "-Pkpaths.editorMode=$mode" \
    "-Pknapshots.kefsVersion=$kefs" "-Pkpaths.editorReportDirectory=$root/build/ide-compatibility/$mode" \
    --no-configuration-cache
done
