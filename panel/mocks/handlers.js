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
    const { email, password } = await request.json();
    const user = usersFixture.find((u) => u.email === email);
    if (!user || user.password !== password) {
      return HttpResponse.json({ message: "بريد أو كلمة مرور خاطئة" }, { status: 401 });
    }
    return HttpResponse.json(issueTokens(user));
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
];

export const defaultHandlers = handlers;
