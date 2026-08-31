# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

Pre-code. The only source is the spike under `spike/`:

- `spike/plugin/` — throwaway Super Productivity plugin (`manifest.json` + `plugin.js`) that
  probes the API and both app→plugin channels.
- `spike/android/` — throwaway Kotlin app. One foreground service runs the missed-call detector
  (`CallDetector` + `CallLogReader`: telephony trigger, call-log source of truth, `ContentObserver`
  backup), a Room-backed dedup ledger + retry queue (`data/CallbackDb.kt`, `QueueRepository`,
  `RetryWorker` on WorkManager), and a NanoHTTPD server on `127.0.0.1:47623` serving `PENDING`
  rows for the plugin to poll (channel B2). Build: `cd spike/android && ./gradlew installDebug`
  (wrapper pins Gradle 8.14.3; needs JDK 17+, SDK 34, build-tools 34; KSP drives Room). The
  debug build is known to compile.

Neither is production code. No app-level build, lint, or test setup exists yet.

- **Full scope doc:** `docs/scope.html` (repo snapshot) — canonical living copy is the artifact at
  https://claude.ai/code/artifact/bb79e342-edf5-4054-b4a1-9c6b2d88b533
- **Settled decisions:** `~/.claude/projects/-home-webstas-Documents-Github-SuperProd-Callback-Task/memory/missed-call-app-scope.md`

Read the scope doc before starting the build. The sections below summarise what it settles.

## What this is

An Android app that detects missed calls and turns each into a "call back" task in
[Super Productivity](https://super-productivity.com), filtered by user rules (known vs unknown
caller, quiet hours, minimum ring duration, per-contact allow/deny).

## Settled constraints

- **Distribution:** sideloaded APK, not Google Play. `READ_CALL_LOG` is a Play-restricted
  permission granted only to default dialer apps; sideloading sidesteps the policy review.
- **Scope:** missed calls only — not rejected or blocked.
- **No Super Productivity desktop** is involved, so the Local REST API (Electron-only) is out.
- **Super Sync is unusable directly.** The user self-hosts a Super Sync server with end-to-end
  encryption on. Every sync operation is ciphertext without the user's key, so the app can neither
  push task ops nor read state (including the Local File Sync JSON, which is also encrypted).

## Architecture the build should follow

The app never talks to Super Sync. It feeds a **Super Productivity plugin** running inside the SP
Android app, which writes tasks via the Plugin API on the *decrypted* in-memory state — so E2EE
never enters our code. Plugin install on SP Android is confirmed working.

Two app→plugin channels, both fully on-device, no server:

- **B2 (preferred):** the app runs an HTTP server bound to `127.0.0.1` from its foreground service;
  the plugin polls it with `PluginAPI.request` (manifest `permissions: ["http"]`,
  `allowedHosts: ["127.0.0.1"]`). Silent, no focus stealing. Depends on `PluginAPI.request`
  reaching loopback cleartext on Android — unverified, the spike settles it.
- **B1 (fallback):** the app fires the `com.super-productivity.app://create-task` deep link with a
  compact marker packed into the task `notes`; the plugin's `TASK_CREATED` hook reads the marker,
  applies project / tags / due day via `updateTask`, then strips the marker.
- **Path A (last resort, if no plugin channel works):** plain `create-task` deep link only, with the
  target-project dropdown parsed from a plaintext SP data export the user imports. Loses tags and
  due dates.

### Components (once building)

- **Detection:** foreground service holding a `TelephonyCallback.CallStateListener` (API 31+) or
  `PhoneStateListener` (26–30) as the *trigger*, plus a `CallLog.Calls` query for the newest
  `MISSED_TYPE` row as the *source of truth*. A `ContentObserver` on the call-log URI is a backup
  trigger. Dedup on `CallLog._ID` in a Room table — both the state trigger and the observer can
  fire for one call.
- **Contact name:** reverse-lookup via `ContactsContract.PhoneLookup`, `READ_CONTACTS` optional,
  fall back to the raw number.
- **The plugin** (`manifest.json` + `plugin.js`): the polling/enrich loop, plus a
  `registerConfigHandler` + `openDialog` settings UI with real `<select>` dropdowns from
  `getAllProjects()` / `getAllTags()`. Config in `persistDataSynced`, secrets in `setSecret`,
  per-call dedup keyed by call id.
- The plugin runs only while SP is open, so tasks/enrichment land the next time the user opens SP.

## Next action

Run the spike in Part 11 of the scope doc (~2 days, real device) to lock the channel (B2 / B1 /
Path A) before writing production code. Fold its findings back into the scope doc and the memory
file.
