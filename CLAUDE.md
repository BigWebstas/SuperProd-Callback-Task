# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

Building. The production app lives under `android/`:

- `android/app/` — Kotlin app, package `dev.mcb.callback`. `CallDetector` + `CallLogReader`
  (telephony trigger, call-log source of truth, `ContentObserver` backup) feed `RuleEngine`
  (known/unknown-caller filter) and a Room-backed dedup ledger + retry queue
  (`data/CallbackDb.kt`, `QueueRepository`). Accepted calls go straight out via
  `net/SuperProductivityApi.kt` (OkHttp) to Super Productivity's Local REST API over the user's
  LAN port-forward; `work/RetryWorker.kt` (WorkManager) retries stale `PENDING` rows.
  `CallMonitorService` is the foreground service; `boot/BootReceiver` restarts it after reboot.
  `ui/MainActivity` covers permissions, write-path config (host/port/token, test-connection),
  the rule toggle, start/stop, and a recent-queue view. Build: `cd android && ./gradlew
  installDebug` (same toolchain as the spike: wrapper pins Gradle 8.14.3; JDK 17+, SDK 34,
  build-tools 34; KSP drives Room). Verified on a real device: detect → rule → enqueue → POST →
  `SENT` with a real remote task id, end to end.
- `spike/` — throwaway, kept as reference. `spike/android/` proved call-log read and found the
  `CallLogReader` `LIMIT`-token bug (fixed, carried into `android/`). `spike/plugin/` proved the
  plugin-bridge approach (B1/B2) dead — see Spike results below.

No lint or automated test setup exists yet.

- **Full scope doc:** `docs/scope.html` (repo snapshot) — canonical living copy is the artifact at
  https://claude.ai/code/artifact/bb79e342-edf5-4054-b4a1-9c6b2d88b533
- **Settled decisions:** `~/.claude/projects/-home-webstas-Documents-Github-SuperProd-Callback-Task/memory/missed-call-app-scope.md`

Read the scope doc for the full history — Parts 1-3 and 5-10 still hold; Part 2's deep link, Part
4's plugin bridge, and Part 10's phasing are superseded by the spike results below.

## What this is

An Android app that detects missed calls and turns each into a "call back" task in
[Super Productivity](https://super-productivity.com), filtered by user rules (known vs unknown
caller, quiet hours, minimum ring duration, per-contact allow/deny).

## Settled constraints

- **Distribution:** sideloaded APK, not Google Play. `READ_CALL_LOG` is a Play-restricted
  permission granted only to default dialer apps; sideloading sidesteps the policy review.
- **Scope:** missed calls only — not rejected or blocked.
- **A Super Productivity desktop instance is required**, always running and reachable on the same
  LAN as the phone. This reopens what was originally settled the other way — see Spike results.
- **Super Sync is unusable directly.** The user self-hosts a Super Sync server with end-to-end
  encryption on. Every sync operation is ciphertext without the user's key, so the app can neither
  push task ops nor read state (including the Local File Sync JSON, which is also encrypted).

## Spike results (2026-08-31) — the write path

Ran the Part 11 spike on a Pixel 9 Pro XL against Super Productivity Android 18.19.0. All three
planned plugin-bridge channels failed:

- **B2 (loopback HTTP)** — `PluginAPI.request` never reaches the app's `127.0.0.1` bridge server,
  even though the bridge itself is healthy. No console access to see why.
- **B1 (deep link, then enrich)** and **Path A (plain deep link)** — both depend on
  `com.super-productivity.app://create-task`, which **does not exist as an intent filter at all**
  on this SP Android build (confirmed via `dumpsys package`'s Activity Resolver Table). Likely a
  desktop/Electron-only Capacitor handler, not something the Android build ships.

The replacement, confirmed working end to end: Super Productivity's **Local REST API**
(`GET`/`POST`/`DELETE /tasks`, no `/api` prefix), reached over a **port-forward on the desktop's
LAN interface** (the API itself binds `127.0.0.1` only, hardcoded, no config to change it). Tested
from the phone's real Wi-Fi and from the production app's own OkHttp client — both created a real
task with `201`/`200` and a real remote id.

One hard requirement: the API 403s any request whose `Host` header isn't a literal `localhost`,
regardless of the actual address/port connected to (a DNS-rebinding guard). Every request must set
`Host: localhost` explicitly — `SuperProductivityApi.kt` does this on every call.

Also found and fixed: `CallLogReader` appended `LIMIT n` into a SQL sort-order string, which
newer Android (17+) rejects (`IllegalArgumentException: Invalid token LIMIT`). Fixed via the
provider's `limit` URI query parameter — see `docs/scope.html` for the full writeup.

## Next action

Polish the app-core slice per `docs/scope.html` Part 10: quiet hours and the rest of Part 7's rule
set, a real history UI, OEM battery-killer guidance, and a project picker — whether the Local REST
API exposes a projects list is untested; if not, Part 2's plaintext-export approach is the
fallback. Also worth doing: point the production app at a stable, always-reachable desktop +
forward setup rather than the ad hoc one used for the spike.
