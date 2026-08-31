# MCB spike — Android side

Throwaway app for the Part 11 spike. One foreground service does three jobs:

- **Missed-call detection** (Task 1): a `TelephonyCallback` / `PhoneStateListener` trigger plus a
  `CallLog.Calls` query for the newest `MISSED_TYPE` row, with a `ContentObserver` backup.
- **Room-backed dedup + retry queue**: `processed_call` is the dedup ledger (PK `CallLog._ID`);
  `queued_task` holds one row per call, `PENDING → SENT` on ack, `→ FAILED` after
  `MAX_ATTEMPTS` stale retry passes. `RetryWorker` (WorkManager, 15 min) ages stale rows; a
  one-shot fires after a failed deep-link send.
- **Loopback HTTP server** on `127.0.0.1:47623` (Task 3) serving `PENDING` rows for the Super
  Productivity plugin to poll with `PluginAPI.request` (channel B2).

Data layer: `data/CallbackDb.kt` (entities + DAO), `QueueRepository.kt` (the only door to it),
`RetryWorker.kt`.

## Build

```
cd spike/android
./gradlew installDebug
```

Needs JDK 17+ and an Android SDK with platform 34 and build-tools 34 (`local.properties` with
`sdk.dir=…`, or `ANDROID_HOME` set). The wrapper pins Gradle 8.14.3. Verified: `assembleDebug`
produces `app/build/outputs/apk/debug/app-debug.apk`, warnings only (`PhoneStateListener`
deprecation).

## Run the spike

1. `./gradlew installDebug` and launch **MCB spike** on the same phone that has Super Productivity.
2. Tap **Grant permissions**. Approve call log, phone, contacts, notifications.
3. Tap **Start bridge + detector**.

### Task 1 — detection

4. Call the phone from another number and let it ring out. Watch the log:
   `state: RINGING` → `state: IDLE` → `MISSED CALL via telephony -> id=… <number> …` →
   `queued missed call id=…`.
5. **Show newest missed call** reads the log directly. **Scan now** re-runs the query through the
   service. History is baselined at start, so only calls missed after **Start** are queued.

### Task 3 — channel B2

6. Tap **Self-test GET /pending** — expect `-> 200 {"calls":[...]}`. Proves the server works
   before the plugin is involved.
7. Load `../plugin/` into Super Productivity (see that folder's README). Watch its console.
   - Plugin logs `B2 request ok` → **B2 is viable, this is the build.**
   - Plugin logs `B2 request FAILED` → copy the error into the spike notes, move to channel B1.
8. To retry as `localhost`: set `HOST` in `BridgeServer.kt` and `BRIDGE_HOST` in `plugin.js` to
   `"localhost"`, keep the matching `allowedHosts` entry, rebuild both.
9. The plugin acks each call, so **Show queue** should show rows flip `PENDING → SENT` and
   `/pending` empties.

### Queue + retry

- **Show queue** dumps every `queued_task` row with state and attempt count.
- **Deliver oldest via deep link** exercises channel B1: fires `create-task`. If Super
  Productivity is missing it catches `ActivityNotFoundException` and the row stays `PENDING`
  with a bumped attempt, then `FAILED` after `MAX_ATTEMPTS`.
- **Retry pass now** ages stale `PENDING` rows immediately instead of waiting for the worker.
- **Re-queue FAILED** resets `FAILED` rows to `PENDING`, attempts `0`.
- **Clear queue + dedup** wipes both tables. After this, the next scan re-enqueues the newest
  missed call from history once.

## Routes

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/health` | `{ ok, pending, counts }` |
| GET | `/pending` | `{ calls: [ { id, number, name, timestamp, rule, taskRowId } ] }` — `PENDING` rows |
| POST | `/ack` `{id}` | mark the matching row `SENT` |
| POST | `/debug/seed` | enqueue a fake call (negative id) |

Non-loopback clients get `403`. The queue is in `callback-spike.db` and survives restarts.

## Logs

In-app panel, plus `adb logcat -s MCB`.
