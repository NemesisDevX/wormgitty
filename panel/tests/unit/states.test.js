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

function fakeApi(result) {
  return {
    list: vi.fn(async () => result.list ?? []),
    get: vi.fn(async () => result.get ?? null),
    command: vi.fn(async () => ({ ok: true })),
    reconnect: vi.fn(async () => result.reconnect ?? {}),
    remove: vi.fn(async (id) => ({ ok: true, id })),
    scan: vi.fn(async () => result.scan ?? { photos: 0, videos: 0, contacts: 0, files: [] }),
    transfer: vi.fn(async (id, selection) => result.transfer?.(selection) ?? { items: [] }),
  };
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

  it("401 without token expires the session back to login", () => {
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

  it("empty device list renders the empty state", async () => {
    const p = panel();
    p.route = { name: "devices", platform: "android" };
    p.setDeviceApiOverride(fakeApi({ list: [] }));
    await p.loadDevices();
    expect(p.view).toBe("empty");
    expect(p.devices).toHaveLength(0);
  });
});

describe("delete confirmation", () => {
  it("removes only after explicit confirmation", async () => {
    const p = panel();
    const api = fakeApi({});
    p.setDeviceApiOverride(api);
    p.route = { name: "device-detail", id: "dev-1" };
    p.show = vi.fn();
    p.requestRemove();
    expect(p.confirmDelete).toBe(true);
    expect(api.remove).not.toHaveBeenCalled();
    await p.removeDevice("dev-1");
    expect(api.remove).toHaveBeenCalledWith("dev-1");
    expect(p.confirmDelete).toBe(false);
    expect(p.show).toHaveBeenCalledWith("#/devices");
  });

  it("cancel clears the confirmation without touching the api", () => {
    const p = panel();
    const api = fakeApi({});
    p.setDeviceApiOverride(api);
    p.requestRemove();
    p.cancelRemove();
    expect(p.confirmDelete).toBe(false);
  });
});

describe("photos selection and queue", () => {
  const files = [
    { id: "f1", kind: "photo", name: "a.jpg", sizeMb: 2 },
    { id: "f2", kind: "photo", name: "b.jpg", sizeMb: 9 },
    { id: "f3", kind: "photo", name: "c.jpg", sizeMb: 5 },
    { id: "f4", kind: "video", name: "d.mp4", sizeMb: 30 },
    { id: "f5", kind: "video", name: "e.mp4", sizeMb: 18 },
  ];

  function storeWithSelection(items) {
    const p = panel();
    const api = fakeApi({
      scan: { photos: 3, videos: 2, contacts: 0, files },
      transfer: (selection) => ({
        items: items.map((f, i) => ({
          id: `${f.id}-q`,
          kind: f.kind,
          name: f.name,
          sizeMb: f.sizeMb,
          state: "queued",
          pos: i,
        })),
      }),
    });
    api.__received = [];
    const wrapped = {
      ...api,
      transfer: async (id, selection) => {
        api.__received.push(selection);
        return api.transfer(id, selection);
      },
    };
    p.setDeviceApiOverride(wrapped);
    p.photos.summary = { photos: 3, videos: 2, contacts: 0, files };
    return { p, api };
  }

  it("selection mode all queues every media row", async () => {
    const { p } = storeWithSelection(files);
    p.photos.mode = "all";
    await p.enqueueTransfer("dev-1");
    expect(p.photos.queue).toHaveLength(5);
  });

  it("selection newest N changes the queued count", async () => {
    const { p, api } = storeWithSelection(files.slice(-2));
    p.photos.mode = "newest";
    p.photos.limit = 2;
    await p.enqueueTransfer("dev-1");
    expect(api.__received[0]).toEqual({ mode: "newest", n: 2 });
    expect(p.photos.queue).toHaveLength(2);
    expect(p.photos.queue.every((q) => ["f4", "f5"].some((id) => q.id.startsWith(id)))).toBe(true);
  });

  it("selection largest N clamps to 1..5000 before requesting", async () => {
    const { p, api } = storeWithSelection(files);
    p.photos.mode = "largest";
    p.photos.limit = 9000;
    await p.enqueueTransfer("dev-1");
    expect(api.__received[0]).toEqual({ mode: "largest", n: 5000 });
  });

  it("invalid limit blocks the enqueue with a message", async () => {
    const { p, api } = storeWithSelection(files);
    p.photos.mode = "newest";
    p.photos.limit = 0;
    await p.enqueueTransfer("dev-1");
    expect(p.photos.error).toContain("٥٠٠٠");
    expect(api.__received).toHaveLength(0);
  });

  it("ticker advances queued->uploading->done and accumulates MB", async () => {
    const { p } = storeWithSelection([{ ...files[0] }]);
    await p.enqueueTransfer("dev-1");
    expect(p.photos.queue[0].state).toBe("queued");
    vi.advanceTimersByTime(400);
    expect(p.photos.queue[0].state).toBe("uploading");
    vi.advanceTimersByTime(400);
    expect(p.photos.queue[0].state).toBe("done");
    expect(p.photos.sentMb).toBe(2);
    vi.advanceTimersByTime(1200);
    expect(p.photoTick).toBeNull();
  });

  it("paused ticker freezes advancement", async () => {
    const { p } = storeWithSelection([{ ...files[0] }, { ...files[1] }]);
    await p.enqueueTransfer("dev-1");
    p.togglePause();
    vi.advanceTimersByTime(4000);
    expect(p.photos.queue.every((q) => q.state === "queued")).toBe(true);
    p.togglePause();
    vi.advanceTimersByTime(400);
    expect(p.photos.queue[0].state).toBe("uploading");
  });

  it("retry-failed requeues only failed rows", async () => {
    const { p } = storeWithSelection(files.slice(0, 3));
    await p.enqueueTransfer("dev-1");
    const queue = p.photos.queue;
    queue[0].state = "failed";
    queue[1].state = "done";
    queue[2].state = "uploading";
    const requeued = p.retryFailed();
    expect(requeued).toBe(1);
    expect(queue[0].state).toBe("queued");
    expect(queue[1].state).toBe("done");
    expect(queue[2].state).toBe("uploading");
  });

  it("scan with zero files flips the empty state", async () => {
    const p = panel();
    p.setDeviceApiOverride(fakeApi({ scan: { photos: 0, videos: 0, contacts: 0, files: [] } }));
    await p.scanDevice("dev-2");
    expect(p.photos.empty).toBe(true);
    expect(p.photos.summary.photos).toBe(0);
  });
});
