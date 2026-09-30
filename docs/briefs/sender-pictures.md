Repo: Rampart, a Kotlin Compose Desktop mail client (read CLAUDE.md and docs/open-tracking.md first). The optional companion server is in server/ (server/src/main/kotlin/org/rampart/tracker/Tracker.kt: a com.sun.net.httpserver server with routes like /o/, /labels; Bearer-token auth helpers exist there, reuse them). The client talks to it from TrackingClient.kt using Settings.trackingServer and the saved token. Sender avatars are drawn by Avatar(...) (search for `fun Avatar`; used in MessageList.kt near line 825 and the reading pane header), currently initials on a colour.

Kaneo RAM-27: real sender pictures, like the other client the user likes.

Privacy is the core of this: fetching a picture keyed on who mailed you tells somebody that you looked. So:
- Pictures are fetched THROUGH the user's own companion server, never directly from the sender's domain, so the sender's server never sees the reader's IP or the time they opened the message. If no companion server is configured, the feature is off and the setting says why in one sentence.
- Nothing is fetched for mail in Junk (setting "Show pictures in Junk", default off: a logo lends a phishing mail legitimacy).

Server (server/):
- GET /icon?domain=<domain>&email=<sha256 of lowercased address>, Bearer token required (same token as /opens), so the route is not an open proxy.
- Order: Libravatar for the address hash (https://seccdn.libravatar.org/avatar/<sha256>?s=96&d=404; honour Libravatar's DNS federation only if simple, otherwise the central service); then the domain's icon: fetch https://<domain>/ (max 256 KB, 5 s timeout, follow at most 3 redirects, https only), read <link rel="icon"|"apple-touch-icon"> and pick the largest, fall back to https://<domain>/favicon.ico. Return the image bytes with its content type, or 404.
- Validate the domain strictly (letters, digits, dots, hyphens; no IPs, no localhost, no private ranges after DNS resolution) so the server cannot be pointed at internal hosts (SSRF).
- Cache results on disk (and misses too) for 7 days, capped at a few thousand entries.
- Skip the domain step for big free-mail domains (gmail.com, outlook.com, hotmail.com, yahoo.com, icloud.com, proton.me, aol.com and similar): their icon says nothing about the sender. Libravatar still applies.

Client:
- Setting under Appearance: "Sender pictures" on or off (default on when a companion server is configured) and "Show pictures in Junk" (default off). Synced via SettingsSyncMerge.kt.
- Avatar(...) shows the picture when one is cached, the initials otherwise; never a blank or a broken image. Pictures load in the background with a small in-memory LRU and an on-disk cache under the app's data folder (7 days), keyed by address, and at most a few requests at a time. Scrolling a long list must not stall.

Tests: server side for domain validation (rejects IPs, localhost, private addresses, odd characters), icon-link picking from sample HTML, free-mail skip; client side for the cache key and the Junk rule.

Match surrounding style: KDoc explaining why, plain English. No em dashes, en dashes or the ellipsis character. Do not run gradle. Do not commit. Only edit files.
