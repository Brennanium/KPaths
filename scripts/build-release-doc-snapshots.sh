#!/usr/bin/env bash
# Add every stable release's documentation to an already-built development site.
set -euo pipefail
repo=$(git rev-parse --show-toplevel)
knapshots_checkout=${KNAPSHOTS_CHECKOUT:-}
if [ -z "$knapshots_checkout" ]; then
  if [ -f "$repo/.knapshots/settings.gradle.kts" ]; then
    knapshots_checkout="$repo/.knapshots"
  elif [ -f "$repo/../Knapshots/settings.gradle.kts" ]; then
    knapshots_checkout="$repo/../Knapshots"
  fi
fi
knapshots_args=()
if [ -n "$knapshots_checkout" ]; then knapshots_args+=("-Pknapshots.checkout=$knapshots_checkout"); fi
if [ -n "${DOCS_PYTHON:-}" ]; then
  docs_python="$DOCS_PYTHON"
elif [ -x "$repo/.venv-docs/bin/python" ]; then
  docs_python="$repo/.venv-docs/bin/python"
else
  docs_python=$(command -v python3)
fi
worktree=''
cleanup() {
  if [ -n "$worktree" ]; then git -C "$repo" worktree remove --force "$worktree" || true; fi
}
trap cleanup EXIT
while IFS= read -r version; do
  worktree=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/kpaths-docs.XXXXXX")
  git -C "$repo" worktree add --detach "$worktree" "$version"
  (
    cd "$worktree"
    ./gradlew docsBuild -I "$repo/gradle/documentation.init.gradle.kts" -PdocumentationVersion="$version" "-PdocsPython=$docs_python" "${knapshots_args[@]}"
    config=$("$docs_python" "$repo/scripts/prepare-release-doc-theme.py" "$repo" "$worktree" "$version")
    "$docs_python" -m mkdocs build --strict --config-file "$config"
    cp -a "$worktree/build/docs/site/api/." "$worktree/build/docs/themed-site/api/"
  )
  rm -rf -- "$repo/build/docs/site/$version"
  mkdir -p "$repo/build/docs/site/$version"
  cp -a "$worktree/build/docs/themed-site/." "$repo/build/docs/site/$version/"
  git -C "$repo" worktree remove --force "$worktree"
  worktree=''
done < <(git -C "$repo" tag --list --sort=version:refname | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' || true)

"$docs_python" "$repo/scripts/finalize-doc-site.py" "$repo/build/docs/site"
