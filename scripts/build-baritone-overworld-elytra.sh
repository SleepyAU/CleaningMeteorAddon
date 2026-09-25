#!/usr/bin/env bash
set -euo pipefail

baritone_commit="b60a3e5fcedd7239acd7dea63309c8ecf7c63feb"
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd -- "$script_dir/.." && pwd)
output_dir=${1:-"$project_dir/build/libs"}
source_dir=$(mktemp -d)

cleanup() {
    rm -rf -- "$source_dir"
}
trap cleanup EXIT

git -C "$source_dir" init --quiet
git -C "$source_dir" remote add origin https://github.com/cabaletta/baritone.git
git -C "$source_dir" fetch --quiet --depth 1 origin "$baritone_commit"
git -C "$source_dir" checkout --quiet FETCH_HEAD
git -C "$source_dir" apply "$project_dir/baritone-overworld-elytra.patch"

task_java_home=${JAVA_HOME:-}
if [[ -d /usr/lib/jvm/java-21-openjdk ]]; then
    task_java_home=/usr/lib/jvm/java-21-openjdk
fi
if [[ -z "$task_java_home" ]]; then
    echo "JAVA_HOME must point to a Java 21 JDK." >&2
    exit 1
fi

JAVA_HOME="$task_java_home" "$source_dir/gradlew" -p "$source_dir" :fabric:build
mkdir -p -- "$output_dir"
artifact="$output_dir/baritone-api-fabric-1.14.0-overworld-elytra-moss-b60a3e5.jar"
cp -- "$source_dir/fabric/build/libs/baritone-api-fabric-1.14.0.jar" "$artifact"
sha256sum "$artifact"
