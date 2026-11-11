#!/usr/bin/env bash
# download-artifact extracts a single matching artifact directly, but multiple into subdirectories.
set -euo pipefail
downloaded=$1
output=$2
existing=$3
replace_version=${4:-}
repositories=()
options=()
if [ -d "$existing" ]; then repositories+=("$existing"); fi
if [ -f "$downloaded/io/github/brennanium/kpaths-compiler-plugin-jvm/maven-metadata.xml" ]; then
  repositories+=("$downloaded")
else
  shopt -s nullglob
  candidates=("$downloaded"/kefs-maven-*)
  if [ "${#candidates[@]}" -eq 0 ]; then echo 'No IDE Maven artifacts were downloaded.' >&2; exit 1; fi
  repositories+=("${candidates[@]}")
fi
if [ -n "$replace_version" ]; then options+=(--replace-version "$replace_version"); fi
python3 scripts/assemble-kefs-maven-repository.py --output "$output" ${options[@]+"${options[@]}"} "${repositories[@]}"
