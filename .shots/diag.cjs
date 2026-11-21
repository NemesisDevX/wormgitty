const { chromium } = require("D:/wormgitty/panel/node_modules/@playwright/test");
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  page.on("console", (m) => console.log("CONSOLE:", m.type(), m.text().slice(0, 200)));
  page.on("requestfailed", (r) => console.log("REQFAIL:", r.url(), r.failure()?.errorText));
  page.on("response", (r) => { if (r.url().includes("/api/")) console.log("API:", r.status(), r.url()); });
  await page.goto("http://localhost:5173/", { waitUntil: "networkidle" });
  await page.fill('[data-testid="email"]', "alice");
  await page.fill('[data-testid="password"]', "pw_alice_strong");
  await page.click('[data-testid="login-submit"]');
  await page.waitForTimeout(4000);
  const state = await page.evaluate(() => {
    const st = window.Alpine?.store?.("panel");
    return { route: st?.route?.name, view: st?.view, msg: st?.message, err: st?.loginError, hash: location.hash, user: !!st?.user };
  });
  console.log("STATE:", JSON.stringify(state));
  console.log("BODY:", (await page.textContent("body"))?.replace(/\s+/g, " ").slice(0, 300));
  await browser.close();
})().catch((e) => { console.error("FAIL:", e.message); process.exit(1); });
