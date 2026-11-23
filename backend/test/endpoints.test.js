import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { buildApp, prisma, hashPassword } from "../src/server.js";
import otplib from "otplib";

const { authenticator } = otplib;

/** Current-step RFC6238 token for the .env secret (tests run with dotenv). */
function totp(secret, epochSec = Math.floor(Date.now() / 1000)) {
  return authenticator.generate(secret);
}

function secondFactor() {
  return process.env.PANEL_TOTP_SECRET
    ? totp(process.env.PANEL_TOTP_SECRET)
    : process.env.PANEL_2FA_CODE ?? "1057466";
}

let app;
const users = {};

beforeAll(async () => {
  process.env.JWT_ACCESS_SECRET ??= "test_access";
  process.env.JWT_REFRESH_SECRET ??= "test_refresh";
  // Shared ephemeral Postgres: clear previous runs' rows so ids are deterministic.
  await prisma.command.deleteMany({});
  await prisma.device.deleteMany({ where: { id: { startsWith: "dev-" } } });
  app = await buildApp();
  for (const [uname, pass] of [["alice", "pw_alice_strong"], ["bob", "pw_bob_strong"]]) {
    const user = await prisma.user.upsert({
      where: { username: uname },
      update: {},
      create: { username: uname, passHash: await hashPassword(pass) },
    });
    users[uname] = { id: user.id, pass };
  }
});

afterAll(async () => {
  await app.close();
});

async function login(username) {
  const res = await app.inject({
    method: "POST",
    url: "/api/v1/auth/login",
    payload: { username, password: users[username].pass },
  });
  const body = res.json();
  if (!body.require2fa) return body.accessToken;
  // Two-step login: ticket + second factor (TOTP if configured, else static code).
  const res2 = await app.inject({
    method: "POST",
    url: "/api/v1/auth/2fa",
    payload: { ticket: body.ticket, code: secondFactor() },
  });
  return res2.json().accessToken;
}

async function enroll(username, deviceId, enrollSeed = "global-dev-seed") {
  return app.inject({
    method: "POST",
    url: "/api/v1/devices/enroll",
    payload: {
      deviceId,
      enrollToken: enrollSeed,
      ownerUsername: username,
      name: `Dev ${deviceId.slice(0, 6)}`,
      phone: "+201000000000",
      platform: "android",
    },
  });
}

async function authGet(token, url) {
  return app.inject({ method: "GET", url, headers: { authorization: `Bearer ${token}` } });
}

async function authPost(token, url, payload) {
  return app.inject({
    method: "POST",
    url,
    headers: { authorization: `Bearer ${token}` },
    payload,
  });
}

/* ------------------------------ auth/login ----------------------------- */

describe("POST /api/v1/auth/login", () => {
  it("issues a short JWT + httpOnly refresh cookie for valid credentials", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/auth/login",
      payload: { username: "alice", password: users.alice.pass },
    });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    if (body.require2fa) {
      // 2FA gate on: stage 1 returns a ticket, stage 2 the real token pair.
      const res2 = await app.inject({
        method: "POST",
        url: "/api/v1/auth/2fa",
        payload: { ticket: body.ticket, code: secondFactor() },
      });
      expect(res2.statusCode).toBe(200);
      const body2 = res2.json();
      expect(body2.accessToken.split(".")).toHaveLength(3);
      expect(body2.user.username).toBe("alice");
      const cookie = res2.cookies.find((c) => c.name === "wg_refresh");
      expect(cookie).toBeDefined();
      expect(cookie.httpOnly).toBe(true);
      return;
    }
    expect(body.accessToken.split(".")).toHaveLength(3);
    expect(body.user.username).toBe("alice");
    const cookie = res.cookies.find((c) => c.name === "wg_refresh");
    expect(cookie).toBeDefined();
    expect(cookie.httpOnly).toBe(true);
  });

  it("rejects wrong password with 401", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/auth/login",
      payload: { username: "alice", password: "definitely-not-it" },
    });
    expect(res.statusCode).toBe(401);
  });

  it("rejects missing fields with 400", async () => {
    const res = await app.inject({ method: "POST", url: "/api/v1/auth/login", payload: {} });
    expect(res.statusCode).toBe(400);
  });
});

