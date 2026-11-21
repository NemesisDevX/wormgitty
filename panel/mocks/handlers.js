import { http, HttpResponse, delay } from "msw";
import { devicesFixture, usersFixture } from "./fixtures/index.js";

let accessTokenSeq = 1;
let loginFailures = 0;
const VALID_TOKENS = new Set();
const VALID_REFRESH = new Set();
export const TOKEN_EXPIRED_PREFIX = "expired-";

/** Scan corpus per device; overwrite entries keyed by device id for tests. */
export const scanOverrides = new Map();

function scanCorpus(deviceId) {
  if (scanOverrides.has(deviceId)) return scanOverrides.get(deviceId);
  const seed = deviceId.split("").reduce((a, c) => a + c.charCodeAt(0), 0);
  const mk = (kind, count, base) =>
    Array.from({ length: count }, (_, i) => ({
      id: `${deviceId}-${kind}-${i}`,
      kind,
      name: `${kind}_${seed + i}.${kind === "video" ? "mp4" : "jpg"}`,
      sizeMb: Math.round(((base + (i % 7)) % 40 + 1) * 10) / 10,
    }));
  return [
    ...mk("photo", 6 + (seed % 5), 2),
    ...mk("video", 2 + (seed % 3), 40),
    ...mk("contact", 8 + (seed % 4), 0),
  ];
}

function issueTokens(user) {
  const accessToken = `fake-access-${accessTokenSeq++}`;
  const refreshToken = `fake-refresh-${accessTokenSeq++}`;
  VALID_TOKENS.add(accessToken);
  VALID_REFRESH.add(refreshToken);
  return {
    accessToken,
    refreshToken,
    user: { email: user.email, displayName: user.displayName },
  };
}

function authorized(request) {
  const header = request.headers.get("authorization") ?? "";
  if (!header.startsWith("Bearer ")) return false;
  const token = header.slice("Bearer ".length).trim();
  if (token.startsWith(TOKEN_EXPIRED_PREFIX)) return false;
  return VALID_TOKENS.has(token);
}

/** Controls which fault the next calls produce (test driver, exposed via window.__wg_fault). */
export const faultControl = {
  status: 200,
  attemptsLeft: 0,
  auth401Left: 0,
  refreshFail: false,
  scanZero: false,
};

/** Records every intercepted method+url pair for e2e assertions. */
export const captured = [];

function track(request) {
  captured.push(`${request.method} ${new URL(request.url).pathname}`);
}

function resetMocksForTests() {
  VALID_TOKENS.clear();
  VALID_REFRESH.clear();
  loginFailures = 0;
  scanOverrides.clear();
  faultControl.status = 200;
  faultControl.attemptsLeft = 0;
  faultControl.auth401Left = 0;
  faultControl.refreshFail = false;
  faultControl.scanZero = false;
}
export { resetMocksForTests };

