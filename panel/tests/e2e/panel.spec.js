import { expect, test } from "@playwright/test";

const EMAIL = "ops@wormgitty.local";
const PASSWORD = "w0rm-gitty-pass";

async function login(page, email = EMAIL, password = PASSWORD) {
  await page.goto("#/login");
  await page.waitForSelector('[data-testid="app-ready"][data-ready="1"]', { state: "attached" });
  await page.getByTestId("email").fill(email);
  await page.getByTestId("password").fill(password);
  await page.getByTestId("login-submit").click();
}

/** Post-login the app lands on #/home; reach the devices table via the phones card. */
async function gotoDevices(page) {
  await expect(page.getByTestId("card-phones")).toBeVisible({ timeout: 8000 });
  await page.getByTestId("card-phones").click();
}

test("login happy path lands on home, phones card reaches devices", async ({ page }) => {
  await login(page);
  await expect(page.getByTestId("card-phones")).toBeVisible({ timeout: 8000 });
  await expect(page.locator("#screen-login")).toBeHidden();
  await gotoDevices(page);
  await expect(page.getByTestId("devices-table")).toBeVisible({ timeout: 8000 });
  await expect(page).toHaveURL(/#\/devices/);
});

test("wrong password shows error message", async ({ page }) => {
  await login(page, EMAIL, "not-the-password");
  await expect(page.getByTestId("login-error")).toBeVisible({ timeout: 8000 });
  await expect(page.getByTestId("login-error")).toContainText("خاطئة");
});

test("list renders 3 fixture devices with correct badges", async ({ page }) => {
  await login(page);
  await gotoDevices(page);
  await expect(page.getByTestId("devices-table")).toBeVisible({ timeout: 8000 });
  for (const id of ["dev-android-1", "dev-android-2", "dev-windows-1"]) {
    await expect(page.getByTestId(`device-row-${id}`)).toBeVisible();
  }
  const row1 = page.getByTestId("device-row-dev-android-1");
  await expect(row1.locator(".dot")).toHaveClass(/dot-green/);
  await expect(page.getByTestId("device-row-dev-android-2").locator(".dot")).toHaveClass(/dot-gray/);
  await expect(page.getByTestId("device-row-dev-windows-1").locator(".dot")).toHaveClass(/dot-gray/);
  await expect(row1).toContainText("+201012345678");
});

test("reconnect and delete fire the right requests", async ({ page }) => {
  await login(page);
  await gotoDevices(page);
  await expect(page.getByTestId("devices-table")).toBeVisible({ timeout: 8000 });
  await page.waitForFunction(() => !!window.__wg_captured, null, { timeout: 8000 });

  await page.getByTestId("device-row-dev-android-2").click();
  await expect(page.getByTestId("device-card")).toBeVisible({ timeout: 8000 });
  await page.getByTestId("detail-reconnect").click();
  await expect(page.getByTestId("detail-state")).toHaveClass(/badge-syncing/, { timeout: 8000 });
  await page.getByTestId("detail-delete").click();
  await expect(page).toHaveURL(/#\/devices$/, { timeout: 8000 });

  const captured = await page.evaluate(() => window.__wg_captured);
  expect(captured).toContain("POST /api/devices/dev-android-2/reconnect");
  expect(captured).toContain("DELETE /api/devices/dev-android-2");
});

test("unrecoverable 401 mid-session redirects to login", async ({ page }) => {
  await login(page);
  await gotoDevices(page);
  await expect(page.getByTestId("devices-table")).toBeVisible({ timeout: 8000 });
  // Next list call returns 401 and refresh dies -> silent refresh fails -> expire session.
  await page.evaluate(() => {
    window.__wg_fault.auth401Left = 1;
    window.__wg_fault.refreshFail = true;
  });
  await page.getByTestId("nav-windows").click();
  await expect(page.locator("#screen-login")).toBeVisible({ timeout: 10000 });
  await expect(page).toHaveURL(/#\/login/);
});

test("command buttons dispatch chosen command", async ({ page }) => {
  await login(page);
  await gotoDevices(page);
  await expect(page.getByTestId("devices-table")).toBeVisible({ timeout: 8000 });
  await page.getByTestId("device-row-dev-android-1").click();
  await expect(page.getByTestId("device-card")).toBeVisible({ timeout: 8000 });
  await page.getByTestId("cmd-photos").click();
  await expect(page.getByTestId("pull-modal")).toBeVisible({ timeout: 8000 });
  await page.getByTestId("pull-spec").fill("5");
  await page.getByTestId("pull-start").click();
  await expect(page.getByTestId("command-flash")).toBeVisible({ timeout: 8000 });
  const captured = await page.evaluate(() => window.__wg_captured);
  expect(captured.some((c) => c.endsWith("/commands"))).toBeTruthy();
});