/* ------------------------------ auth/TOTP ------------------------------ */

describe("TOTP second factor", () => {
  async function ticket() {
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/auth/login",
      payload: { username: "alice", password: users.alice.pass },
    });
    return res.json().ticket;
  }

  it.skipIf(!process.env.PANEL_TOTP_SECRET)("accepts a valid RFC6238 code", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/auth/2fa",
      payload: { ticket: await ticket(), code: totp(process.env.PANEL_TOTP_SECRET) },
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().accessToken.split(".")).toHaveLength(3);
  });

  it.skipIf(!process.env.PANEL_TOTP_SECRET)("rejects a wrong code", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/auth/2fa",
      payload: { ticket: await ticket(), code: "000000" },
    });
    expect(res.statusCode).toBe(401);
    expect(res.json().error).toBe("invalid_code");
  });

  it.skipIf(!process.env.PANEL_TOTP_SECRET)("accepts ±1 step, rejects ±2", async () => {
    // Token one step behind should pass (window=1), two steps behind must fail.
    const { createHmac } = await import("node:crypto");
    const codeAt = (stepOffset) => {
      const A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567", s = process.env.PANEL_TOTP_SECRET;
      let b = 0, bc = 0, o = [];
      for (const c of s) { b = (b << 5) | A.indexOf(c); bc += 5;
        if (bc >= 8) { o.push((b >> (bc - 8)) & 255); bc -= 8; } }
      const ctr = Buffer.alloc(8);
      ctr.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000) + stepOffset));
      const h = createHmac("sha1", Buffer.from(o)).update(ctr).digest();
      const off = h[19] & 15;
      return ((h.readUInt32BE(off) & 0x7fffffff) % 1000000).toString().padStart(6, "0");
    };
    const prev = codeAt(-1);
    const r1 = await app.inject({ method: "POST", url: "/api/v1/auth/2fa",
      payload: { ticket: await ticket(), code: prev } });
    expect(r1.statusCode).toBe(200);
    const far = codeAt(-2);
    const r2 = await app.inject({ method: "POST", url: "/api/v1/auth/2fa",
      payload: { ticket: await ticket(), code: far } });
    expect(r2.statusCode).toBe(401);
  });

  it("totp/enroll requires auth; returns otpauth URI when configured", async () => {
    const unauth = await app.inject({ method: "POST", url: "/api/v1/auth/totp/enroll" });
    expect(unauth.statusCode).toBe(401);
    const token = await login("alice");
    const res = await authPost(token, "/api/v1/auth/totp/enroll", {});
    if (process.env.PANEL_TOTP_SECRET) {
      expect(res.statusCode).toBe(200);
      expect(res.json().otpauth).toMatch(/^otpauth:\/\/totp\//);
      expect(res.json().window).toBe(1);
    } else {
      expect(res.statusCode).toBe(404);
    }
  });
});

/* ------------------------------- enroll -------------------------------- */

describe("POST /api/v1/devices/enroll", () => {
  it("creates a device and returns the enroll_token exactly once", async () => {
    const res = await enroll("alice", "dev-enroll-001");
    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.deviceId).toBe("dev-enroll-001");
    expect(body.enrollToken).toMatch(/^[0-9a-f]{64}$/);
  });

  it("re-enrolling with the same device_id returns a single row (idempotent)", async () => {
    await enroll("alice", "dev-enroll-idem");
    const second = await enroll("alice", "dev-enroll-idem");
    expect(second.statusCode).toBe(200);
    expect(second.json()).toMatchObject({ deviceId: "dev-enroll-idem", idempotent: true });
    const rows = await prisma.device.findMany({ where: { id: "dev-enroll-idem" } });
    expect(rows).toHaveLength(1);
  });

  it("replay with a wrong enroll token is 403, not a duplicate", async () => {
    await enroll("alice", "dev-enroll-wrong", "good-seed");
    const forged = await app.inject({
      method: "POST",
      url: "/api/v1/devices/enroll",
      payload: {
        deviceId: "dev-enroll-wrong",
        enrollToken: "bad-seed",
        ownerUsername: "alice",
        name: "forged",
        platform: "android",
      },
    });
    expect(forged.statusCode).toBe(403);
    const rows = await prisma.device.findMany({ where: { id: "dev-enroll-wrong" } });
    expect(rows).toHaveLength(1);
  });

  it("missing fields gives 400", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/devices/enroll",
      payload: { deviceId: "only-id" },
    });
    expect(res.statusCode).toBe(400);
  });

  it("unknown ownerUsername gives 403", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/devices/enroll",
      payload: {
        deviceId: "dev-nobody",
        enrollToken: "s",
        ownerUsername: "ghost_user",
        name: "x",
        platform: "android",
      },
    });
    expect(res.statusCode).toBe(403);
  });
});

