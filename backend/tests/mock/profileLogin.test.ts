import { findOrCreateUser } from "../../src/auth/service";
import { db } from "../../src/prisma/db";
jest.mock("../../src/config/env", () => ({ requireEnv: (name: string) => name + "-test" }));
jest.mock("../../src/prisma/db", () => ({ db: {
    user: { findUnique: jest.fn(), update: jest.fn(), create: jest.fn() },
} }));
const users = jest.mocked(db.user);
beforeEach(() => jest.resetAllMocks());
test("Google login preserves a customized name and username", async () => {
    users.findUnique.mockResolvedValue({ id: "self", displayName: "Custom", username: "custom" } as never);
    await findOrCreateUser({ googleSub: "google-id", email: "new@example.com", name: "Google Name" });
    expect(users.update).toHaveBeenCalledWith({ where: { id: "self" }, data: { email: "new@example.com" } });
});
test("first login still initializes display name from Google", async () => {
    users.findUnique.mockResolvedValue(null);
    await findOrCreateUser({ googleSub: "google-id", email: "new@example.com", name: "Google Name" });
    expect(users.create).toHaveBeenCalledWith({ data: {
        googleSub: "google-id", email: "new@example.com", displayName: "Google Name",
        account: { create: { cashBalance: "10000", startingBalance: "10000" } },
    } });
});
