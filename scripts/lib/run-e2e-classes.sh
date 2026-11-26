#!/usr/bin/env bash
set -euo pipefail
prefix="$1"
shift
cd "$(dirname "$0")/../../frontend"
report_root="$PWD/app/build/reports/e2e-$prefix/$(date +%Y%m%d-%H%M%S)-$$"
failed=()
marker="$(mktemp)"
trap 'rm -f "$marker"' EXIT
for suite in "$@"; do
    printf '\n=== %s ===\n' "$suite"
    touch "$marker"
    if ./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true "-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.$suite" --console=plain; then
        :
    else
        failed+=("$suite")
    fi
    source_report=app/build/reports/androidTests/connected/debug
    if [[ "$source_report/index.html" -nt "$marker" ]]; then
        mkdir -p "$report_root/$suite"
        cp -R "$source_report/." "$report_root/$suite/"
    fi
done
printf 'Reports: %s\n' "$report_root"
if ((${#failed[@]})); then
    printf 'E2E failures: %s\n' "${failed[*]}" >&2
    exit 1
fi
