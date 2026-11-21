# Worm GPT (repo slug wormgitty)

Two deliverables, one repo, zero real networking. Everything talks to a fake transport behind an interface.

| Part | Path | Stack |
|------|------|-------|
| Android agent | `/android` | Kotlin, Compose, minSdk 26, targetSdk 35, single `:app` |
| Ops panel | `/panel` | Vite + vanilla JS + Alpine.js, MSW mocks |
| Contracts | `/contract` | API spec the next phase implements mechanically |
| Scripts | `/scripts` | `dev.sh`, `gate.sh` |

## Gate

GitHub Actions is disabled (quota exhausted). The gate is `scripts/gate.sh`, run locally.

## Hard rule

Neither side calls a real API.

- Android: `RegistrationApi` (`app/src/main/java/com/wormgitty/agent/RegistrationApi.kt`) is the only network-shaped seam. Its production impl, `FakeRegistrationApi`, durably appends JSON lines to `filesDir/registrations/queue.jsonl`.
- Panel: every HTTP call goes through `src/lib/api.js` and MSW service-worker handlers (`mocks/handlers.js`), in dev server and Playwright alike.

`/contract/needs-android.md` and `/contract/needs-panel.md` record every endpoint, field, status code, retry policy, and offline behavior. They are tables only, and they are the spec for the real-backend phase.

## Panel routes

SPA with hash routing served from `:5173` (strict port):

| Route | Screen |
|-------|--------|
| `#/login` | email/password sign-in |
| `#/devices?platform=android\|windows` | device list, 5s polling, state badges |
| `#/devices/{id}` | detail: reconnect, delete, command select (ping, sync_config) |

Designed states: `loading`, `empty`, `offline`, `401 (silent refresh then redirect)`, `429 (cooldown)`, `500 (retry)`.

## Dependency justification

Android (see `android/app/build.gradle.kts` header comment on each line). Panel:

| Package | Why |
|---------|-----|
| alpinejs | declarative UI without a framework build step |
| msw | fake transport implementing the contract in dev/tests |
| vite | dev server + bundler on :5173 |
| vitest | unit tests (E.164, badge, fetch wrapper, view states) |
| @playwright/test | browser E2E over the MSW-mocked app |
| jsdom | DOM/localStorage emulation for vitest |

## Commands

```sh
scripts/dev.sh panel        # vite on :5173 with MSW
scripts/gate.sh             # full gate, exits non-zero on any failure
```

Details and ops runbook: `docs/OPS.md`.
