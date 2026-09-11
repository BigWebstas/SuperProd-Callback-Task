# Callback

An Android app that turns missed calls into "call back" tasks in
[Super Productivity](https://super-productivity.com), filtered by your own rules
(known vs unknown caller, declined calls, per-project target).

Status: **building.** The production app lives in [`android/`](android/).

## How it works

1. A foreground service watches for missed calls via the telephony callback, with
   the call log as the source of truth and a `ContentObserver` as backup.
2. A rule engine decides whether each call is worth capturing.
3. Kept calls go into a Room-backed dedup ledger + retry queue, then straight out
   as a `POST /tasks` to Super Productivity's **Local REST API**.
4. `RetryWorker` (WorkManager) drains anything left `PENDING`; `BootReceiver`
   restarts the service after a reboot.
5. A home-screen widget shows whether the monitor is running (green) or not (red).

## Requirements

- A **Super Productivity desktop instance**, always running on the same LAN, with
  the Local REST API enabled.
- The API binds `127.0.0.1` only, so it must be reached one of two ways. The
  Host/LAN IP field in the app accepts either:
  - a bare LAN IP/hostname (optionally `host:port`, default port `3876`),
    reached through a direct **port-forward** on the desktop (e.g. `socat`,
    an SSH tunnel, or nginx). Every request the app sends carries a literal
    `Host: localhost` header — the server 403s anything else.
  - a full `https://...` URL, reached through a **reverse proxy** (e.g. for
    remote/off-LAN access over TLS). The app does *not* force the `Host:
    localhost` header in this case — the proxy is expected to rewrite it on
    the backend leg instead. See [`docs/reverse-proxy/`](docs/reverse-proxy/)
    for a confirmed-working IIS 10 + ARR setup.
- Sideloaded APK, not Google Play: `READ_CALL_LOG` is a Play-restricted permission.

## Build

```
cd android && ./gradlew installDebug
```

Gradle 8.14.3 (wrapper), JDK 17+, Android SDK 34 / build-tools 34. KSP drives Room.

## Setup

Open the app, then: grant permissions → enter the API host and bearer token →
pick a project → choose which calls to capture → **Start monitor**.

## Layout

| Path | What |
|---|---|
| `android/` | the production app (`dev.mcb.callback`) |
| `spike/` | throwaway proof-of-concept code, kept for reference |
| `docs/scope.html` | full design doc and history |
