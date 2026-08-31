# Missed Call Bridge — spike plugin

Throwaway instrument for the Part 11 spike. Not production code.

## What it checks

| Console line (filter `[MCB]`) | Proves |
| --- | --- |
| `plugin.js running` / snack shows | plugin loads on SP Android |
| `getAllProjects -> N projects` | API reads work |
| `persistDataSynced round-trip ok: true` | synced persistence works |
| `B2 request ok: {status: 200 ...}` | channel B2 (loopback HTTP) is viable |
| `B2 request FAILED — ...` | B2 blocked; capture the error, fall back to B1 |
| `taskCreated hook fired. payload: ...` | channel B1 (deep-link enrich) is viable |

## Load it

1. Set `minSupVersion` in `manifest.json` to the SP version on the test phone (Settings → About).
2. Zip the folder contents (`manifest.json` + `plugin.js` at the zip root), or point SP at the
   folder if the Android build offers "load from folder".
3. SP Android → Settings → Plugins → add the plugin. Enable it.
4. Open the dev console (desktop SP for the same account) or run `adb logcat | grep MCB`.

## Channel B2 test

The Android spike app must run an HTTP server on `127.0.0.1:47623` with a `GET /pending` route
returning JSON. Keep `BRIDGE_PORT` in `plugin.js` in sync. If `PluginAPI.request` fails, retry with
`BRIDGE_HOST = 'localhost'` and the matching `allowedHosts` entry.

## Channel B1 test

Fire the deep link from the app or `adb`:

```
adb shell am start -a android.intent.action.VIEW \
  -d 'com.super-productivity.app://create-task?title=Spike&notes=sp-cb:%7B%22num%22%3A%22%2B15550134%22%7D'
```

Then watch for `taskCreated hook fired`. If the payload has no task id, B1 needs a `getTasks()`
poll instead — note that in the spike findings.