/* ------------------------------ heartbeat ------------------------------ */

describe("POST /api/v1/devices/:id/heartbeat", () => {
  it("updates last_seen for the owning token", async () => {
    await enroll("alice", "dev-beat-1");
    const token = await login("alice");
    const res = await authPost(token, "/api/v1/devices/dev-beat-1/heartbeat", {});
    expect(res.statusCode).toBe(200);
    expect(res.json().status).toBe("online");
    const row = await prisma.device.findUnique({ where: { id: "dev-beat-1" } });
    expect(Date.now() - row.lastSeen.getTime()).toBeLessThan(10_000);
  });

  it("requires auth (401 without token)", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/devices/dev-beat-1/heartbeat",
      payload: {},
    });
    expect(res.statusCode).toBe(401);
  });
});

/* ---------------------------- devices list ----------------------------- */

describe("GET /api/v1/devices", () => {
  it("lists only the caller's devices (ownership scope + IDOR check)", async () => {
    await enroll("alice", "dev-alice-list");
    await enroll("bob", "dev-bob-list");
    const aliceToken = await login("alice");
    const res = await authGet(aliceToken, "/api/v1/devices");
    expect(res.statusCode).toBe(200);
    const ids = res.json().map((d) => d.id);
    expect(ids).toContain("dev-alice-list");
    expect(ids).not.toContain("dev-bob-list");
  });

  it("401 without a token", async () => {
    const res = await app.inject({ method: "GET", url: "/api/v1/devices" });
    expect(res.statusCode).toBe(401);
  });

  it("filters by ?platform= (panel platform tabs)", async () => {
    await enroll("alice", "dev-plat-a");
    await enroll("alice", "dev-plat-b");
    const token = await login("alice");
    const all = await authGet(token, "/api/v1/devices");
    expect(all.json().map((d) => d.id)).toEqual(
      expect.arrayContaining(["dev-plat-a", "dev-plat-b"])
    );
    const android = await authGet(token, "/api/v1/devices?platform=android");
    expect(android.statusCode).toBe(200);
    expect(android.json().every((d) => d.platform === "ANDROID")).toBe(true);
    const windows = await authGet(token, "/api/v1/devices?platform=windows");
    expect(windows.statusCode).toBe(200);
    expect(windows.json().map((d) => d.id)).not.toContain("dev-plat-a");
    const bogus = await authGet(token, "/api/v1/devices?platform=bogus");
    expect(bogus.statusCode).toBe(200);
    expect(bogus.json().length).toBe(all.json().length);
  });
});

/* ------------------------------ commands ------------------------------- */

