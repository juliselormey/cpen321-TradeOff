/** True when a thrown value carries the given Prisma error code (e.g. P2002, P2034). */
export function hasErrorCode(error: unknown, code: string): boolean {
    return typeof error === "object" && error !== null && "code" in error && error.code === code;
}