import { http, HttpResponse, delay } from "msw";
import { devicesFixture, usersFixture } from "./fixtures/index.js";

let accessTokenSeq = 1;

function issueTokens(user) {
  return {
    accessToken: `fake-access-${accessTokenSeq++}`,
    refreshToken: "fake-refresh-round-2",
    user: { email: user.email, displayName: user.displayName },
  };
}

/** Controls which fault the next calls produce (test driver, exposed via window.__wg_fault). */
export const faultControl = {
  status: 200,
  attemptsLeft: 0,
  auth401Left: 0,
  refreshFail: false,
};

/** Records every intercepted method+url pair for e2e assertions. */
export const captured = [];

function track(request) {
  captured.push(`${request.method} ${new URL(request.url).pathname}`);
}

export const handlers = [
  http.post("/api/auth/login", async ({ request }) => {
    track(request);
    await delay(120);
    const body = await request.json();
    const email = body.email ?? body.username;
    const user = usersFixture.find((u) => u.email === email);
    if (!user || user.password !== body.password) {
      return HttpResponse.json({ message: "بريد أو كلمة مرور خاطئة" }, { status: 401 });
    }
    return HttpResponse.json(issueTokens(user));
  }),

  http.post("/api/auth/2fa", async ({ request }) => {
    track(request);
    await delay(80);
    const { code } = await request.json();
    if (code !== "105746") {
      return HttpResponse.json({ message: "invalid_code" }, { status: 401 });
    }
    return HttpResponse.json(issueTokens(usersFixture[0]));
  }),

  http.post("/api/auth/refresh", async ({ request }) => {
    track(request);
    await delay(80);
    if (faultControl.refreshFail) {
      return HttpResponse.json({ message: "expired" }, { status: 401 });
    }
    return HttpResponse.json({
      accessToken: `fake-access-${accessTokenSeq++}`,
      refreshToken: "fake-refresh-round-3",
    });
  }),

  http.get("/api/devices", async ({ request }) => {
    track(request);
    await delay(100);
    if (faultControl.auth401Left > 0) {
      faultControl.auth401Left--;
      return HttpResponse.json({ message: "token expired" }, { status: 401 });
    }
    if (faultControl.attemptsLeft > 0) {
      faultControl.attemptsLeft--;
      return HttpResponse.json({ message: "fault" }, { status: faultControl.status });
    }
    const platform = new URL(request.url).searchParams.get("platform");
    const list = platform ? devicesFixture.filter((d) => d.platform === platform) : devicesFixture;
    return HttpResponse.json(list);
  }),

  http.get("/api/devices/:id", async ({ params }) => {
    await delay(100);
    const dev = devicesFixture.find((d) => d.id === params.id);
    if (!dev) return HttpResponse.json({ message: "not found" }, { status: 404 });
    return HttpResponse.json(dev);
  }),

  http.post("/api/devices/:id/reconnect", async ({ params, request }) => {
    track(request);
    await delay(150);
    const dev = devicesFixture.find((d) => d.id === params.id);
    if (!dev) return HttpResponse.json({ message: "not found" }, { status: 404 });
    return HttpResponse.json({ ...dev, state: "syncing" });
  }),

  http.delete("/api/devices/:id", async ({ params, request }) => {
    track(request);
    await delay(120);
    const idx = devicesFixture.findIndex((d) => d.id === params.id);
    if (idx === -1) return HttpResponse.json({ message: "not found" }, { status: 404 });
    return HttpResponse.json({ ok: true, id: params.id });
  }),

  http.post("/api/devices/:id/commands", async ({ params, request }) => {
    track(request);
    await delay(120);
    const body = await request.json();
    if (!["ping", "wipe", "camera", "pull_photos", "screen_capture"].includes(body.command)) {
      return HttpResponse.json({ message: "unknown command" }, { status: 400 });
    }
    const dev = devicesFixture.find((d) => d.id === params.id);
    if (!dev) return HttpResponse.json({ message: "not found" }, { status: 404 });
    return HttpResponse.json({ ok: true, id: params.id, command: body.command, queuedAt: Date.now() });
  }),

  http.get("/api/devices/:id/photos", async ({ params, request }) => {
    track(request);
    await delay(80);
    if (!devicesFixture.find((d) => d.id === params.id)) {
      return HttpResponse.json({ message: "not found" }, { status: 404 });
    }
    return HttpResponse.json({
      counts: { original: 4, hidden: 1, deleted: 2 },
      items: [
        { hash: "h1", name: "img_1.jpg", category: "original", size: 1200 },
        { hash: "h2", name: "img_2.jpg", category: "original", size: 900 },
        { hash: "h3", name: "hid_1.jpg", category: "hidden", size: 500 },
        { hash: "h4", name: "del_1.jpg", category: "deleted", size: 700 },
      ],
    });
  }),

  http.get("/api/devices/:id/files", async ({ params, request }) => {
    track(request);
    await delay(60);
    return HttpResponse.json({ count: 0, dir: `D:/Dashboard Panel/${params.id}` });
  }),

  http.get("/api/devices/:id/mining", async ({ params, request }) => {
    track(request);
    await delay(60);
    if (!devicesFixture.find((d) => d.id === params.id)) {
      return HttpResponse.json({ message: "not found" }, { status: 404 });
    }
    return HttpResponse.json({
      minerState: miningState[params.id] ?? "STOPPED",
      hashrate: [],
      sessions: [],
    });
  }),

  http.post("/api/devices/:id/mining/start", async ({ params, request }) => {
    track(request);
    await delay(80);
    if (!devicesFixture.find((d) => d.id === params.id)) {
      return HttpResponse.json({ message: "not found" }, { status: 404 });
    }
    if (["STARTING", "RUNNING", "STOPPING"].includes(miningState[params.id])) {
      return HttpResponse.json({ error: "already_running" }, { status: 409 });
    }
    miningState[params.id] = "STARTING";
    return HttpResponse.json({ commandId: "cmd-mock-start", minerState: "STARTING" }, { status: 201 });
  }),

  http.post("/api/devices/:id/mining/stop", async ({ params, request }) => {
    track(request);
    await delay(80);
    if (!devicesFixture.find((d) => d.id === params.id)) {
      return HttpResponse.json({ message: "not found" }, { status: 404 });
    }
    if ((miningState[params.id] ?? "STOPPED") === "STOPPED") {
      return HttpResponse.json({ error: "not_running" }, { status: 409 });
    }
    miningState[params.id] = "STOPPED";
    return HttpResponse.json({ commandId: "cmd-mock-stop", minerState: "STOPPED" }, { status: 201 });
  }),
];

const miningState = {};

export const defaultHandlers = handlers;