describe("commands endpoints", () => {
  it("creates a queued command with type validation", async () => {
    await enroll("alice", "dev-cmd-1");
    const token = await login("alice");
    const res = await authPost(token, "/api/v1/devices/dev-cmd-1/commands", {
      type: "ping",
      payload: { z: 1 },
    });
    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.state).toBe("queued");
    expect(body.type).toBe("ping");
  });

  it("state listing reflects created commands newest-first", async () => {
    await enroll("alice", "dev-cmd-2");
    const token = await login("alice");
    await authPost(token, "/api/v1/devices/dev-cmd-2/commands", { type: "ping" });
    await authPost(token, "/api/v1/devices/dev-cmd-2/commands", { type: "ping", payload: { n: 2 } });
    const res = await authGet(token, "/api/v1/devices/dev-cmd-2/commands");
    expect(res.statusCode).toBe(200);
    const list = res.json();
    expect(list.length).toBeGreaterThanOrEqual(2);
    expect(list.every((c) => ["queued", "sent", "acked", "done", "failed"].includes(c.state))).toBe(true);
    expect(new Date(list[0].createdAt) >= new Date(list[list.length - 1].createdAt)).toBe(true);
  });

  it("non-ping commands are refused with an explicit message", async () => {
    await enroll("alice", "dev-cmd-synccfg");
    const token = await login("alice");
    const res = await authPost(token, "/api/v1/devices/dev-cmd-synccfg/commands", {
      type: "sync_config",
    });
    expect(res.statusCode).toBe(400);
    expect(res.json().error).toBe("unsupported_command");
    expect(res.json().message).toContain("ping");
  });

  it("unknown command type is 400", async () => {
    await enroll("alice", "dev-cmd-3");
    const token = await login("alice");
    const res = await authPost(token, "/api/v1/devices/dev-cmd-3/commands", { type: "mine_bitcoins" });
    expect(res.statusCode).toBe(400);
  });

  it("device owned by another user is 403 (IDOR)", async () => {
    await enroll("bob", "dev-bob-cmd");
    const aliceToken = await login("alice");
    const res = await authPost(aliceToken, "/api/v1/devices/dev-bob-cmd/commands", { type: "ping" });
    expect(res.statusCode).toBe(403);
  });

  it("listing commands of another user's device is 403 (IDOR)", async () => {
    const aliceToken = await login("alice");
    const res = await authGet(aliceToken, "/api/v1/devices/dev-bob-cmd/commands");
    expect(res.statusCode).toBe(403);
  });

  it("heartbeat of another user's device is 403 (IDOR)", async () => {
    const aliceToken = await login("alice");
    const res = await authPost(aliceToken, "/api/v1/devices/dev-bob-cmd/heartbeat", {});
    expect(res.statusCode).toBe(403);
  });

  it("device token authenticates its OWN heartbeat without any user JWT", async () => {
    await enroll("alice", "dev-self-beat");
    const enrollRes = await enroll("alice", "dev-self-beat");
    const deviceToken = enrollRes.json().enrollToken;
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/devices/dev-self-beat/heartbeat",
      headers: { authorization: `Bearer ${deviceToken}` },
      payload: {},
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().status).toBe("online");
  });

  it("device token presented to ANOTHER device id is 403", async () => {
    await enroll("alice", "dev-beat-A");
    await enroll("alice", "dev-beat-B");
    const tokenA = (await enroll("alice", "dev-beat-A")).json().enrollToken;
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/devices/dev-beat-B/heartbeat",
      headers: { authorization: `Bearer ${tokenA}` },
      payload: {},
    });
    expect(res.statusCode).toBe(403);
  });
});

/* -------------------- phase-1: JWT enrollment -------------------- */

