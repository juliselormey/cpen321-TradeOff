import request from "supertest";
import jwt from "jsonwebtoken";
import { createApp } from "../../src/app";
import { db } from "../../src/prisma/db";
import { requireEnv } from "../../src/config/env";

const app = createApp();
const JWT_SECRET = requireEnv("JWT_SECRET");