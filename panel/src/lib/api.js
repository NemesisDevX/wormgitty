const BASE = "/api";

let accessToken = null;

export function setAccessToken(token) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

export class ApiError extends Error {
  constructor(status, payload, cause) {
    super(`API ${status}: ${payload?.message ?? cause?.message ?? "unknown"}`);
    this.status = status;
    this.payload = payload;
    this.cause = cause;
  }
}

/** Exchanges the stored refresh token for a fresh access token pair. */
export async function refreshTokens(fetchImpl = fetch) {
  const refreshToken = localStorage.getItem("refresh_token");
  if (!refreshToken) return false;
  let res;
  try {
    res = await fetchImpl(`${BASE}/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
  } catch {
    return false;
  }
  if (!res.ok) return false;
  const body = await res.json();
  accessToken = body.accessToken;
  if (body.refreshToken) localStorage.setItem("refresh_token", body.refreshToken);
  return true;
}

/**
 * Core transport. Attaches Bearer token, silently refreshes exactly once on
 * 401, and raises typed ApiError(status) for callers to map to UI states.
 */
export async function apiFetch(path, opts = {}, attempt = 0, fetchImpl = fetch) {
  const headers = { "Content-Type": "application/json", ...(opts.headers || {}) };
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  let res;
  try {
    res = await fetchImpl(BASE + path, { ...opts, headers });
  } catch (err) {
    throw new ApiError(0, null, err);
  }
  if (res.status === 401 && attempt === 0) {
    if (await refreshTokens(fetchImpl)) return apiFetch(path, opts, 1, fetchImpl);
  }
  const payload = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, payload);
  return payload;
}
