#!/usr/bin/env bash
# Add every stable release's documentation to an already-built development site.
set -euo pipefail
repo=$(git rev-parse --show-toplevel)
if [ -x "$repo/.venv-docs/bin/python" ]; then
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
    ./gradlew docsBuild -I "$repo/gradle/documentation.init.gradle.kts" -PdocumentationVersion="$version" "-PdocsPython=$docs_python"
  )
  mkdir -p "$repo/build/docs/site/$version"
  cp -a "$worktree/build/docs/site/." "$repo/build/docs/site/$version/"
  git -C "$repo" worktree remove --force "$worktree"
  worktree=''
done < <(git -C "$repo" tag --list --sort=version:refname | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' || true)
