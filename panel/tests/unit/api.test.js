import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import {
  ApiError,
  apiFetch,
  getAccessToken,
  login,
  refreshTokens,
  setAccessToken,
} from "../../src/lib/api.js";

const BASE = "/api/v1/admin";

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  localStorage.clear();
  setAccessToken(null);
});

describe("apiFetch transport", () => {
  it("attaches the in-memory access token as Bearer", async () => {
    setAccessToken("tok-abc");
    const bearer = [];
    server.use(
      http.get(`${BASE}/devices`, ({ request }) => {
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
      http.post(`${BASE}/auth/refresh`, () => {
        refreshCalls++;
        return HttpResponse.json({ accessToken: "fresh-tok", refreshToken: "rt-2" });
      }),
      http.get(`${BASE}/devices`, ({ request }) => {
        authHeader.push(request.headers.get("authorization"));
        if (authHeader.length === 1) return HttpResponse.json({ ok: 0 }, { status: 401 });
        return HttpResponse.json({ ok: true });
      })
    );
    const out = await apiFetch("/devices");
    expect(out).toEqual({ ok: true });
    expect(refreshCalls).toBe(1);
    expect(authHeader[0]).toBe("Bearer expired-tok");
    expect(authHeader[1]).toBe("Bearer fresh-tok");
    expect(getAccessToken()).toBe("fresh-tok");
  });

  it("throws ApiError(401) when refresh also fails", async () => {
    setAccessToken("expired");
    server.use(
      http.post(`${BASE}/auth/refresh`, () => HttpResponse.json({}, { status: 401 })),
      http.get(`${BASE}/devices`, () => HttpResponse.json({}, { status: 401 }))
    );
    await expect(apiFetch("/devices")).rejects.toMatchObject({ status: 401 });
  });

  it("surfaces network failure as ApiError(0)", async () => {
    server.use(http.get(`${BASE}/devices`, () => HttpResponse.error()));
    let caught;
    try {
      await apiFetch("/devices");
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeInstanceOf(ApiError);
    expect(caught.status).toBe(0);
  });

  it("login stores tokens on success", async () => {
    server.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json({ accessToken: "acc-1", refreshToken: "ref-1", user: { email: "e" } })
      )
    );
    const body = await login("ops@wormgitty.local", "w0rm-gitty-pass");
    expect(body.accessToken).toBe("acc-1");
    expect(getAccessToken()).toBe("acc-1");
    expect(localStorage.getItem("refresh_token")).toBe("ref-1");
  });

  it("refreshTokens swaps the stored refresh token", async () => {
    localStorage.setItem("refresh_token", "old-ref");
    server.use(
      http.post(`${BASE}/auth/refresh`, () =>
        HttpResponse.json({ accessToken: "acc-2", refreshToken: "new-ref" })
      )
    );
    expect(await refreshTokens()).toBe(true);
    expect(getAccessToken()).toBe("acc-2");
    expect(localStorage.getItem("refresh_token")).toBe("new-ref");
  });
});
