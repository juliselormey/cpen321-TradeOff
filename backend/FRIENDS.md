# Friends API

All endpoints require `Authorization: Bearer <access-token>` from the existing login.
Friend requests require recipient consent. Add by user UUID (`user.id` from
`POST /api/auth/me`); display names are not unique and are not usernames.
Users from the original integer-ID database retain their IDs as numeric strings
(for example, `"12"`), which are also accepted by the API.

| Method | Path | Behavior |
| --- | --- | --- |
| POST | `/api/friends/requests` | Body: `{ "userId": "<recipient UUID>" }`. Returns 201 `{ request }`. |
| GET | `/api/friends/requests` | Returns `{ incoming: [], outgoing: [] }` for pending requests. |
| POST | `/api/friends/requests/:id/accept` | Recipient accepts a pending request. Returns 204. |
| DELETE | `/api/friends/requests/:id` | Recipient declines or sender cancels. Returns 204. |
| GET | `/api/friends` | Returns `{ friends: [{ friendshipId, user: { id, displayName, avatarUrl } }] }`. |
| DELETE | `/api/friends/:id` | Either participant removes an accepted friendship using `friendshipId`. Returns 204. |

Request objects include `id`, `senderId`, `recipientId`, `pairKey`, `status`,
`createdAt`, and public `sender` / `recipient` profiles. Emails, Google IDs,
balances, and tokens are excluded. Lists are newest-request-first.

Invalid targets or self-requests return 400; unavailable targets return 404;
duplicate requests, reciprocal requests, and existing friendships return 409.
Reciprocal requests do not automatically accept: accept the incoming request instead.
Missing authentication returns 401; banned callers receive 403. Missing requests,
wrong-state requests, and requests the caller cannot modify return 404.
Declining, cancelling, or removing deletes the relationship so a new request can be sent.

## Database setup

From `backend`, configure `DATABASE_URL` and the existing authentication environment
variables (see `.env.example`), then run:

```sh
npm ci --legacy-peer-deps
npx prisma generate
npx prisma migrate deploy
npm run dev
```

The legacy peer flag works around missing optional peer entries in this branch's
existing lockfile with npm 11. The string-user-IDs migration first aligns the
original integer columns with the current Prisma schema, preserving existing
IDs and account/token links. The next migration creates the Friendship table.
A unique canonical user-pair key prevents
simultaneous requests from creating two relationships. Acceptance and deletion
use conditional database writes to enforce caller ownership and request state.

The Android Friends screen still uses sample data. Connect its add dialog and
friend/request lists to these endpoints separately.

## Verification

```sh
npm run typecheck
npm run build
npm test -- --runInBand
```

`tests/mock/friends.test.ts` uses the real JWT middleware and a mocked database.
To verify persistence against MySQL after migration, use two signed-in users:
send a request, check both request lists, accept as the recipient, confirm each
user sees the other in their friends list, remove the friendship, and resend.
Also send simultaneous requests in both directions: only one should return 201,
with the other returning 409. An unrelated third user must receive 404 when
attempting to accept or delete the relationship.
