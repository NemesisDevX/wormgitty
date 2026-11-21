import { apiFetch, getAccessToken } from "./api.js";

/**
 * Transport seam: swapping the dashboard to the real backend is a one-line
 * change in makeDeviceApi(), nothing else in the app knows which is active.
 *
 * interface DeviceApi {
 *   list(platform?: string): Promise<Device[]>
 *   get(id: string): Promise<Device>
 *   command(id: string, type: "ping" | "sync_config"): Promise<{ok: true}>
 *   reconnect(id: string): Promise<Device>
 *   remove(id: string): Promise<{ok: true, id: string}>
 *   scan(id: string): Promise<ScanSummary>
 *   transfer(id: string, selection: TransferSelection): Promise<{items: QueueItem[]}>
 * }
 */

export class HttpDeviceApi {
  list(platform) {
    return apiFetch(`/devices${platform ? `?platform=${encodeURIComponent(platform)}` : ""}`);
  }

  get(id) {
    return apiFetch(`/devices/${encodeURIComponent(id)}`);
  }

  command(id, type) {
    return apiFetch(`/devices/${encodeURIComponent(id)}/commands`, {
      method: "POST",
      body: JSON.stringify({ command: type }),
    });
  }

  reconnect(id) {
    return apiFetch(`/devices/${encodeURIComponent(id)}/reconnect`, { method: "POST" });
  }

  remove(id) {
    return apiFetch(`/devices/${encodeURIComponent(id)}`, { method: "DELETE" });
  }

  scan(id) {
    return apiFetch(`/devices/${encodeURIComponent(id)}/scan`);
  }

  transfer(id, selection) {
    return apiFetch(`/devices/${encodeURIComponent(id)}/transfer`, {
      method: "POST",
      body: JSON.stringify({ selection }),
    });
  }
}

/** Talks to the same paths; the MSW browser worker intercepts the fetches. */
export class MockDeviceApi extends HttpDeviceApi {}

/**
 * Real backend v0 adapter (contracts/needs-panel P3..P7 mapping). Implements
 * the SAME interface as HttpDeviceApi so resolving between mock and backend
 * stays a one-line decision in makeDeviceApi(). Backend base is configurable
 * (VITE_BACKEND_BASE) and defaults to the local Fastify port.
 *
 * Auth: the backend serves httpOnly-cookie refresh; the panel keeps its Bearer
 * access token via the shared token store (login() wires it).
 */
export class HttpBackendDeviceApi {
  constructor(base = import.meta.env.VITE_BACKEND_BASE || "http://localhost:8088/api/v1") {
    this.base = base.replace(/\/$/, "");
  }

  async #req(path, opts = {}) {
    const { apiFetch, ApiError } = await import("./api.js");
    const res = await fetch(`${this.base}${path}`, {
      ...opts,
      headers: {
        "Content-Type": "application/json",
        ...(getAccessToken() ? { Authorization: `Bearer ${getAccessToken()}` } : {}),
        ...(opts.headers || {}),
      },
    });
    if (res.status === 401) throw new ApiError(401, { error: "unauthorized" });
    if (!res.ok) throw new ApiError(res.status, await res.json().catch(() => ({})));
    return res.status === 204 ? {} : res.json();
  }

  async login(username, password) {
    const { login } = await import("./api.js");
    return login(username, password);
  }

  list(platform) {
    return this.#req(`/devices${platform ? `?platform=${encodeURIComponent(platform)}` : ""}`);
  }

  get(id) {
    return this.#req(`/devices/${encodeURIComponent(id)}`).catch((e) => {
      if (e.status === 404) return null;
      throw e;
    });
  }

  command(id, type) {
    return this.#req(`/devices/${encodeURIComponent(id)}/commands`, {
      method: "POST",
      body: JSON.stringify({ type, payload: {} }),
    });
  }

  async reconnect(id) {
    // Backend v0 has no reconnect endpoint; heartbeat doubles as liveness nudge.
    const hb = await this.#req(`/devices/${encodeURIComponent(id)}/heartbeat`, { method: "POST" });
    return { id, state: "syncing", lastSeen: hb.lastSeen };
  }

  async remove(id) {
    // Backend v0 has no delete endpoint yet; surface an honest error to the caller.
    throw Object.assign(new Error("delete not supported by backend v0"), { status: 501 });
  }

  async scan() {
    throw Object.assign(new Error("scan not supported by backend v0"), { status: 501 });
  }

  async transfer() {
    throw Object.assign(new Error("transfer not supported by backend v0"), { status: 501 });
  }
}

export function makeDeviceApi(env = import.meta.env) {
  if (env.VITE_USE_MOCK === "true") return new MockDeviceApi();
  if (env.VITE_USE_BACKEND === "true") return new HttpBackendDeviceApi();
  return new HttpDeviceApi();
}

let resolved = null;

export function getDeviceApi() {
  if (!resolved) resolved = makeDeviceApi();
  return resolved;
}

export function setDeviceApi(api) {
  resolved = api;
}
