# Settings and profile deployment

The Android app has a Settings tab with Google sign-in/sign-out, a persistent
device-local light/dark preference, and account display-name/username editing.

Light mode is the default. Usernames are normalized to lowercase, unique, and
3–24 ASCII letters, digits or underscores. Display names are 1–60 characters.
Existing users can choose a username the first time they edit their profile.
Google login preserves names the user has edited.

## Backend rollout

Upload the updated backend source, Prisma schema, migrations, and Dockerfile to
the deployed project before running commands. Merely restarting the old image
will not add the profile endpoint.

For the existing MySQL-based server Compose deployment, from its project folder:

    sudo docker compose build backend
    sudo docker compose run --rm backend npx prisma migrate deploy
    sudo docker compose up -d --force-recreate backend

The migration adds one nullable username column and a unique index; existing
users remain valid. Run migration before starting the new backend image. Do not
use the repository's old MongoDB sample Compose file for this MySQL deployment.

The image build generates Prisma's client from the new schema. Database URL and
Google client ID remain supplied by the server's Compose configuration.

## API

- GET /api/users/me: authenticated profile (id, email, displayName, username).
- PATCH /api/users/me: authenticated update with displayName and username only.
- 400 for invalid input, 401 for missing/expired session, 403 for a banned account,
  409 for a username collision.
- Existing POST /api/auth/me remains supported.

The frontend shows a clear error until the server supports these endpoints.
The screen handles expired sessions with Sign in again. Sign out clears the
local token and Credential Manager state; appearance stays saved on the device.

## Manual verification

1. Open Settings signed out; verify sign-in is offered.
2. Switch to dark mode, visit Market and Social/Portfolio, then restart the app:
   the preference should persist. Switch back to light mode.
3. Sign in, change name and username, save, and reopen Settings.
4. Try a duplicate username; the app should retain the edits and show an error.
5. Sign out, then revisit Social/Portfolio: authentication should be required.
6. Sign in again: the customized display name must remain unchanged.

## Local quality checks

From the backend folder, run npm ci, npm run lint, npm run typecheck, and npm run build.
The lint command uses the root .eslintrc.json, resolves plugins installed in the
backend, and checks source, tests, and the lint runner. Generated Prisma code is
excluded. The compiled lint runner is generated under ignored .lint-tools/.
