#!/usr/bin/env bash
# Works out the next release version of this fork and prints it as key=value lines for $GITHUB_OUTPUT.
#
# The version is upstream's versionName from app/build.gradle plus a fork counter: the first release on
# upstream 0.8.2 is 0.8.2-lumix.1, the next 0.8.2-lumix.2, and after merging upstream 0.8.3 the counter
# starts again at 0.8.3-lumix.1. versionCode is upstream's versionCode * 100 + the counter, so it keeps
# growing across upstream updates (upstream 20 gives 2001, 2002, ...; upstream 21 gives 2101).
set -euo pipefail

gradle_file=${1:-app/build.gradle}
base=$(sed -nE "s/^[[:space:]]*versionName[[:space:]].*[\"']([^\"']+)[\"'][[:space:]]*$/\1/p" "$gradle_file" | head -n1)
base_code=$(sed -nE 's/^[[:space:]]*versionCode[[:space:]](.*[^0-9])?([0-9]+)[[:space:]]*$/\2/p' "$gradle_file" | head -n1)
if [ -z "$base" ] || [ -z "$base_code" ]; then
    echo "could not read versionName and versionCode from $gradle_file" >&2
    exit 1
fi

last=$(git tag -l "v${base}-lumix.*" | sed -nE 's/^v.*-lumix\.([0-9]+)$/\1/p' | sort -n | tail -n1)
n=$(( ${last:-0} + 1 ))
if [ "$n" -ge 100 ]; then
    echo "version $base already has 99 fork releases; versionCode would collide with upstream's next one" >&2
    exit 1
fi

echo "base=$base"
echo "version=$base-lumix.$n"
echo "code=$(( base_code * 100 + n ))"
echo "tag=v$base-lumix.$n"
echo "previous=$(git describe --tags --abbrev=0 --match 'v*-lumix.*' HEAD 2>/dev/null || true)"
