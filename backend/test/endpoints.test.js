import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { buildApp, prisma, hashPassword } from "../src/server.js";

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
  return res.json().accessToken;
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
    await authPost(token, "/api/v1/devices/dev-cmd-2/commands", { type: "sync_config" });
    const res = await authGet(token, "/api/v1/devices/dev-cmd-2/commands");
    expect(res.statusCode).toBe(200);
    const list = res.json();
    expect(list.length).toBeGreaterThanOrEqual(2);
    expect(list.every((c) => ["queued", "sent", "acked", "done", "failed"].includes(c.state))).toBe(true);
    expect(new Date(list[0].createdAt) >= new Date(list[list.length - 1].createdAt)).toBe(true);
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
});