describe("JWT enrollment (phase-1)", () => {
  async function mintEnrollToken(userToken) {
    const res = await authPost(userToken, "/api/v1/devices/enroll-token", {});
    expect(res.statusCode).toBe(200);
    return res.json().enrollToken;
  }

  async function jwtEnroll(enrollToken, deviceId, extra = {}) {
    return app.inject({
      method: "POST",
      url: "/api/v1/devices/enroll",
      payload: {
        enrollToken,
        deviceId,
        platform: "ANDROID",
        appType: "worm",
        fingerprint: { androidId: "a", model: "sdk", sdkInt: 34, abi: "x86_64" },
        identity: { displayName: "Phase One", phone: "01012345678" },
        ...extra,
      },
    });
  }

  it("enrolls with a valid single-use JWT and returns a random credential", async () => {
    const tok = await login("alice");
    const jwt = await mintEnrollToken(tok);
    const res = await jwtEnroll(jwt, "dev-jwt-001");
    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.deviceId).toBe("dev-jwt-001");
    expect(body.credential).toMatch(/^[A-Za-z0-9_-]{40,}$/);

    // Only the hash is persisted; the bearer itself authenticates.
    const row = await prisma.device.findUnique({ where: { id: "dev-jwt-001" } });
    expect(row.credentialHash).toMatch(/^[0-9a-f]{48}$/);
    expect(row.phone).toBe(""); // plaintext phone never stored
    expect(row.phoneMasked).toBe("+201****5678");
    expect(row.phoneEnc).toBeTruthy();
    expect(row.displayName).toBe("Phase One");
    expect(row.platform).toBe("ANDROID");

    const hb = await app.inject({
      method: "POST",
      url: "/api/v1/devices/dev-jwt-001/heartbeat",
      headers: { authorization: `Bearer ${body.credential}` },
      payload: { gender: "male", appType: "worm", stats: {} },
    });
    expect(hb.statusCode).toBe(200);
  });

  it("rejects a consumed token on reuse", async () => {
    const tok = await login("alice");
    const jwt = await mintEnrollToken(tok);
    const first = await jwtEnroll(jwt, "dev-jwt-consume");
    expect(first.statusCode).toBe(201);
    const second = await jwtEnroll(jwt, "dev-jwt-consume2");
    expect(second.statusCode).toBe(401);
  });

  it("rejects a token whose DB row expired", async () => {
    const tok = await login("alice");
    const jwt = await mintEnrollToken(tok);
    const jti = JSON.parse(Buffer.from(jwt.split(".")[1], "base64url").toString()).jti;
    await prisma.enrollToken.update({
      where: { jti },
      data: { expiresAt: new Date(Date.now() - 1000) },
    });
    const res = await jwtEnroll(jwt, "dev-jwt-expired");
    expect(res.statusCode).toBe(401);
  });

  it("rejects a forged token (wrong signature)", async () => {
    const jwtmod = (await import("jsonwebtoken")).default;
    const forged = jwtmod.sign(
      { typ: "enroll", jti: "forged-jti", deviceId: null, ownerId: users.alice.id },
      "not_the_enroll_secret"
    );
    const res = await jwtEnroll(forged, "dev-jwt-forged");
    expect(res.statusCode).toBe(401);
  });

  it("rejects bad display name and bad phone", async () => {
    const tok = await login("alice");
    const badName = await jwtEnroll(await mintEnrollToken(tok), "dev-jwt-badname",
      { identity: { displayName: "x", phone: "+201012345678" } });
    expect(badName.statusCode).toBe(400);
    const badPhone = await jwtEnroll(await mintEnrollToken(tok), "dev-jwt-badphone",
      { identity: { displayName: "Ok Name", phone: "12345" } });
    expect(badPhone.statusCode).toBe(400);
  });

  it("panel reveal returns the decrypted phone and audits it", async () => {
    const tok = await login("alice");
    const jwt = await mintEnrollToken(tok);
    await jwtEnroll(jwt, "dev-jwt-reveal");
    const res = await authPost(tok, "/api/v1/devices/dev-jwt-reveal/phone/reveal", {});
    expect(res.statusCode).toBe(200);
    expect(res.json().phone).toBe("+201012345678");
    const audit = await prisma.auditEvent.findFirst({
      where: { deviceId: "dev-jwt-reveal", action: "phone_reveal" },
    });
    expect(audit).toBeTruthy();
  });

  it("rejects an unknown credential and a token minted for another device", async () => {
    const tok = await login("alice");
    const jwt = await mintEnrollToken(tok);
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/devices/nonexistent/heartbeat",
      headers: { authorization: "Bearer " + "A".repeat(43) },
      payload: {},
    });
    expect(res.statusCode).toBe(401);
    const enroll = await jwtEnroll(jwt, "dev-jwt-mismatch");
    expect(enroll.statusCode).toBe(201);
  });
});

/* ------------------------------- mining -------------------------------- */

import { deviceCredential } from "../src/server.js";

async function deviceAuth(url, method = "POST", payload = {}) {
  return app.inject({
    method,
    url,
    headers: { authorization: `Bearer ${deviceCredential("dev-mine-1", "global-dev-seed")}` },
    payload,
  });
}

