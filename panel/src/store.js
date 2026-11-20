import Alpine from "alpinejs";
import { apiFetch, ApiError, setAccessToken } from "./lib/api.js";
import { formatE164 } from "./lib/e164.js";
import { stateBadge } from "./lib/badge.js";

export const POLL_MS = 5000;
export const COOLDOWN_SECONDS = 30;

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

    listFilter: "",
    pollTimer: null,
    cooldownTimer: null,
    cooldownLeft: 0,

    formatE164,
    badgeOf(state) {
      return stateBadge(state);
    },

    navigate(route) {
      this.stopTimers();
      this.route = route;
      this.view = "loading";
      this.message = "";
      if (route.name === "login") this.loginError = "";
      if (route.name === "devices") this.loadDevices();
      if (route.name === "device-detail") this.loadDetail(route.id);
    },

    stopTimers() {
      if (this.pollTimer) clearInterval(this.pollTimer);
      this.pollTimer = null;
      if (this.cooldownTimer) clearInterval(this.cooldownTimer);
      this.cooldownTimer = null;
    },

    startLogin(email, password) {
      this.loginError = "";
      return apiFetch("/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password }),
      })
        .then((body) => {
          setAccessToken(body.accessToken);
          localStorage.setItem("refresh_token", body.refreshToken);
          this.user = body.user;
          this.show("#/devices");
        })
        .catch((err) => {
          this.loginError =
            err instanceof ApiError && err.status === 0
              ? "تعذر الاتصال، حاول تاني"
              : "بريد أو كلمة مرور خاطئة";
        });
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
      const qs = this.route.platform ? `?platform=${this.route.platform}` : "";
      try {
        const list = await apiFetch(`/devices${qs}`);
        this.devices = Array.isArray(list) ? list : [];
        this.view = this.devices.length ? "ready" : "empty";
        this.startPolling(qs);
      } catch (err) {
        this.handleLoadError(err);
      }
    },

    startPolling(qs) {
      this.stopTimers();
      this.pollTimer = setInterval(async () => {
        if (document.hidden || this.route.name !== "devices") return;
        try {
          const list = await apiFetch(`/devices${qs}`);
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

    async loadDetail(id) {
      this.view = "loading";
      try {
        this.detail = await apiFetch(`/devices/${encodeURIComponent(id)}`);
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
        this.detail = await apiFetch(`/devices/${encodeURIComponent(id)}/reconnect`, {
          method: "POST",
        });
      } catch (err) {
        this.handleLoadError(err);
      }
    },

    async removeDevice(id) {
      try {
        await apiFetch(`/devices/${encodeURIComponent(id)}`, { method: "DELETE" });
        this.show("#/devices");
      } catch (err) {
        this.handleLoadError(err);
      }
    },

    async sendCommand(id) {
      try {
        await apiFetch(`/devices/${encodeURIComponent(id)}/commands`, {
          method: "POST",
          body: JSON.stringify({ command: this.commandChoice }),
        });
        this.flash = "تم إرسال الأمر";
        setTimeout(() => (this.flash = ""), 2500);
      } catch (err) {
        this.handleLoadError(err);
      }
    },

    flash: "",
  };
}

Alpine.store("panel", makePanelStore());

export default Alpine;
