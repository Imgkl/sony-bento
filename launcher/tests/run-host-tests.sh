#!/usr/bin/env bash
# Desktop-only loopback tests. Optional argument selects a test-name substring.
# Set JSON_JAR to an org.json runtime jar if it is not already in Gradle/Maven's cache.
set -euo pipefail
test_dir="$(cd "$(dirname "$0")" && pwd)"
launcher_dir="$(dirname "$test_dir")"
sdk="${ANDROID_SDK:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
android_jar="${ANDROID_JAR:-$sdk/platforms/android-36/android.jar}"
json_jar="${JSON_JAR:-}"
if [[ -z "$json_jar" ]]; then
  shopt -s nullglob
  for candidate in "$HOME"/.gradle/caches/modules-2/files-2.1/org.json/json/*/*/*.jar \
      "$HOME"/.m2/repository/org/json/json/*/json-*.jar; do
    case "$candidate" in *-sources.jar|*-javadoc.jar) continue ;; esac
    json_jar="$candidate"
    break
  done
fi
[[ -f "$android_jar" ]] || { echo "Set ANDROID_SDK or ANDROID_JAR to an installed Android SDK." >&2; exit 1; }
[[ -f "$json_jar" ]] || { echo "Set JSON_JAR to an org.json runtime jar." >&2; exit 1; }
if [[ -n "${JAVA_HOME:-}" ]]; then
  javac_bin="$JAVA_HOME/bin/javac"
  java_bin="$JAVA_HOME/bin/java"
else
  javac_bin="$(command -v javac)"
  java_bin="$(command -v java)"
fi
test_build="$(mktemp -d "${TMPDIR:-/tmp}/sony-volume-host-tests.XXXXXX")"
trap 'rm -rf "$test_build"' EXIT
test_sources=("$test_dir"/*Test.java)
"$javac_bin" --release 17 -cp "$android_jar" -sourcepath "$launcher_dir/src" \
  -d "$test_build/classes" \
  "$launcher_dir/src/com/sonybento/volume/AdbVolumeConnection.java" \
  "$launcher_dir/src/com/sonybento/volume/ShellVolumeHelper.java" \
  "${test_sources[@]}"
for test_source in "${test_sources[@]}"; do
  test_class="${test_source##*/}"
  "$java_bin" -cp "$test_build/classes:$json_jar:$android_jar" "com.sonybento.volume.${test_class%.java}" "$@"
done
