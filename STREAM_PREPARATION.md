# Optional server stream preparation (v1)

This experimental extension lets a selected HTTP source prepare its startup buffer before a player opens media. It addresses servers that otherwise withhold HTTP response headers longer than a player's read timeout. It does not change global HTTP timeouts, request speculative downloads during source discovery, or start a local torrent engine.

An addon may advertise `stream.behaviorHints.streamPreparation = {"url": "https://media.example/preparation/opaque-reference"}` alongside its normal `stream.url`. Both URLs must share their exact origin (scheme, hostname, effective port). The capability/reference must remain usable for the intended resume period. Avoid embedding credentials that expire before the cached source does. Old addons without this optional hint follow the existing playback path.

After actual selection Nuvio POSTs an empty body, then GETs approximately every second. A server returns immediate HTTP 200/202 JSON, bounded to 16 KiB:

```json
{
  "version": 1,
  "state": "preparing",
  "stage": "buffering",
  "progress": 0.5,
  "downloadedBytes": 52428800,
  "targetBytes": 104857600,
  "downloadSpeedBytesPerSecond": 5242880,
  "peers": 4,
  "seeders": 2,
  "etaSeconds": 10,
  "playbackUrl": null,
  "error": null
}
```

`state` is `preparing`, `ready`, or `failed`; `stage` is `source`, `metadata`, `buffering`, `ready`, or `failed`. Unknown numeric telemetry is absent/null. Numeric fields are nonnegative, progress is finite and in [0,1]. Progress describes only the contiguous startup target, not completion of the full media file. ETA is an estimate and should be omitted unless measured smoothed speed supports it. Error values must be safe enums, never raw server exceptions, paths, source URLs or tokens. Nuvio displays its own actionable generic failure message.

Only `ready` may contain `playbackUrl`, and it must contain an absolute same-origin HTTP(S) media URL. This may renew expired playback authorization on resume: Nuvio replaces the old media URL with this fresh URL and retains the selected source's request headers. It does not fall back to an expired or unprepared media URL after failure. The preparation reference is retained in last-link reuse and player navigation. An invalid advertised hint fails closed.

Each request has connect/read/write/call timeouts of at most 10 seconds; preparation has a 180-second total deadline. Preparation redirects are bounded to five, validated before following, and cannot forward credentials across origins. Userinfo, fragments, control characters, non-HTTP(S) schemes and oversized/invalid responses are rejected. Media-source/player initialization and media probing happen only after ready. No backend response details or capability URLs are added to logs.

Back, source changes, failures and deadline expiry cancel preparation and send bounded, best-effort DELETE to release interest. The server must treat POST/DELETE idempotently and expire abandoned interest itself, since a device/process can disappear before DELETE arrives. Ready interests remain held through media handoff until client release; servers should bound their ready handoff lease. Normal media requests take over data delivery. A failed DELETE must not prevent navigation.

The guarantee covers preparation requests/redirects and validation of the returned ready URL. ExoPlayer additionally fences prepared media redirects and recovery probes to that origin. Prepared ExoPlayer clients use normal TLS validation and do not inherit the legacy independent trust-all fallback, which would bypass the origin fence. Network AFR preflight is skipped for hinted sources because native/utility probes do not share this fence; normal decoder-track frame-rate detection remains available. MPV downstream media redirects retain its existing engine behavior; the trusted backend must keep media redirects same-origin. This extension is not a proxy or a new MPV transport.

Version 1 prepares the startup target at the beginning of the selected file. A resume/seek can therefore encounter another cold range; no byte offset is guessed from average bitrate. Only streams that completed preparation receive a 150-second media read timeout, allowing a server with a bounded 120-second cold-range gate to answer. Their ExoPlayer stall threshold is also 150 seconds; MPV uses a 150-second network/stall/idle allowance and 180-second absolute startup bound. Ordinary sources retain their existing timeouts and watchdogs. A cold resume can still show the normal buffering indicator; v1 does not report seek-specific server progress. Player buffer settings and available memory remain separate limits.

The existing loading overlay displays stage, startup percentage, bytes/target, speed, peers/seeds and optional estimated remaining time. These are server measurements, separate from the player's own buffer and from local-engine torrent statistics. Internal engine selection remains unchanged.

## Validation

Unit coverage exercises strict parsing/origin validation, telemetry, ready handoff, POST/GET sequencing, cancellation/deadline cleanup, malformed/failed/network responses, unknown versions and legacy mapping. HTTP transport tests cover bounded bodies and redirect handling. A device smoke should additionally check Back and switching sources while preparing, cached-source resume with expired original media authorization, both internal player engines, and unchanged playback of an addon without the hint.

This is an opt-in public-contract proposal. Upstream submission requires prior maintainer approval under CONTRIBUTING.md; a private-fork test build is not an upstream approval or release.

## Test-build account configuration

A source build does not automatically contain the official account backend configuration.
The login website URL alone is insufficient to generate a QR session: a valid
backend URL and public client authentication key are also required. These values
must be supplied through local build configuration or an explicitly selected
supported custom server. Never embed a private service-role key.

An unconfigured build now stops the QR loading state and displays a configuration
error, including during initial account loading. The fork's replacement test
artifact uses a non-debuggable optimized release build and retains the separate
test application's package and signing certificate for installation as an update.
Its public client configuration is validated before packaging; local configuration
and signing material remain outside Git. APK compilation and protocol tests alone
do not prove account initialization or physical-device performance.
