#!/usr/bin/env bash
# Export the durable Maven branch if it exists. Fail on network/authentication errors.
set -euo pipefail
destination=$1
branch=kpaths-kefs-maven
if git ls-remote --exit-code --heads origin "refs/heads/$branch" >/dev/null; then
  git fetch --no-tags origin "refs/heads/$branch"
  mkdir -p "$destination"
  git archive FETCH_HEAD | tar -x -C "$destination"
else
  status=$?
  if [ "$status" -ne 2 ]; then
    echo "Could not check the durable Maven branch (exit $status)" >&2
    exit "$status"
  fi
  echo "No durable Maven branch exists yet."
fi
