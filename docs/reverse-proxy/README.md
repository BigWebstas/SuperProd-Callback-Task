# IIS 10 reverse proxy for Super Productivity's Local REST API

Puts a public HTTPS front (e.g. `https://sprest.example.net`) in front of the
Local REST API, which otherwise only binds `127.0.0.1` on the desktop. Confirmed
working end to end against a real IIS 10 box.

In the Android app, set the **Host or LAN IP** field to the full URL (e.g.
`https://sprest.example.net`) — the port field is ignored in that case.

## Prerequisites

1. Install the **URL Rewrite** and **Application Request Routing (ARR)**
   IIS extensions.
2. Enable ARR's proxy feature and turn off host-header preservation
   server-wide (both are applicationHost.config-only settings — a site-level
   `web.config` `<proxy>` element is parsed but silently ignored):
   ```
   %windir%\system32\inetsrv\appcmd.exe set config -section:system.webServer/proxy /enabled:"True" /commit:apphost
   %windir%\system32\inetsrv\appcmd.exe set config -section:system.webServer/proxy /preserveHostHeader:"False" /commit:apphost
   ```
3. Drop [`web.config`](web.config) into the site's root, with `3876` in the
   rewrite rule's target URL changed to match your Local REST API port if
   it's not the app's default.
4. Bind the HTTPS site **without** a required hostname match. The app
   deliberately does *not* send `Host: localhost` to this proxy (only for a
   direct-LAN target) — see the note below — so the incoming request's Host
   header is the real public domain, and the site binding needs to route on
   that, not reject it.

## Why the Host header is handled this way

Super Productivity's Local REST API 403s (`Invalid Host header`) any request
whose `Host` header isn't a literal `localhost` — a DNS-rebinding guard.

The rewrite rule's target is `http://localhost:3876/{R:1}` (hostname
`localhost`, not `127.0.0.1`). With `preserveHostHeader="false"`, ARR takes
the outbound `Host` header from that target's authority, so the backend leg
(IIS → Super Productivity) always carries `Host: localhost`, regardless of
what the client sent.

The Android client does *not* also force `Host: localhost` on the client → IIS
leg for a proxy target (it does for a direct-LAN target). Confirmed by testing
both ways against this proxy: forcing it doesn't affect TLS SNI/cert
checks (those use the connection's real target), but it does break IIS's own
HTTP-level host-header site routing — the request lands on the wrong site and
404s instead of reaching this rewrite rule at all.
