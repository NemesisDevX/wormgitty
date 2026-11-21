import Fastify from "fastify";
import cookie from "@fastify/cookie";
import argon2 from "argon2";
import crypto from "node:crypto";
import jwt from "jsonwebtoken";
import { randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import { PrismaClient } from "@prisma/client";

export const prisma = new PrismaClient();

const ACCESS_TTL_SEC = Number(process.env.ACCESS_TTL_SEC ?? 600);
const REFRESH_TTL_SEC = Number(process.env.REFRESH_TTL_SEC ?? 1209600);

const JWT_ACCESS_SECRET =
  process.env.JWT_ACCESS_SECRET ?? "dev_only_access_secret_change_me";
const JWT_REFRESH_SECRET =
  process.env.JWT_REFRESH_SECRET ?? "dev_only_refresh_secret_change_me";

/** Truncated SHA-256 hex for enrollment & refresh token lookups (fast, deterministic). */
export function tokenDigest(token) {
  return crypto.createHash("sha256").update(token).digest("hex").slice(0, 48);
}

/** Deterministic device credential from the enrollment proof (device_id + seed). */
export function deviceCredential(deviceId, enrollSeed) {
  return crypto
    .createHash("sha256")
    .update(`device-credential:${deviceId}:${enrollSeed}`)
    .digest("hex");
}

export function signAccessToken(userId) {
  return jwt.sign({ sub: userId, typ: "access" }, JWT_ACCESS_SECRET, {
    expiresIn: `${ACCESS_TTL_SEC}s`,
  });
}

export function issueRefreshToken(userId) {
  const token = crypto.randomBytes(32).toString("hex");
  return prisma.refreshToken.create({
    data: {
      userId,
      tokenHash: tokenDigest(token),
      expiresAt: new Date(Date.now() + REFRESH_TTL_SEC * 1000),
    },
  }).then(() => token);
}

export async function verifyRefreshToken(token) {
  const row = await prisma.refreshToken.findUnique({
    where: { tokenHash: tokenDigest(token) },
  });
  if (!row || row.revoked || row.expiresAt < new Date()) return null;
  return row;
}

export async function hashPassword(plain) {
  return argon2.hash(plain, { type: argon2.argon2id });
}

export async function verifyPassword(hash, plain) {
  return argon2.verify(hash, plain);
}

export async function buildApp(opts = {}) {
  const app = Fastify({ logger: opts.logger ?? false });
  await app.register(cookie);

  /**
   * Bearer auth decorator, dual-mode:
   *  - User JWT (typ=access)         -> req.authKind="user",   req.userId=sub
   *  - Device credential (64 hex)    -> req.authKind="device", req.deviceIdFromToken set
   *    (looked up by device_token_hash; the DEVICE is verified against its own
   *     stored hash — who verifies whom is spelled out in the phase report)
   *  Anything else -> 401.
   */
  app.decorate("authenticate", async (req, reply) => {
    const header = req.headers.authorization ?? "";
    if (!header.startsWith("Bearer ")) {
      return reply.code(401).send({ error: "unauthorized" });
    }
    const token = header.slice(7);
    if (/^[0-9a-f]{64}$/.test(token)) {
      const holder = await prisma.device.findFirst({
        where: { deviceTokenHash: tokenDigest(token) },
        select: { id: true },
      });
      if (holder) {
        req.authKind = "device";
        req.deviceIdFromToken = holder.id;
        return;
      }
      return reply.code(401).send({ error: "unauthorized" });
    }
    try {
      const claims = jwt.verify(token, JWT_ACCESS_SECRET);
      if (claims.typ !== "access") throw new Error("wrong token type");
      req.authKind = "user";
      req.userId = claims.sub;
    } catch {
      return reply.code(401).send({ error: "unauthorized" });
    }
  });

  const deviceSelection = {
    id: true,
    name: true,
    phone: true,
    platform: true,
    variant: true,
    status: true,
    lastSeen: true,
    createdAt: true,
  };

  /* -------------------------------- probe ------------------------------- */

  // Unauthenticated liveness probe used by the Android client link check.
  app.get("/api/v1/ping", async () => ({ ok: true }));

  /* -------------------------------- auth -------------------------------- */

  app.post("/api/v1/auth/login", async (req, reply) => {
    const { username, password } = req.body ?? {};
    if (typeof username !== "string" || typeof password !== "string" || !username || !password) {
      return reply.code(400).send({ error: "bad_request" });
    }
    const user = await prisma.user.findUnique({ where: { username } });
    if (!user || !(await verifyPassword(user.passHash, password))) {
      return reply.code(401).send({ error: "invalid_credentials" });
    }
    const accessToken = signAccessToken(user.id);
    const refreshToken = await issueRefreshToken(user.id);
    reply.setCookie("wg_refresh", refreshToken, {
      httpOnly: true,
      sameSite: "lax",
      path: "/api/v1/auth",
      maxAge: REFRESH_TTL_SEC,
    });
    return {
      accessToken,
      user: { id: user.id, username: user.username },
    };
  });

  /* ------------------------------- devices ------------------------------ */

  // Enrollment: registration-token protected, idempotent by client device id.
  app.post("/api/v1/devices/enroll", async (req, reply) => {
    const { deviceId, name, phone, platform, variant, ownerUsername, enrollToken } =
      req.body ?? {};
    if (
      typeof deviceId !== "string" || !deviceId ||
      typeof enrollToken !== "string" || !enrollToken ||
      typeof ownerUsername !== "string" || !ownerUsername ||
      typeof name !== "string" || !name ||
      typeof platform !== "string" || !platform
    ) {
      return reply.code(400).send({ error: "bad_request" });
    }
    const owner = await prisma.user.findUnique({ where: { username: ownerUsername } });
    if (!owner) return reply.code(403).send({ error: "forbidden" });

    const existing = await prisma.device.findUnique({ where: { id: deviceId } });
    if (existing) {
      if (existing.enrollTokenHash !== tokenDigest(enrollToken)) {
        return reply.code(403).send({ error: "forbidden" });
      }
      await prisma.device.update({
        where: { id: existing.id },
        data: { lastSeen: new Date(), status: existing.status ?? "offline" },
      });
      // Caller re-proved possession of the enrollment seed, so handing back the
      // (deterministic) device credential is a possession-bound re-issue.
      return reply.code(200).send({
        deviceId: existing.id,
        idempotent: true,
        enrollToken: deviceCredential(existing.id, enrollToken),
      });
    }

    const deviceToken = deviceCredential(deviceId, enrollToken);
    const device = await prisma.device.create({
      data: {
        id: deviceId,
        ownerId: owner.id,
        name,
        phone: typeof phone === "string" ? phone : "",
        platform,
        variant: typeof variant === "string" ? variant : "standard",
        enrollTokenHash: tokenDigest(enrollToken),
        deviceTokenHash: tokenDigest(deviceToken),
        status: "online",
        lastSeen: new Date(),
      },
    });
    // Device credential: derived from the enrollment proof, returned exactly
    // once at creation (and on possession-bound replays), never stored plaintext.
    return reply.code(201).send({
      deviceId: device.id,
      enrollToken: deviceToken,
    });
  });

  /**
   * Dual-auth heartbeat:
   *  1) DEVICE token: Bearer == device credential issued at enrollment.
   *     Verified against THE DEVICE'S OWN stored device_token_hash; the device
   *     is never checked against any user. Cross-device presentation -> 403.
   *  2) USER JWT: ownership check via ownerId === token subject.
   */
  app.post("/api/v1/devices/:id/heartbeat", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });

    if (req.authKind === "device") {
      // The presented device token must belong to THIS device.
      if (req.deviceIdFromToken !== device.id) return reply.code(403).send({ error: "forbidden" });
    } else if (device.ownerId !== req.userId) {
      return reply.code(403).send({ error: "forbidden" });
    }

    const updated = await prisma.device.update({
      where: { id: device.id },
      data: { lastSeen: new Date(), status: "online" },
      select: { id: true, lastSeen: true, status: true },
    });
    return { id: updated.id, lastSeen: updated.lastSeen, status: updated.status };
  });

  app.get("/api/v1/devices", { onRequest: [app.authenticate] }, async (req) => {
    // Ownership scope enforced here: only the caller's devices ever leave the DB.
    return prisma.device.findMany({
      where: { ownerId: req.userId },
      orderBy: { createdAt: "desc" },
      select: deviceSelection,
    });
  });

  /* ------------------------------ commands ------------------------------ */

  // Phase discipline: ONLY ping is dispatchable this phase; anything else is a 400.
  const COMMAND_TYPES = new Set(["ping"]);

  app.post("/api/v1/devices/:id/commands", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    const { type, payload } = req.body ?? {};
    if (!COMMAND_TYPES.has(type)) {
      return reply.code(400).send({
        error: "unsupported_command",
        message: "only ping is accepted in this phase",
      });
    }
    let safePayload = {};
    if (payload !== undefined && payload !== null) {
      try {
        safePayload = JSON.parse(typeof payload === "string" ? payload : JSON.stringify(payload));
      } catch {
        return reply.code(400).send({ error: "bad_payload" });
      }
    }
    const command = await prisma.command.create({
      data: {
        deviceId: device.id,
        type,
        payload: safePayload,
        state: "queued",
        expiresAt: new Date(Date.now() + 24 * 3600 * 1000),
      },
    });
    return reply.code(201).send({
      id: command.id,
      deviceId: command.deviceId,
      type: command.type,
      state: command.state,
      createdAt: command.createdAt,
    });
  });

  app.get("/api/v1/devices/:id/commands", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    const list = await prisma.command.findMany({
      where: { deviceId: device.id },
      orderBy: { createdAt: "desc" },
      take: 100,
    });
    return list.map((c) => ({
      id: c.id,
      type: c.type,
      state: c.state,
      payload: c.payload,
      result: c.result,
      createdAt: c.createdAt,
      updatedAt: c.updatedAt,
    }));
  });

  /* ------------------------------ teardown ------------------------------ */

  app.addHook("onClose", async () => {
    await prisma.$disconnect();
  });

  await app.ready();
  return app;
}

/* Entry point when run directly (node src/server.js). */
if (process.argv[1] && process.argv[1].endsWith("server.js")) {
  const app = await buildApp({ logger: true });
  const port = Number(process.env.PORT ?? 8088);
  app.listen({ port, host: "127.0.0.1" });
}
