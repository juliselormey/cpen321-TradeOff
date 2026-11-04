# TradeOff README

TradeOff is an Android practice-investing app with Google sign-in, live market data, virtual trading, portfolios, and a friends leaderboard.

Follow these steps to clone the project and run the full app locally using an Android emulator. Complete the one-time setup first; later runs only require starting the backend and launching the app.

## Requirements

- [Git](https://git-scm.com/install/).
- [Node.js 22.x](https://nodejs.org/en/download/) with npm 10.x.
- [MySQL Community Server 8.4 LTS](https://dev.mysql.com/downloads/mysql/). During installation, keep port **3306**, set an administrator (`root`) password, and enable the service to start automatically.
- [Android Studio](https://developer.android.com/studio) with **Android SDK Platform 35**, **Android Emulator**, and **Platform-Tools** installed through SDK Manager. Use JDK 17 or a compatible bundled JDK for Gradle.
- OpenSSL. On Windows, Git normally includes it at `C:\Program Files\Git\usr\bin\openssl.exe`.
- A Google account, a [Google Cloud project](https://console.cloud.google.com/) for sign-in, and [Alpaca API credentials](https://alpaca.markets/) for stock data. These must be configured; a fresh clone does not contain working credentials for a new tester.

Commands below use **Windows PowerShell**. On macOS/Linux, use `npm`/`npx`, `cp`, `mkdir -p`, `curl`, and `./gradlew` in place of their Windows equivalents.

```powershell
git clone https://github.com/juliselormey/cpen321-TradeOff.git
cd cpen321-TradeOff
```

---

## Backend Setup

### 1. Create the local database

Open **MySQL Command Line Client** and enter the root password chosen during installation. Alternatively, open a root connection in MySQL Workbench or run `mysql -u root -p` if MySQL is on your PATH.

Run this SQL, replacing `YOUR_PASSWORD` with a long alphanumeric password of your choice:

```sql
CREATE DATABASE tradeoff CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'tradeoff_app'@'localhost' IDENTIFIED BY 'YOUR_PASSWORD';
GRANT ALL PRIVILEGES ON tradeoff.* TO 'tradeoff_app'@'localhost';
```

This is a one-time step for a new database. **MySQL must be running** for login, accounts, trades, and portfolios. If you enabled automatic startup during installation, you do not need to start it manually. Otherwise, open `services.msc` on Windows, find your MySQL service, and click **Start**.

### 2. Install dependencies and configure the backend

In PowerShell, from the repository root:

```powershell
cd backend
npm.cmd ci
Copy-Item .env.example .env
```

Open `backend/.env` and set:

```dotenv
PORT=3000
NODE_ENV=development
DATABASE_URL="mysql://tradeoff_app:YOUR_PASSWORD@127.0.0.1:3306/tradeoff"
GOOGLE_CLIENT_ID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
JWT_SECRET=YOUR_RANDOM_SECRET
ALPACA_API_KEY_ID=YOUR_ALPACA_KEY
ALPACA_API_SECRET_KEY=YOUR_MATCHING_ALPACA_SECRET
```

- Use the database password from step 1. If it contains reserved URL characters, URL-encode them in `DATABASE_URL`.
- Generate `JWT_SECRET` with the following command and paste the output into `.env`:

  ```powershell
  node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
  ```

- In your Alpaca dashboard, generate an API key and secret, then copy both values into `.env`. The backend uses Alpaca's IEX stock-data feed; the account must have access to it. See [Alpaca market-data access](https://docs.alpaca.markets/us/v1.4.2/docs/about-market-data-api).
- Set `GOOGLE_CLIENT_ID` using the Google sign-in steps under Frontend Setup below, **before starting the backend**.

Do not commit `.env`, share API secrets, or reuse the example secret.

### 3. Create the tables and HTTPS certificate

Still in `backend/`:

```powershell
npx.cmd prisma generate
npx.cmd prisma db push
New-Item -ItemType Directory -Force certs
```

`db push` creates the current tables in your new local database. If using an existing database and Prisma warns about data loss, inspect the changes rather than forcing them. The migration history alone does not yet create every trading table.

Generate the local certificate with OpenSSL:

```powershell
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -keyout certs/key.pem -out certs/cert.pem -days 365 -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2" -addext "basicConstraints=critical,CA:TRUE"
Copy-Item certs/cert.pem ../frontend/app/src/main/res/raw/cert.pem
```

If PowerShell cannot find `openssl`, replace `openssl` in that command with `& "C:\Program Files\Git\usr\bin\openssl.exe"` after confirming that file exists. Run the command in PowerShell, not Git Bash. On macOS/Linux, use your installed `openssl` command.

The frontend must have the same **public certificate** as the backend. Never copy the private `key.pem` into the frontend. Regenerating the certificate requires restarting the backend and rebuilding the app.

---

## Frontend Setup

### 1. Open the project and configure the emulator

1. Open the **`frontend/` folder** in Android Studio and allow Gradle to sync.
2. In **SDK Manager**, install Android SDK Platform **35**, Platform-Tools, and Android Emulator if needed.
3. In **Device Manager → Create Device**, choose a phone and an API 35 **Google Play** system image. Start the emulator and sign in to a Google account through its Play Store or Settings.
4. Under **Settings → Build, Execution, Deployment → Build Tools → Gradle**, select JDK 17 or a compatible Android Studio bundled JDK. For terminal Gradle commands, set `JAVA_HOME` to the same JDK.

### 2. Configure Google sign-in

Google must recognize your app's package name and your computer's debug signing certificate. This registration is required when building the app yourself.

1. In Google Cloud Console, create/select a project. Open **Google Auth Platform**, configure the app's branding/contact information, and choose an audience. If using testing mode, add your test Google accounts under **Audience → Test users**.
2. In a terminal opened in `frontend/`, run:

   ```powershell
   .\gradlew.bat signingReport
   ```

   Copy the **SHA-1 under the debug variant**.
3. Under **Google Auth Platform → Clients → Create client**, create an **Android** OAuth client using:
   - Package name: `com.example.cpen321application`
   - SHA-1: the debug fingerprint from the previous step.
4. In the **same Google Cloud project**, create a **Web application** OAuth client. Copy its **client ID** into `GOOGLE_CLIENT_ID` in both `backend/.env` and `frontend/local.properties` below. Do not use the Android client ID or Web client secret.

If using your group's existing Google Cloud project instead, its owner must register your debug SHA-1/package and give you the matching Web client ID and any required test-user access. See [Google's sign-in setup](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation).

### 3. Configure the frontend

Open `frontend/local.properties`. Android Studio normally creates this file with `sdk.dir`; keep the path it generated and add the other settings below. If the file does not exist, copy `local.properties.example` and replace its sample values.

```properties
sdk.dir=C:/Users/YOUR_USERNAME/AppData/Local/Android/Sdk
API_BASE_URL=https://10.0.2.2:3000/
MARKET_API_BASE_URL=https://10.0.2.2:3000/
GOOGLE_CLIENT_ID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

Use your actual SDK path from SDK Manager, and leave `DEBUG_APPLICATION_ID_SUFFIX` unset. `10.0.2.2` lets the Android emulator reach your computer. Both URLs must point to the full backend on port **3000**, not the old remote address in the example file.

---

## Build and Run

### 1. Start the backend

In a terminal opened in **`backend/`**:

```powershell
npm.cmd run dev
```

Keep this terminal open. Expect `Server listening`. This starts the **full backend**, including Market; you do not need `npm run dev:market` or Docker for this setup.

In a second terminal opened in `backend/`, check the connection:

```powershell
curl.exe --cacert certs/cert.pem https://localhost:3000/health
curl.exe --cacert certs/cert.pem https://localhost:3000/api/market/stocks
```

The first request should return `{"status":"ok"}`. The second should return six stocks with prices and `status: "ok"`. If a stock has an `error`, check the Alpaca settings. The health response alone does not test the database or market provider.

### 2. Run the app

Select the emulator in Android Studio and click **Run ▶**. Sign in with your configured Google account. A new user and $10,000 virtual-cash account are created automatically; no seed command is needed.

Rebuild and reinstall after changing `local.properties` or `cert.pem`. Restart the backend after changing `.env`. If switching from another backend, sign out and sign back in.

### Subsequent runs

Once setup is complete:

1. Ensure MySQL is running—normally automatic.
2. Run `npm.cmd run dev` from `backend/` and keep it open.
3. Start the emulator and open TradeOff, or click **Run ▶** if the app changed.

Stop the backend with **Ctrl+C** when finished. Do not rerun database creation, regenerate certificates, or reinstall dependencies every time.

---

## Testing the App

- **Account:** Sign in, edit the display name/username, save, and reopen the screen. Usernames must be unique and use 3–24 lowercase letters, digits, or underscores.
- **Market and trading:** Check stock prices/charts, buy a small quantity, then sell some owned shares. Check that invalid quantities and insufficient balances are rejected. Try a limit order and cancel it if it remains pending.
- **Portfolio:** Confirm cash, holdings, pending orders, and recent transactions reflect your trades.
- **Social:** Use a second Google account on another emulator connected to the same backend. Send and accept a friend request, then check the leaderboard.

For automated checks, run from `frontend/`:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Run from `backend/`:

```powershell
npm.cmd run build
npm.cmd test -- --runInBand --testPathPatterns=tests/mock
```

---

## Troubleshooting

| Problem | Fix |
| --- | --- |
| Cannot connect to MySQL / port 3306 | Start the MySQL service and check `DATABASE_URL` and the database user's password. |
| Google sign-in fails | Check the debug SHA-1/package registration, test-user access, Google Play emulator, and matching **Web** client IDs. |
| Certificate / `Trust anchor` error | Copy the backend's current `cert.pem` to the frontend's `app/src/main/res/raw/cert.pem`, then rebuild/reinstall. |
| `MARKET_NOT_CONFIGURED` | Set both Alpaca variables in `backend/.env`, start from `backend/`, and restart the backend. |
| Alpaca rejects credentials | Check the matching key/secret pair and IEX access; update both values and restart after regenerating keys. |
| Portfolio shows an error | Confirm both URLs use `https://10.0.2.2:3000/`, the full backend is running, and `prisma db push` completed. Check the backend terminal for the exact error. |
| Account/Portfolio stops working after being signed in | Tokens expire after 15 minutes; sign out and back in. |
| Port 3000 is already in use | Stop the older backend process before starting another one. |
| PowerShell blocks `npm.ps1` | Use `npm.cmd` and `npx.cmd` as shown above. |

The checked-in Docker configuration still targets an older backend setup; use the local instructions above. Market quotes may remain unchanged between refreshes even when the connection works.
