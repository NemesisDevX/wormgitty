import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { ApiError, apiFetch, getAccessToken, refreshTokens, setAccessToken } from "../../src/lib/api.js";

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  localStorage.clear();
  setAccessToken(null);
});

function authedHandler(capture, body = { ok: true }) {
  return http.get("/api/devices", ({ request }) => {
    capture.push(request.headers.get("authorization"));
    if (!capture.bearerSeen) {
      capture.bearerSeen = true;
      return HttpResponse.json(body, { status: 401 });
    }
    return HttpResponse.json(body);
  });
}

describe("apiFetch", () => {
  it("attaches the in-memory access token as Bearer", async () => {
    setAccessToken("tok-abc");
    const bearer = [];
    server.use(
      http.get("/api/devices", ({ request }) => {
        bearer.push(request.headers.get("authorization"));
        return HttpResponse.json([]);
      })
    );
    await apiFetch("/devices");
    expect(bearer[0]).toBe("Bearer tok-abc");
  });

  it("retries exactly once after silent refresh on 401", async () => {
    localStorage.setItem("refresh_token", "rt-1");
    setAccessToken("expired-tok");
    let refreshCalls = 0;
    const authHeader = [];
    server.use(
      http.post("/api/auth/refresh", () => {
        refreshCalls++;
        return HttpResponse.json({ accessToken: "fresh-tok", refreshToken: "rt-2" });
      }),
      authedHandler(authHeader)
    );
    const out = await apiFetch("/devices");
    expect(out).toEqual({ ok: true });
    expect(refreshCalls).toBe(1);
    expect(authHeader[0]).toBe("Bearer expired-tok");
    expect(authHeader[1]).toBe("Bearer fresh-tok");
    expect(localStorage.getItem("refresh_token")).toBe("rt-2");
    expect(getAccessToken()).toBe("fresh-tok");
  });

  it("throws ApiError(401) when refresh also fails", async () => {
    setAccessToken("expired");
    server.use(
      http.post("/api/auth/refresh", () => HttpResponse.json({}, { status: 401 })),
      http.get("/api/devices", () => HttpResponse.json({}, { status: 401 }))
    );
    await expect(apiFetch("/devices")).rejects.toMatchObject({ status: 401 });
  });

  it("surfaces network failure as ApiError(0)", async () => {
    server.use(http.get("/api/devices", () => HttpResponse.error()));
    let caught;
    try {
      await apiFetch("/devices");
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeInstanceOf(ApiError);
    expect(caught.status).toBe(0);
  });
});
