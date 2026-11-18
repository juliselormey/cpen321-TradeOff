#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../frontend"
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true '-Pandroid.testInstrumentationRunnerArguments.class=com.example.cpen321application.e2e.PortfolioFlowTest' --console=plain
