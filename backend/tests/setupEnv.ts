// Local defaults for test-only auth/database setup. CI and developers can
// provide their own database URL; provider credentials must never be embedded
// in the test bootstrap.
process.env.DATABASE_URL ??= "mysql://root:tradeoff@127.0.0.1:3306/tradeoff_e2e_test";
process.env.JWT_SECRET ??= "test-only-jwt-secret";
process.env.GOOGLE_CLIENT_ID ??= "test-google-client-id";
process.env.E2E_USER_EMAIL ??= "test-user@test.invalid";
