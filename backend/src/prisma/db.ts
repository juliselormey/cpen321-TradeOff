import "dotenv/config";
import { PrismaMariaDb } from "@prisma/adapter-mariadb";
import { PrismaClient } from "../generated/prisma/client";
import { requireEnv } from "../config/env"
/* eslint-disable @typescript-eslint/no-unsafe-argument, @typescript-eslint/no-unsafe-call,
@typescript-eslint/no-unsafe-assignment, @typescript-eslint/no-unsafe-member-access

 Codacy analyzes without node_modules or the generated Prisma client, so these
 imports resolve to `any` there. tsc resolves them correctly. */
const url = new URL(requireEnv("DATABASE_URL"));

const adapter = new PrismaMariaDb({
  host: url.hostname,
  port: Number(url.port) || 3306,
  user: decodeURIComponent(url.username),
  password: decodeURIComponent(url.password),
  database: url.pathname.slice(1),
});

export const db = new PrismaClient({ adapter });