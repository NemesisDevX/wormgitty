import Alpine from "alpinejs";
import { apiFetch, ApiError, login, setAccessToken } from "./lib/api.js";
import { getDeviceApi, setDeviceApi } from "./lib/deviceApi.js";
import { formatE164 } from "./lib/e164.js";
import { stateBadge } from "./lib/badge.js";

export const POLL_MS = 5000;
export const COOLDOWN_SECONDS = 30;
export const SELECTION_LIMIT_MAX = 5000;
export const TICK_MS = 400;

export function parseRoute(hash) {
  const clean = (hash || "#/login").replace(/^#\/?/, "") || "login";
  const [pathPart, queryPart] = clean.split("?");
  const segs = pathPart.split("/").filter(Boolean);
  if (segs[0] === "devices" && segs[1]) {
    return { name: "device-detail", id: segs[1] };
  }
  if (segs[0] === "devices") {
    const platform = new URLSearchParams(queryPart || "").get("platform");
    return { name: "devices", platform: platform || "" };
  }
  return { name: "login" };
}

function blankPhotos() {
  return {
    loading: false,
    summary: null,
    empty: false,
    mode: "all",
    limit: 100,
    limitDirty: false,
    queue: [],
    sentMb: 0,
    paused: false,
    ticking: false,
    error: "",
  };
}

export function makePanelStore() {
  return {
    route: { name: "login", platform: "" },
    view: "loading",
    message: "",
    user: null,
    loginError: "",

    devices: [],
    detail: null,
    commandChoice: "ping",

    confirmDelete: false,
    photos: blankPhotos(),

    pollTimer: null,
    photoTick: null,
    cooldownTimer: null,
    cooldownLeft: 0,

    deviceApi: null,

    api() {
      if (!this.deviceApi) this.deviceApi = getDeviceApi();
      return this.deviceApi;
    },
    setDeviceApiOverride(api) {
      this.deviceApi = api;
    },

    formatE164,
    badgeOf(state) {
      return stateBadge(state);
    },

    navigate(route) {
      this.stopTimers();
      this.route = route;
      this.view = "loading";
      this.message = "";
      this.confirmDelete = false;
      this.photos = blankPhotos();
      if (route.name === "login") this.loginError = "";
      if (route.name === "devices") this.loadDevices();
      if (route.name === "device-detail") this.loadDetail(route.id);
    },

    stopTimers() {
      if (this.pollTimer) clearInterval(this.pollTimer);
      this.pollTimer = null;
      if (this.photoTick) clearInterval(this.photoTick);
      this.photoTick = null;
      if (this.cooldownTimer) clearInterval(this.cooldownTimer);
      this.cooldownTimer = null;
    },

    async startLogin(email, password) {
      this.loginError = "";
      try {
        const body = await login(email, password);
        this.user = body.user;
        this.show("#/devices");
      } catch (err) {
        if (err instanceof ApiError && err.status === 0) {
          this.loginError = "تعذر الاتصال، حاول تاني";
        } else if (err instanceof ApiError && err.status === 429) {
          this.loginError = err.payload?.message ?? "محاولات كتير، استنى شوية";
        } else {
          this.loginError = "بريد أو كلمة مرور خاطئة";
        }
      }
    },

    show(hash) {
      window.location.hash = hash;
    },

    logout() {
      setAccessToken(null);
      localStorage.removeItem("refresh_token");
      this.user = null;
      this.show("#/login");
    },

    async loadDevices() {
      this.view = "loading";
      try {
        const list = await this.api().list(this.route.platform || undefined);
        this.devices = Array.isArray(list) ? list : [];
        this.view = this.devices.length ? "ready" : "empty";
        this.startPolling();
      } catch (err) {
        this.handleLoadError(err);
      }
    },

    startPolling() {
      this.stopPollOnly();
      this.pollTimer = setInterval(async () => {
        if (document.hidden || this.route.name !== "devices") return;
        try {
          const list = await this.api().list(this.route.platform || undefined);
          this.devices = list;
          this.view = this.devices.length ? "ready" : "empty";
        } catch (err) {
          if (err.status === 401) this.expireSession();
          else if (err.status === 0) {
            this.view = "offline";
            this.message = "لا يوجد اتصال بالشبكة";
          }
        }
      }, POLL_MS);
    },

    stopPollOnly() {
      if (this.pollTimer) clearInterval(this.pollTimer);
      this.pollTimer = null;
    },

    async loadDetail(id) {
      this.view = "loading";
      try {
        this.detail = await this.api().get(id);
        this.view = this.detail ? "ready" : "empty";
      } catch (err) {
        this.handleLoadError(err);
      }
    },

    handleLoadError(err) {
      if (!(err instanceof ApiError)) return this.networkDown();
      if (err.status === 401) return this.expireSession();
      if (err.status === 429) return this.enterCooldown();
      if (err.status === 500) {
        this.view = "server-error";
        this.message = "السيرفر تعبان، جرّب تاني";
        return;
      }
      this.networkDown();
    },

    networkDown() {
      this.view = "offline";
      this.message = "لا يوجد اتصال بالشبكة";
    },

    expireSession() {
      this.stopTimers();
      setAccessToken(null);
      localStorage.removeItem("refresh_token");
      this.show("#/login");
    },

    enterCooldown() {
      this.view = "cooldown";
      this.message = "طلبات كتير، استنى شوية وهنعيد تلقائي";
      this.cooldownLeft = COOLDOWN_SECONDS;
      this.cooldownTimer = setInterval(() => {
        this.cooldownLeft -= 1;
        if (this.cooldownLeft <= 0) {
          clearInterval(this.cooldownTimer);
          this.cooldownTimer = null;
          this.reload();
        }
      }, 1000);
    },

    reload() {
      if (this.route.name === "device-detail") this.loadDetail(this.route.id);
      else this.loadDevices();
    },

    async reconnect(id) {
      try {
        this.detail = await this.api().reconnect(id);
      } catch (err) {
        this.handleLoadError(err);
      }
    },

    requestRemove() {
      this.confirmDelete = true;
    },

    cancelRemove() {
      this.confirmDelete = false;
    },

    async removeDevice(id) {
      try {
        await this.api().remove(id);
        this.confirmDelete = false;
        this.show("#/devices");
      } catch (err) {
        this.handleLoadError(err);
      }
    },

    async sendCommand(id) {
      try {
        await this.api().command(id, this.commandChoice);
        this.flash = "تم إرسال الأمر";
        setTimeout(() => (this.flash = ""), 2500);
      } catch (err) {
        this.handleLoadError(err);
      }
    },

    flash: "",

    /* ----------------------------- Photos tab ----------------------------- */

    async scanDevice(id) {
      this.photos.loading = true;
      this.photos.error = "";
      try {
        const summary = await this.api().scan(id);
        this.photos.summary = summary;
        this.photos.empty = !summary.files?.length;
      } catch (err) {
        this.photos.error =
          err instanceof ApiError && err.status === 401
            ? "الجلسة انتهت"
            : "فحص الصور فشل، جرّب تاني";
      } finally {
        this.photos.loading = false;
      }
    },

    effectiveLimit() {
      if (this.photos.mode === "all") return null;
      const n = Math.floor(Number(this.photos.limit));
      if (!Number.isFinite(n)) return NaN;
      return Math.min(Math.max(n, 1), SELECTION_LIMIT_MAX);
    },

    async enqueueTransfer(id) {
      const limit = this.effectiveLimit();
      if (this.photos.mode !== "all" && (!Number.isInteger(limit) || limit < 1)) {
        this.photos.error = "حدد رقم من ١ لـ ٥٠٠٠";
        return;
      }
      this.photos.error = "";
      try {
        const selection =
          this.photos.mode === "all" ? { mode: "all" } : { mode: this.photos.mode, n: limit };
        const { items } = await this.api().transfer(id, selection);
        this.photos.queue = items;
        this.photos.sentMb = 0;
        this.photos.paused = false;
        this.startTicker();
      } catch (err) {
        this.photos.error = "تعذر تجهيز النقل، جرّب تاني";
      }
    },

    startTicker() {
      if (this.photoTick) clearInterval(this.photoTick);
      this.photoTick = setInterval(() => this.tickTransfer(), TICK_MS);
    },

    tickTransfer() {
      if (this.photos.paused) return;
      const next = this.photos.queue.find((q) => q.state === "uploading");
      const firstQueued = this.photos.queue.find((q) => q.state === "queued");
      const target = next ?? firstQueued;
      if (!target) {
        clearInterval(this.photoTick);
        this.photoTick = null;
        return;
      }
      if (target.state === "queued") target.state = "uploading";
      else {
        target.state = "done";
        this.photos.sentMb = Math.round((this.photos.sentMb + target.sizeMb) * 10) / 10;
      }
    },

    togglePause() {
      this.photos.paused = !this.photos.paused;
    },

    retryFailed() {
      let requeued = 0;
      this.photos.queue.forEach((q) => {
        if (q.state === "failed") {
          q.state = "queued";
          requeued += 1;
        }
      });
      if (requeued) this.startTicker();
      return requeued;
    },

    markRandomFailure(count = 1) {
      // Simulation affordance used by tests/demo: flips up to [count] uploading rows.
      let flipped = 0;
      for (const q of this.photos.queue) {
        if (q.state === "uploading" && flipped < count) {
          q.state = "failed";
          flipped += 1;
        }
      }
      return flipped;
    },

    queueProgress() {
      const total = this.photos.queue.length;
      const done = this.photos.queue.filter((q) => q.state === "done").length;
      return { total, done };
    },
  };
}

// Re-exported for consumers that configure transports before Alpine mounts.
export { setDeviceApi };
export const __wire = { apiFetch };
Alpine.store("panel", makePanelStore());

export default Alpine;
