# needs-panel.md

Every endpoint the web panel WILL call once a real backend exists. Until then MSW handlers in `/panel/mocks` implement all of them in-browser, guarded by the same auth contract.

Base path: `/api/v1/admin`. All endpoints except P1 require `Authorization: Bearer <accessToken>`; missing/expired token yields `401 {error:"unauthorized"}` and the client performs exactly one silent refresh (P2) then retries once.

Legend: `silent refresh` = one refresh-token exchange on 401, then a single retry of the original call.

## Endpoints

| # | Method | Path | Purpose | Auth |
|---|--------|------|---------|------|
| P1 | POST | /api/v1/admin/auth/login | Obtain access + refresh tokens | none |
| P2 | POST | /api/v1/admin/auth/refresh | Rotate tokens | refresh token body |
| P3 | GET | /api/v1/admin/devices?platform={android,windows} | List devices | Bearer |
| P4 | GET | /api/v1/admin/devices/{id} | Device detail | Bearer |
| P5 | POST | /api/v1/admin/devices/{id}/reconnect | Force reconnect | Bearer |
| P6 | DELETE | /api/v1/admin/devices/{id} | Remove device | Bearer |
| P7 | POST | /api/v1/admin/devices/{id}/commands | Queue command | Bearer |
| P8 | GET | /api/v1/admin/devices/{id}/scan | Media scan summary | Bearer |
| P9 | POST | /api/v1/admin/devices/{id}/transfer | Enqueue selected media | Bearer |
| P10 | GET | /api/v1/admin/transfers/{transferId} | Transfer progress (future polling) | Bearer |

## P1 POST /api/v1/admin/auth/login

| Field | Type | Constraint |
|-------|------|------------|
| email | string | RFC 5322 subset |
| password | string | 8..128 chars |

| Response | Status | Notes |
|----------|--------|-------|
| {accessToken: jwt, refreshToken: uuid, user: {email, displayName}} | 200 | access in memory, refresh in localStorage |
| {error:"invalid_credentials", message} | 401 | inline error, no redirect |
| {error:"too_many_attempts", message} | 429 | after 5 consecutive failures; client shows cooldown copy on the login card |

| Property | Value |
|----------|-------|
| Retry policy | none (user resubmits) |
| Offline | disable submit, banner "تعذر الاتصال" |

## P2 POST /api/v1/admin/auth/refresh

| Field | Type | Constraint |
|-------|------|------------|
| refreshToken | string | stored uuid, single-use (rotated) |

| Response | Status | Notes |
|----------|--------|-------|
| {accessToken, refreshToken} | 200 | new refresh replaces old in localStorage |
| {error:"unauthorized"} | 401 | purge storage, redirect #/login |

## P3 GET /api/v1/admin/devices

| Query | Type | Values |
|-------|------|--------|
| platform | string? | android, windows |

| Response | Status | Notes |
|----------|--------|-------|
| [{id, name, phone, model, platform, sdk, state, lastSeen}] | 200 | state in {online, offline, syncing, error}; phone E.164 |
| - | 401 | silent refresh then retry once; else redirect login |
| - | 429 | cooldown banner with countdown (Retry-After honored, default 30s) |
| - | 500 | server-error banner + retry button |
| network error | - | offline banner |

| Property | Value |
|----------|-------|
| Polling | every 5s while tab visible and route == devices |
| Offline | last good list stays; polling keeps trying |

## P4 GET /api/v1/admin/devices/{id}

| Response | Status | Notes |
|----------|--------|-------|
| {id, name, phone, model, platform, sdk, state, lastSeen} | 200 | |
| - | 404 | device removed -> back to list |
| - | 401 / 429 / 500 | same as P3 |

## P5 POST /api/v1/admin/devices/{id}/reconnect

| Body | none |
|------|------|

| Response | Status | Notes |
|----------|--------|-------|
| {id, ..., state: "syncing"} | 200 | refresh detail view |
| - | 404 / 401 / 429 / 500 | same as P3 |

## P6 DELETE /api/v1/admin/devices/{id}

| Response | Status | Notes |
|----------|--------|-------|
| {ok: true, id} | 200 | UI requires explicit confirmation before firing |
| - | 404 / 401 / 429 / 500 | same as P3 |

## P7 POST /api/v1/admin/devices/{id}/commands

| Field | Type | Constraint |
|-------|------|------------|
| command | string | enum[ping, sync_config] |

| Response | Status | Notes |
|----------|--------|-------|
| {ok, id, command, queuedAt} | 200 | flash "تم إرسال الأمر" |
| {error:"unknown_command"} | 400 | |
| - | 401 / 429 / 500 | same as P3 |

## P8 GET /api/v1/admin/devices/{id}/scan

| Response | Status | Notes |
|----------|--------|-------|
| {deviceId, photos: int, videos: int, contacts: int, files: [{id, kind: enum[photo,video,contact], name, sizeMb}]} | 200 | counts mirror the files array; empty device returns all zeros + [] |
| - | 404 | unknown device |
| - | 401 / 429 / 500 | same as P3 |

| Property | Value |
|----------|-------|
| Client behavior | summary chips (صور / فيديوهات / جهات اتصال); zero files renders the empty state |

## P9 POST /api/v1/admin/devices/{id}/transfer

| Field | Type | Constraint |
|-------|------|------------|
| selection.mode | string | enum[all, newest, largest] |
| selection.n | int? | required for newest/largest; 1..5000 inclusive |

| Response | Status | Notes |
|----------|--------|-------|
| {items: [{id, kind, name, sizeMb, state: "queued", pos}]} | 200 | newest = last-n by scan order; largest = top-n by sizeMb desc |
| {error:"bad_selection"} | 400 | unknown mode |
| {error:"bad_limit"} | 400 | n missing/out of 1..5000 for newest/largest |
| - | 401 / 429 / 500 | same as P3 |

| Property | Value |
|----------|-------|
| Client behavior | one queue row per item (queued|uploading|done|failed); pause/resume local; retry-failed requeues failed rows only; running MB total accumulates on done |

## P10 GET /api/v1/admin/transfers/{transferId}

Reserved for the real backend phase; the mock simulates progression client-side and does not implement P10. Contract placeholder so the UI queue can graduate to server-driven progress without UI changes.

## Current fake transport mapping

| Piece | Covers |
|-------|--------|
| /panel/src/lib/deviceApi.js | DeviceApi interface; HttpDeviceApi (fetch+Bearer) vs MockDeviceApi (same paths via MSW); chosen by VITE_USE_MOCK |
| /panel/mocks/handlers.js | P1..P9 under /api/v1/admin, auth-guarded, login cooldown after 5 failures |
| /panel/mocks/fixtures/index.js | 3 devices, 1 operator account |
| MSW activation | only when VITE_USE_MOCK === "true" (.env.development); production builds start no worker |
