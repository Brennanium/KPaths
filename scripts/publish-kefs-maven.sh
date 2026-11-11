#!/usr/bin/env bash
# Publish one validated Maven repository, retaining and revalidating existing versions.
set -euo pipefail
incoming=$1
shift
temp_root=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/kpaths-maven-publish.XXXXXX")
worktree="$temp_root/worktree"
cleanup() {
  git worktree remove --force "$worktree" >/dev/null 2>&1 || true
  rm -rf "$temp_root"
}
trap cleanup EXIT
bash scripts/fetch-kefs-maven.sh "$temp_root/current"
repositories=()
if [ -d "$temp_root/current" ]; then repositories+=("$temp_root/current"); fi
if [ "$incoming" != '-' ]; then repositories+=("$incoming"); fi
if [ "${#repositories[@]}" -eq 0 ]; then echo 'No Maven repository exists to publish.' >&2; exit 1; fi
python3 scripts/assemble-kefs-maven-repository.py --output "$temp_root/final" "$@" "${repositories[@]}"
if [ -d "$temp_root/current" ]; then
  git worktree add --detach "$worktree" FETCH_HEAD
else
  git worktree add --detach "$worktree" HEAD
  git -C "$worktree" checkout --orphan kpaths-kefs-maven
fi
# This is a dedicated temporary worktree. Remove the previous tree before copying so
# explicitly deleted version directories or files cannot survive the publication.
git -C "$worktree" rm -rf --ignore-unmatch .
cp -a "$temp_root/final/." "$worktree/"
git -C "$worktree" add -A
if ! git -C "$worktree" diff --cached --quiet; then
  git -C "$worktree" -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' commit -m 'Publish KEFS Maven artifacts'
  git -C "$worktree" push origin HEAD:refs/heads/kpaths-kefs-maven
else
  echo 'The durable Maven repository is already up to date.'
fi
