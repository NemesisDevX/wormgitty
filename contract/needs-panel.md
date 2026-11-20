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