export const handlers = [
  http.post("/api/v1/admin/auth/login", async ({ request }) => {
    track(request);
    await delay(60);
    const { email, password } = await request.json();
    const user = usersFixture.find((u) => u.email === email && u.password === password);
    if (!user) {
      loginFailures += 1;
      if (loginFailures >= 5) {
        return HttpResponse.json(
          { error: "too_many_attempts", message: "محاولات كتير، استنى شوية" },
          { status: 429 }
        );
      }
      return HttpResponse.json(
        { error: "invalid_credentials", message: "بريد أو كلمة مرور خاطئة" },
        { status: 401 }
      );
    }
    loginFailures = 0;
    return HttpResponse.json(issueTokens(user));
  }),

  http.post("/api/v1/admin/auth/refresh", async ({ request }) => {
    track(request);
    await delay(40);
    if (faultControl.refreshFail) {
      return HttpResponse.json({ error: "unauthorized" }, { status: 401 });
    }
    const { refreshToken } = await request.json();
    if (!VALID_REFRESH.has(refreshToken)) {
      return HttpResponse.json({ error: "unauthorized" }, { status: 401 });
    }
    VALID_REFRESH.delete(refreshToken);
    const body = issueTokens({ email: "ops@wormgitty.local", displayName: "مراقب الأجهزة" });
    return HttpResponse.json({
      accessToken: body.accessToken,
      refreshToken: body.refreshToken,
    });
  }),

  http.get("/api/v1/admin/devices", async ({ request }) => {
    track(request);
    await delay(60);
    if (faultControl.auth401Left > 0) {
      faultControl.auth401Left--;
      return HttpResponse.json({ error: "unauthorized" }, { status: 401 });
    }
    if (!authorized(request)) {
      return HttpResponse.json({ error: "unauthorized" }, { status: 401 });
    }
    if (faultControl.attemptsLeft > 0) {
      faultControl.attemptsLeft--;
      return HttpResponse.json({ error: "boom" }, { status: faultControl.status });
    }
    const platform = new URL(request.url).searchParams.get("platform");
    const list = platform
      ? devicesFixture.filter((d) => d.platform === platform)
      : devicesFixture;
    return HttpResponse.json(list);
  }),

  http.get("/api/v1/admin/devices/:id", async ({ params, request }) => {
    await delay(60);
    if (!authorized(request)) {
      return HttpResponse.json({ error: "unauthorized" }, { status: 401 });
    }
    const dev = devicesFixture.find((d) => d.id === params.id);
    if (!dev) return HttpResponse.json({ error: "not_found" }, { status: 404 });
    return HttpResponse.json(dev);
  }),

  http.post("/api/v1/admin/devices/:id/reconnect", async ({ params, request }) => {
    track(request);
    await delay(80);
    if (!authorized(request)) {
      return HttpResponse.json({ error: "unauthorized" }, { status: 401 });
    }
    const dev = devicesFixture.find((d) => d.id === params.id);
    if (!dev) return HttpResponse.json({ error: "not_found" }, { status: 404 });
    return HttpResponse.json({ ...dev, state: "syncing" });
  }),

  http.delete("/api/v1/admin/devices/:id", async ({ params, request }) => {
    track(request);
    await delay(60);
    if (!authorized(request)) {
      return HttpResponse.json({ error: "unauthorized" }, { status: 401 });
    }
    const idx = devicesFixture.findIndex((d) => d.id === params.id);
    if (idx === -1) return HttpResponse.json({ error: "not_found" }, { status: 404 });
    return HttpResponse.json({ ok: true, id: params.id });
  }),

  http.post("/api/v1/admin/devices/:id/commands", async ({ params, request }) => {
    track(request);
    await delay(60);
    if (!authorized(request)) {
      return HttpResponse.json({ error: "unauthorized" }, { status: 401 });
    }
    const body = await request.json();
    if (!["ping", "sync_config"].includes(body.command)) {
      return HttpResponse.json({ error: "unknown_command" }, { status: 400 });
    }
    const dev = devicesFixture.find((d) => d.id === params.id);
    if (!dev) return HttpResponse.json({ error: "not_found" }, { status: 404 });
    return HttpResponse.json({
      ok: true,
      id: params.id,
      command: body.command,
      queuedAt: Date.now(),
    });
  }),

  http.get("/api/v1/admin/devices/:id/scan", async ({ params, request }) => {
    track(request);
    await delay(80);
    if (!authorized(request)) {
      return HttpResponse.json({ error: "unauthorized" }, { status: 401 });
    }
    const dev = devicesFixture.find((d) => d.id === params.id);
    if (!dev) return HttpResponse.json({ error: "not_found" }, { status: 404 });
    if (faultControl.scanZero) {
      return HttpResponse.json({
        deviceId: params.id,
        photos: 0,
        videos: 0,
        contacts: 0,
        files: [],
      });
    }
    const files = scanCorpus(params.id);
    return HttpResponse.json({
      deviceId: params.id,
      photos: files.filter((f) => f.kind === "photo").length,
      videos: files.filter((f) => f.kind === "video").length,
      contacts: files.filter((f) => f.kind === "contact").length,
      files,
    });
  }),

  http.post("/api/v1/admin/devices/:id/transfer", async ({ params, request }) => {
    track(request);
    await delay(80);
    if (!authorized(request)) {
      return HttpResponse.json({ error: "unauthorized" }, { status: 401 });
    }
    const { selection } = await request.json();
    const mode = selection?.mode ?? "all";
    const n = Number(selection?.n ?? 0);
    if (!["all", "newest", "largest"].includes(mode)) {
      return HttpResponse.json({ error: "bad_selection" }, { status: 400 });
    }
    if (mode !== "all" && (!Number.isInteger(n) || n < 1 || n > 5000)) {
      return HttpResponse.json({ error: "bad_limit" }, { status: 400 });
    }
    if (faultControl.scanZero) {
      return HttpResponse.json({ items: [] });
    }
    let pool = scanCorpus(params.id).filter((f) => f.kind !== "contact");
    if (mode === "newest") pool = pool.slice(-n);
    if (mode === "largest") pool = [...pool].sort((a, b) => b.sizeMb - a.sizeMb).slice(0, n);
    return HttpResponse.json({
      items: pool.map((f, i) => ({
        id: `${f.id}-q`,
        kind: f.kind,
        name: f.name,
        sizeMb: f.sizeMb,
        state: "queued",
        pos: i,
      })),
    });
  }),
];

export const defaultHandlers = handlers;
