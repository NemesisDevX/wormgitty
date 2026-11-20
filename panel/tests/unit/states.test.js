import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { makePanelStore } from "../../src/store.js";
import { ApiError } from "../../src/lib/api.js";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

function panel() {
  return makePanelStore();
}

describe("view-state mapping", () => {
  it("429 enters cooldown state with countdown", () => {
    const p = panel();
    p.route = { name: "devices", platform: "" };
    p.handleLoadError(new ApiError(429, {}));
    expect(p.view).toBe("cooldown");
    expect(p.cooldownLeft).toBeGreaterThan(0);
    vi.advanceTimersByTime(2000);
    expect(p.cooldownLeft).toBeLessThan(30);
  });

  it("cooldown recovers and reloads after the timer drains", () => {
    const p = panel();
    p.route = { name: "devices", platform: "" };
    const spy = vi.spyOn(p, "reload").mockResolvedValue(undefined);
    p.enterCooldown();
    vi.advanceTimersByTime(31000);
    expect(spy).toHaveBeenCalled();
    spy.mockRestore();
  });

  it("500 maps to server-error with retry affordance data", () => {
    const p = panel();
    p.route = { name: "devices", platform: "" };
    p.handleLoadError(new ApiError(500, { message: "boom" }));
    expect(p.view).toBe("server-error");
    expect(p.message).toContain("جرّب");
  });

  it("401 expires the session back to login", () => {
    const p = panel();
    localStorage.setItem("refresh_token", "rt");
    p.route = { name: "devices", platform: "" };
    p.expireSession();
    expect(localStorage.getItem("refresh_token")).toBeNull();
  });

  it("network failure maps to offline banner", () => {
    const p = panel();
    p.route = { name: "devices", platform: "" };
    p.networkDown();
    expect(p.view).toBe("offline");
    expect(p.message.length).toBeGreaterThan(0);
  });

  it("successful load with no rows flips view to empty", async () => {
    const p = panel();
    p.route = { name: "devices", platform: "android" };
    p.startPolling = () => {};
    const origFetch = global.fetch;
    global.fetch = async () =>
      new Response(JSON.stringify([]), { status: 200, headers: { "Content-Type": "application/json" } });
    try {
      await p.loadDevices();
    } finally {
      global.fetch = origFetch;
    }
    expect(p.view).toBe("empty");
  });
});
