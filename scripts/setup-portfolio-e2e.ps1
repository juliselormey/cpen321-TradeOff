#!/usr/bin/env pwsh
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $root 'backend'
$envFile = Join-Path $backend '.env.e2e'
if (Test-Path -LiteralPath $envFile) {
    throw 'backend/.env.e2e already exists. Keep it and edit the missing values, or back it up before running setup again.'
}

$mysql = Get-Command mysql.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source
if (-not $mysql) {
    $mysql = Get-ChildItem 'C:/Program Files/MySQL' -Filter mysql.exe -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Directory.Name -eq 'bin' } | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $mysql) { throw 'Could not find mysql.exe. Install MySQL Server or add its bin folder to PATH.' }

$suffix = (Read-Host 'Database name suffix (letters, digits, underscore) [portfolio]').Trim().ToLowerInvariant()
if (-not $suffix) { $suffix = 'portfolio' }
if ($suffix -notmatch '^[a-z0-9_]+$') { throw 'Use only lowercase letters, numbers, and underscores for the suffix.' }

$admin = Read-Host 'MySQL administrator username [root]'
if (-not $admin) { $admin = 'root' }
$database = "tradeoff_e2e_$suffix"
$userBytes = [byte[]]::new(4)
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($userBytes)
$dbUser = "e2e_$suffix" + [BitConverter]::ToString($userBytes).Replace('-', '').ToLowerInvariant()
$randomBytes = [byte[]]::new(24)
$rng.GetBytes($randomBytes)
$dbPassword = [BitConverter]::ToString($randomBytes).Replace('-', '').ToLowerInvariant()
$jwtBytes = [byte[]]::new(32)
$rng.GetBytes($jwtBytes)
$jwtSecret = [BitConverter]::ToString($jwtBytes).Replace('-', '').ToLowerInvariant()
$rng.Dispose()

$sql = "CREATE DATABASE IF NOT EXISTS ``$database`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER '$dbUser'@'127.0.0.1' IDENTIFIED BY '$dbPassword'; GRANT ALL PRIVILEGES ON ``$database``.* TO '$dbUser'@'127.0.0.1'; FLUSH PRIVILEGES;"
Write-Host 'MySQL will ask for the administrator password.'
& $mysql --host=127.0.0.1 "--user=$admin" --password "--execute=$sql"
if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated database/user. Check MySQL is running and the admin login can create databases/users.' }

$databaseUrl = "mysql://$dbUser`:$dbPassword@127.0.0.1:3306/$database"
$contents = @"
# Local Portfolio E2E settings. This file is ignored by Git.
DATABASE_URL="$databaseUrl"
GOOGLE_CLIENT_ID=PASTE_YOUR_GOOGLE_WEB_CLIENT_ID
JWT_SECRET=$jwtSecret
ALPACA_API_KEY_ID=
ALPACA_API_SECRET_KEY=
E2E_USER_EMAIL=PASTE_THE_GOOGLE_ACCOUNT_EMAIL
"@
Set-Content -LiteralPath $envFile -Value $contents -Encoding utf8

$env:DOTENV_CONFIG_PATH = $envFile
$env:DATABASE_URL = $databaseUrl
Push-Location $backend
try {
    if (-not (Test-Path (Join-Path $backend 'node_modules'))) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
    }
    & npx.cmd prisma generate
    if ($LASTEXITCODE -ne 0) { throw 'Prisma client generation failed.' }
    & npx.cmd prisma db push
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the E2E tables.' }
} finally { Pop-Location }

Write-Host "Created $database and backend/.env.e2e. Edit the Google ID, Alpaca keys, and E2E_USER_EMAIL before starting the test backend."
