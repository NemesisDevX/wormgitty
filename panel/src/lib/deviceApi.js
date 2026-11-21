import { apiFetch } from "./api.js";

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

export function makeDeviceApi(env = import.meta.env) {
  if (env.VITE_USE_MOCK === "true") return new MockDeviceApi();
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