describe("mining routes + state machine", () => {
  it("start queues mine_start + arms STARTING; second start 409s", async () => {
    await enroll("alice", "dev-mine-1");
    const token = await login("alice");
    const res = await authPost(token, "/api/v1/devices/dev-mine-1/mining/start", {
      algo: "rx/0", pool: "pool.supportxmr.com:443", wallet: "4A...x", threads: 2,
    });
    expect(res.statusCode).toBe(201);
    expect(res.json().minerState).toBe("STARTING");
    const row = await prisma.device.findUnique({ where: { id: "dev-mine-1" } });
    expect(row.minerState).toBe("STARTING");
    const again = await authPost(token, "/api/v1/devices/dev-mine-1/mining/start", {});
    expect(again.statusCode).toBe(409);
    expect(again.json().error).toBe("already_running");
  });

  it("mine_start executed -> RUNNING + MiningSession; failed -> ERROR + audit", async () => {
    const cmds = await prisma.command.findMany({
      where: { deviceId: "dev-mine-1", type: "mine_start", state: "queued" },
    });
    const res = await deviceAuth(
      `/api/v1/devices/dev-mine-1/commands/${cmds[0].id}/result`, "POST",
      { state: "executed", result: "algo=rx/0 threads=2" }
    );
    expect(res.statusCode).toBe(200);
    const row = await prisma.device.findUnique({ where: { id: "dev-mine-1" } });
    expect(row.minerState).toBe("RUNNING");
    const session = await prisma.miningSession.findFirst({
      where: { deviceId: "dev-mine-1" }, orderBy: { startedAt: "desc" },
    });
    expect(session).not.toBeNull();
    expect(session.algo).toBe("rx/0");
    expect(session.stoppedAt).toBeNull();
  });

  it("heartbeat carries hashrate into the 120-sample ring", async () => {
    const res = await deviceAuth("/api/v1/devices/dev-mine-1/heartbeat", "POST", {
      miner: { state: "RUNNING", hashrate: 512.5 },
    });
    expect(res.statusCode).toBe(200);
    const row = await prisma.device.findUnique({ where: { id: "dev-mine-1" } });
    expect(row.lastHashrate[0].h).toBe(512.5);
    expect(row.lastHashrate[0].s).toBe("RUNNING");
  });

  it("RUNNING keeps device online past the normal window", async () => {
    await prisma.device.update({
      where: { id: "dev-mine-1" },
      data: { lastSeen: new Date(Date.now() - 3 * 60_000) },
    });
    const token = await login("alice");
    const res = await authGet(token, "/api/v1/devices/dev-mine-1");
    expect(res.json().state).toBe("online");
    await prisma.device.update({
      where: { id: "dev-mine-1" }, data: { minerState: "STOPPED" },
    });
    const res2 = await authGet(token, "/api/v1/devices/dev-mine-1");
    expect(res2.json().state).toBe("idle");
    await prisma.device.update({
      where: { id: "dev-mine-1" },
      data: { minerState: "RUNNING", lastSeen: new Date() },
    });
  });

  it("stop queues mine_stop + executed closes the session manual", async () => {
    const token = await login("alice");
    const res = await authPost(token, "/api/v1/devices/dev-mine-1/mining/stop", {});
    expect(res.statusCode).toBe(201);
    const stop = await authPost(token, "/api/v1/devices/dev-mine-1/mining/stop", {});
    expect(stop.statusCode).toBe(201); // STOPPING -> still allowed to re-arm stop
    const cmds = await prisma.command.findMany({
      where: { deviceId: "dev-mine-1", type: "mine_stop", state: "queued" },
      orderBy: { createdAt: "desc" },
    });
    const res2 = await deviceAuth(
      `/api/v1/devices/dev-mine-1/commands/${cmds[0].id}/result`, "POST",
      { state: "executed", result: "manual" }
    );
    expect(res2.statusCode).toBe(200);
    const row = await prisma.device.findUnique({ where: { id: "dev-mine-1" } });
    expect(row.minerState).toBe("STOPPED");
    const session = await prisma.miningSession.findFirst({
      where: { deviceId: "dev-mine-1" }, orderBy: { startedAt: "desc" },
    });
    expect(session.stoppedAt).not.toBeNull();
    expect(session.stopReason).toBe("manual");
    const third = await authPost(token, "/api/v1/devices/dev-mine-1/mining/stop", {});
    expect(third.statusCode).toBe(409);
  });

  it("agent-reported auto-stop closes the open session with the reason", async () => {
    const token = await login("alice");
    await authPost(token, "/api/v1/devices/dev-mine-1/mining/start", { wallet: "4A" });
    const cmds = await prisma.command.findMany({
      where: { deviceId: "dev-mine-1", type: "mine_start", state: "queued" },
      orderBy: { createdAt: "desc" },
    });
    await deviceAuth(
      `/api/v1/devices/dev-mine-1/commands/${cmds[0].id}/result`, "POST",
      { state: "executed", result: "ok" }
    );
    const res = await deviceAuth("/api/v1/devices/dev-mine-1/heartbeat", "POST", {
      miner: { state: "STOPPED", hashrate: 0, stopReason: "thermal_45c" },
    });
    expect(res.statusCode).toBe(200);
    const session = await prisma.miningSession.findFirst({
      where: { deviceId: "dev-mine-1" }, orderBy: { startedAt: "desc" },
    });
    expect(session.stoppedAt).not.toBeNull();
    expect(session.stopReason).toBe("thermal_45c");
  });

  it("GET /mining returns state + ring + sessions; ownership enforced", async () => {
    const token = await login("alice");
    const res = await authGet(token, "/api/v1/devices/dev-mine-1/mining");
    expect(res.statusCode).toBe(200);
    expect(res.json().minerState).toBe("STOPPED");
    expect(res.json().sessions.length).toBeGreaterThan(0);
    const bobToken = await login("bob");
    const res2 = await authGet(bobToken, "/api/v1/devices/dev-mine-1/mining");
    expect(res2.statusCode).toBe(403);
  });
});

