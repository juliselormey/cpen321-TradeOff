#!/usr/bin/env pwsh
# Compatibility alias: uses the normal backend; never switches URLs to a fixture.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run-browse-stocks-e2e-tests.ps1')
