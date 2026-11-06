# README: TradeOff installation guide

## Introduction

TradeOff is an Android investment-practice app w/ Google sign-in, live market data, virtual trading, portfolios, and a friends leaderboard.

Follow these steps to clone the project and run the full app locally using an Android emulator.

Complete the one-time setup first; later runs only require starting the backend and launching the app

> [!IMPORTANT]
> Required software and tools include:
> - [Git](https://git-scm.com/install/)
> - [Node.js 22.x](https://nodejs.org/en/download/)
>   - ...with npm 10.x
> - [MySQL Community Server 8.4 LTS](https://dev.mysql.com/downloads/mysql/)
>   - during installation (configurator), keep port 3306, set an administrator (`root`) password, and enable the service to start automatically
> - [Android Studio](https://developer.android.com/studio)
>   - ...with Android SDK Platform 35, Android Emulator, and Platform-Tools installed through SDK Manager
>   - use JDK 17 or a compatible bundled JDK for Gradle
> - [OpenSSL](https://openssl-library.org/source/)
>   - on Windows, Git normally includes it at `C:\Program Files\Git\usr\bin\openssl.exe`
> - A [Google account](https://accounts.google.com/), a [Google Cloud project](https://console.cloud.google.com/) for sign-in, and [Alpaca API credentials](https://alpaca.markets/) for stock data
>   - must be configured; a fresh clone does not contain working credentials for a new tester
>   - sign up for Alpaca and open a Paper Trading account (choosing Trading API on signup/signin does just fine)
> 
> Commands used assume Windows (PowerShell). On macOS/Linux, use `npm`/`npx`, `cp`, `mkdir -p`, `curl`, and `./gradlew` in place of their Windows equivalents.
> ```powershell
> git clone https://github.com/juliselormey/cpen321-TradeOff.git
> cd cpen321-TradeOff
> ```

## Backend Setup

### 1. Create the local database

Open MySQL Command Line Client and enter the root password chosen during installation (alternatively, open a root connection in MySQL Workbench or run `mysql -u root -p` if MySQL is on your PATH).

After connecting as root, run this SQL, replacing `YOUR_PASSWORD` with a password of your choice. This is a one-time step for a new database.
```sql
CREATE DATABASE tradeoff CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'tradeoff_app'@'localhost' IDENTIFIED BY 'YOUR_PASSWORD';
GRANT ALL PRIVILEGES ON tradeoff.* TO 'tradeoff_app'@'localhost';
```

> [!NOTE]
> MySQL must be running for login, accounts, trades, and portfolios. If you enabled automatic startup during installation, you do not need to start it manually. Otherwise, open `services.msc` on Windows, find your MySQL service, and click Start.

### (you should do Frontend 1 now.)

### 2. Install dependencies and configure the backend

In PowerShell, from the repository root:
```powershell
cd backend
npm.cmd ci
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Open `cpen321-TradeOff/backend/.env` and edit the file (if it already exists, edit only the differences) so it contains:
```dotenv
PORT=3000
NODE_ENV=development
DATABASE_URL="mysql://tradeoff_app:YOUR_PASSWORD@127.0.0.1:3306/tradeoff"
GOOGLE_CLIENT_ID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
JWT_SECRET=YOUR_RANDOM_SECRET
ALPACA_API_KEY_ID=YOUR_ALPACA_KEY
ALPACA_API_SECRET_KEY=YOUR_MATCHING_ALPACA_SECRET
```
- `DATABASE_URL`: change `YOUR_PASSWORD` to the password you gave it (NOT the root password!)
- `GOOGLE_CLIENT_ID`: from the Web application OAuth client you made in Frontend 2
- `JWT_SECRET`: run this cmd in PowerShell with Node.js installed, from any folder - what you want is the output
```powershell
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
```
- `ALPACA_API_KEY_ID` from your Alpaca Paper Trading account - namely the API Keys section of the Dashboard
- `ALPACA_API_SECRET_KEY`: see above

> [!WARNING]
> NEVER commit or share any secrets (even example ones, which may be generated project-dependently). Includes API keys and basically the entire `.env` file.

### 3. Create the tables and HTTPS certificate

Ensure you're still in `cpen321-TradeOff/backend/`, then run some cmds:
```powershell
npx.cmd prisma generate
npx.cmd prisma db push
New-Item -ItemType Directory -Force certs
```

`db push` creates the current tables in your new local database. If using an existing database and Prisma warns about data loss, inspect the changes rather than forcing them.

Generate the local certificate, the current directory still `cpen321-TradeOff/backend/`, with OpenSSL (If PowerShell cannot find `openssl`, replace `openssl` in that command with `& "C:\Program Files\Git\usr\bin\openssl.exe"` after confirming that file exists):
```powershell
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -keyout certs/key.pem -out certs/cert.pem -days 365 -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2" -addext "basicConstraints=critical,CA:TRUE"
Copy-Item certs/cert.pem ../frontend/app/src/main/res/raw/cert.pem
```

> [!NOTE]
> Ensure that the frontend must have the same public certificate as the backend. If it doesn't, you'll have to regenerate the certificate, requiring restarting the backend and rebuilding the app. The copied frontend certificate is a tracked file; keep your local certificate replacement out of unrelated commits.

> [!WARNING]
> NEVER copy `key.pem` into the frontend. It's private. 

### (you should do Frontend 3 now.)

## Frontend Setup

### 1. Open the project and configure the emulator

Clone the repo in any way you prefer (common ways include `git clone` or GitHub Desktop)

Open the `cpen321-TradeOff/frontend/` directory (NOT just `cpen321-TradeOff`) in Android Studio and allow Gradle to import and sync.

In Device Manager => "Add a new device", create a phone equivalent to or which supersedes the following settings (+ run it to make sure it works):
- Pixel 9 (recommended)
- API 36.0 "Baklava", Android 16.0
- Google Play system image
- Services include Google Play Store

Sign in through an app already present on your phone (e.g. Settings or Play Store) to save some time.

Under File => Settings => Build, Execution, Deployment => Build Tools => Gradle, select JDK 17 or a compatible Android Studio bundled JDK. For terminal Gradle commands, set `JAVA_HOME` to the same JDK.

### 2. Configure Google sign-in

In Google Cloud Console, create/select a project. 

Search for Google Auth Platform, configure the app's branding/contact information, and choose an audience (if using testing mode, add your test Google accounts under Audience → Test users).

In a terminal opened in `cpen321-TradeOff/frontend/`, run:
```powershell
.\gradlew.bat signingReport
```

What you're looking for is the SHA-1 under the debug variant - copy it.

Under Google Auth Platform => Clients => Create client, create an Android OAuth client using:
- package name `com.example.cpen321application`
- the mentioned SHA-1 from the signingReport

In the same Google Cloud project, create a Web application OAuth client.
- save this client's ID (NOT the Android client ID or Web client secret), you will use it for the backend shortly

> [!NOTE]
> If using your group's existing Google Cloud project instead, its owner must register your debug SHA-1/package and give you the matching Web client ID and any required test-user access. See [Google's sign-in setup](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation).

> [!NOTE]
> Google must recognize your app's package name and your computer's debug signing certificate. This registration is required when building the app yourself.

### (you should do Backend 2 now.)

### 3. Configure the frontend

Open `cpen321-TradeOff/frontend/local.properties` - Android Studio normally creates this file with `sdk.dir`; keep the path it generated and add the other settings below (If the file does not exist, just copy `local.properties.example` and edit only the differences):
```properties
sdk.dir=C:/Users/YOUR_USERNAME/AppData/Local/Android/Sdk
API_BASE_URL=https://10.0.2.2:3000/
MARKET_API_BASE_URL=https://10.0.2.2:3000/
GOOGLE_CLIENT_ID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

Use your actual SDK path (to find it, go to Tools => SDK Manager => Android SDK Location), and leave `DEBUG_APPLICATION_ID_SUFFIX` unset (if it doesn't exist, you're good)

> [!NOTE]
> `10.0.2.2` lets the Android emulator reach your computer. Ensure that both URLs must point to the full backend on port 3000, not a remote deployment or the separate market development server.

## Build Setup

### 1. Start the backend

In a terminal opened in `cpen321-TradeOff/backend/`:
```powershell
npm.cmd run dev
```

> [!NOTE]
> Keep this terminal open. Expect `Server listening`. This starts the full backend, you do not need to instantiate separate parts (i.e. run `npm run dev:market`) or set up a deployment tool (e.g. Docker) for this setup.

In a second terminal directed to `cpen321-TradeOff/backend/`, probe the connection by running:
```powershell
curl.exe --cacert certs/cert.pem https://localhost:3000/health
curl.exe --cacert certs/cert.pem https://localhost:3000/api/market/stocks
```

> [!NOTE]
> The first request should return `{"status":"ok"}` (if you've judged by the command, it's general backend (connection) health!), the second should return Alpaca's wall of text (should be six stocks with price data, connection health, and other relevant stuff).

### 2. Run the app

Brace for impact - open `cpen321-TradeOff/frontend/` in Android Studio, Start your emulator in Device Manager, and click Run. Sign in with your configured Google account. If it's your first time using said configured Google account with the app, a new user and $10,000 virtual-cash are created automatically. Good luck on your investments!

### 3. For subsequent runs

Once setup is complete:
1. Ensure MySQL is running—normally automatic.
2. Run `npm.cmd run dev` from `backend/` and keep it open.
3. Start the emulator and open TradeOff, or click Run if the app changed.

> [!NOTE]
> Stop the backend with Ctrl+C when finished (simply closing the terminal may not be safe). Do not rerun database creation, regenerate certificates, or reinstall dependencies every time.

> [!NOTE]
> Rebuild and reinstall after changing `local.properties` or `cert.pem`. Restart the backend after changing `.env`. If switching from another backend, sign out and sign back in.

## Conclusion

Some in-app tests you can do:
- Account - Sign in, edit the display name/username, save, and reopen the screen. Usernames must be unique and use 3–24 lowercase letters, digits, or underscores.
- Market/Trading - Check stock prices/charts, buy a small quantity, then sell some owned shares. Check that invalid quantities and insufficient balances are rejected. Try a limit order and cancel it if it remains pending.
- Portfolio - Confirm cash, holdings, pending orders, and recent transactions reflect your trades.
- Social - Use a second Google account on another emulator connected to the same backend. Send and accept a friend request, then check the leaderboard.

Some automated checks you can do:
- to `cpen321-TradeOff/frontend/`:
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```
- to `cpen321-TradeOff/backend/`:
```powershell
npm.cmd run build
npm.cmd run typecheck
npm.cmd test -- --runInBand --testPathPatterns=tests/mock
```

> [!CAUTION]
> Troubleshooting table - if you run into one of these issues deal with it (else bad things might happen to your build of the app):
> | Problem | Fix |
> | --- | --- |
> | Cannot connect to MySQL / port 3306 | Start the MySQL service and check `DATABASE_URL` and the database user's password. |
> | MySQL reports `ER_CANNOT_RETRIEVE_RSA_KEY` | The database driver cannot complete RSA-based authentication with this server configuration. Configure a trusted database TLS connection or server public key with the driver before retrying; the current setup has not passed this check. |
> | Certificate does not match `localhost` | Generate the local certificate using the command above, restart the backend, copy the new public certificate into Android, and rebuild. |
> | Google sign-in fails | Check the debug SHA-1/package registration, test-user access, Google Play emulator, and matching **Web** client IDs. |
> | Certificate / `Trust anchor` error | Copy the backend's current `cert.pem` to the frontend's `app/src/main/res/raw/cert.pem`, then rebuild/reinstall. |
> | `MARKET_NOT_CONFIGURED` | Set both Alpaca variables in `backend/.env`, start from `backend/`, and restart the backend. |
> | Alpaca rejects credentials | Check the matching key/secret pair and IEX access; update both values and restart after regenerating keys. |
> | Portfolio shows an error | Confirm both URLs use `https://10.0.2.2:3000/`, the full backend is running, and `prisma db push` completed. Check the backend terminal for the exact error. |
> | Account/Portfolio stops working after being signed in | Tokens expire after 15 minutes; sign out and back in. |
> | Port 3000 is already in use | Stop the older backend process before starting another one. |
> | PowerShell blocks `npm.ps1` | Use `npm.cmd` and `npx.cmd` as shown above. |