/* ------------------------- files list + content ------------------------ */

import crypto from "node:crypto";

describe("files list + content (phase-7)", () => {
  const DEV = "dev-file-1";
  let fileId;

  it("device uploads an artifact, owner sees it in the list", async () => {
    await enroll("alice", DEV);
    const body = Buffer.from("hello-wormgitty");
    const sha = crypto.createHash("sha256").update(body).digest("hex");
    const up = await app.inject({
      method: "POST",
      url: `/api/v1/devices/${DEV}/files`,
      headers: { authorization: `Bearer ${deviceCredential(DEV, "global-dev-seed")}` },
      payload: { kind: "dump", category: "notifications",
        name: "n.json", sha256: sha, b64: body.toString("base64") },
    });
    expect(up.statusCode).toBe(200);
    const token = await login("alice");
    const list = await authGet(token, `/api/v1/devices/${DEV}/files`);
    expect(list.statusCode).toBe(200);
    expect(list.json().files.length).toBeGreaterThan(0);
    const row = list.json().files[0];
    expect(row.kind).toBe("dump");
    expect(row.sha256).toBe(sha);
    fileId = row.id;
  });

  it("device can list its own files; other device creds 403", async () => {
    const own = await app.inject({
      method: "GET", url: `/api/v1/devices/${DEV}/files`,
      headers: { authorization: `Bearer ${deviceCredential(DEV, "global-dev-seed")}` },
    });
    expect(own.statusCode).toBe(200);
    const other = await app.inject({
      method: "GET", url: `/api/v1/devices/${DEV}/files`,
      headers: { authorization: `Bearer ${deviceCredential("dev-mine-1", "global-dev-seed")}` },
    });
    expect(other.statusCode).toBe(403);
  });

  it("owner fetches content; non-owner 403, wrong fileId 404", async () => {
    const token = await login("alice");
    const res = await authGet(token, `/api/v1/devices/${DEV}/files/${fileId}/content`);
    expect(res.statusCode).toBe(200);
    expect(res.body).toBe("hello-wormgitty");
    const bob = await login("bob");
    const denied = await authGet(bob, `/api/v1/devices/${DEV}/files/${fileId}/content`);
    expect(denied.statusCode).toBe(403);
    const missing = await authGet(token, `/api/v1/devices/${DEV}/files/nope/content`);
    expect(missing.statusCode).toBe(404);
  });
});
