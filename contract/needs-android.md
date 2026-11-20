# needs-android.md

Every endpoint the Android app WILL call once a real backend exists. Until then `RegistrationApi` (fake transport, JSONL outbox) is the only implementation.

Legend: `retry` = automatic retries by the client, exponential backoff; `offline` = behavior when no connectivity.

## Endpoints

| # | Method | Path | Purpose | Auth |
|---|--------|------|---------|------|
| A1 | POST | /v1/registrations | Register device after lock step | none (bootstrap token) |
| A2 | POST | /v1/devices/{deviceId}/report | Periodic heartbeat + status | device token |
| A3 | GET | /v1/devices/{deviceId}/config | Pull command config | device token |
| A4 | POST | /v1/devices/{deviceId}/acks | Acknowledge executed command | device token |

## A1 POST /v1/registrations

| Field | Type | Constraint |
|-------|------|------------|
| name | string | 2..40 chars, trimmed |
| phone | string | E.164, 10..15 digits, default CC +20 |
| locale | string | "ar" |

| Expected status | Meaning |
|-----------------|---------|
| 201 | created, body: {deviceId: uuid, deviceToken: jwt} |
| 400 | validation failed |
| 409 | duplicate device identity |
| 500 | transient server failure |

| Property | Value |
|----------|-------|
| Retry policy | 3 attempts, 1s/2s/4s backoff |
| Offline | enqueue into filesDir/registrations/queue.jsonl, flush on next app start / CONNECTIVITY change |

## A2 POST /v1/devices/{deviceId}/report

| Field | Type | Constraint |
|-------|------|------------|
| ts | int64 | epoch millis |
| batteryPct | int | 0..100 |
| uptimeSec | int64 | >= 0 |

| Expected status | Meaning |
|-----------------|---------|
| 204 | accepted |
| 401 | rotate token via A1 re-register |
| 429 | honor Retry-After |
| 500 | transient |

| Property | Value |
|----------|-------|
| Retry policy | 1 retry after Retry-After or 5s |
| Offline | keep last report in memory, overwrite on success (last-write-wins) |

## A3 GET /v1/devices/{deviceId}/config

| Body | none |
|------|------|

| Expected status | Meaning |
|-----------------|---------|
| 200 | body: {commands: [{id: string, op: enum[ping, sync_config], payload: object}]} |
| 304 | unchanged, keep cached |
| 401 | rotate token |
| 500 | transient |

| Property | Value |
|----------|-------|
| Retry policy | none, next poll pulls again |
| Offline | serve last cached config |

## A4 POST /v1/devices/{deviceId}/acks

| Field | Type | Constraint |
|-------|------|------------|
| commandId | string | server issued |
| result | enum[ok, error] | |
| detail | string? | optional |

| Expected status | Meaning |
|-----------------|---------|
| 204 | accepted |
| 404 | unknown commandId, drop |
| 401 | rotate token |
| 429 | honor Retry-After |
| 500 | transient |

| Property | Value |
|----------|-------|
| Retry policy | infinite while offline, FIFO drain from local queue |
| Offline | enqueue to queue.jsonl alongside registrations |

## Current fake transport mapping

| Interface method | Backing store |
|------------------|---------------|
| RegistrationApi.register | filesDir/registrations/queue.jsonl (append-only) |
