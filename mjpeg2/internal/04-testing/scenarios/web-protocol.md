[Scenario index](../verification-contracts.md) · [Running tests](../testing.md)

# HTTP authority and WebSocket wire protocol

Behavior: [maintained contract](../../03-delivery/web-client.md). Explicit-title WebState JSON uses L0; HTTP routes and WebSockets use L1. Public PIN-body channels exercise read faults/timeouts, unsupported-type no-read behavior and UTF-8 byte bounds. Continuing CIO sockets establish changed facts, same-PIN preservation, cookie authorization, generation cutoff and outgoing encoded snapshot limits. Socket termination is checked with real CIO. Held send/close timeouts, snapshot/cutoff races, incoming-frame size enforcement, queue overflow and nonreading-peer completion remain unarranged. Android transport behavior needs L2.

## Current listener, Host and forwarded data

<a id="mjpeg-web-01"></a>

- **Common headers and current-listener authority.** Request root, PIN, media, unknown routes and WS upgrade on current/stale listeners with absent, present or empty accessToken parameters. All pipeline responses carry no-store and nosniff; Referrer-Policy is no-referrer when accessToken parameter exists, otherwise same-origin. Untrusted Host or inactive listener is Forbidden before route admission; revoked listener cannot continue authorizing new requests.

<a id="mjpeg-web-02"></a>

- **Trusted Host literal and localhost rules.** Current listener binds an IPv4/IPv6 literal with explicit port; Host variants include matching numeric address, loopback localhost, foreign literal/DNS, missing/duplicate Host, userinfo/path/query/fragment and wrong port. Send requests with each explicit Host fixture. Matching numeric listener address/port is trusted; localhost only aliases a loopback bind. Default http port is 80 when omitted. Arbitrary DNS/forwarded headers and malformed/multiple authority are not trusted. IPv6 listener scope is not copied into trusted portable Host authority.

<a id="mjpeg-web-14"></a>

- **Unknown routes and forwarded data do not broaden authority.** Unknown paths/methods or legitimate path with arbitrary Forwarded/X-Forwarded-Host/For headers are supplied. Send with trusted versus untrusted direct Host/Origin. Forwarded data never grants Host/origin trust or changes immediate PIN peer/endpoint accounting. Unknown route follows configured Ktor routing rather than opening media or control authority. No externally supplied message can start/stop capture through WS.

## PIN Origin and Referer

<a id="mjpeg-web-03"></a>

Trusted Host request has matching/mismatching Origin; missing Origin uses matching/mismatching Referer with allowed path/query; fragment/userinfo/https/port variants exist. POST /pin, including simultaneous Origin and Referer. Origin takes precedence; exact normalized host text/scheme/port must match request origin. Without Origin only PIN may use valid Referer with path/query. Foreign/null/malformed origin is Forbidden before verification; matching IP bytes alone does not equate distinct origin host spellings.

## Public page and token redirect

<a id="mjpeg-web-04"></a>

Protection is open/protected and root request has no token or valid/invalid/empty token parameter. GET / and repeat token exchanges. Root without parameter returns packaged HTML publicly. Any token parameter returns 303 Location /; only valid protected token adds current cookie. Cookie is Path=/, HttpOnly, SameSite=Lax; valid repeated exchange keeps cookie. No token/credentials enter authorized state JSON.

## Bounded PIN parsing and verification outcomes

<a id="mjpeg-web-05"></a>

- **PIN form shape and bounded body.** Same-origin current protected listener receives valid six ASCII digits, wrong PIN, duplicate/missing fields, malformed query data, unsupported content type, exact 256-byte and oversized body fixtures. POST each body through route. Valid form single PIN authorizes 204 and current cookie; other parser-null/wrong-PIN forms do not authorize and can consume limiter budget. Body reading is bounded at 257 bytes to detect >256; unrelated fields do not invalidate one valid pin; unsupported type returns parser-null without unbounded reads.

<a id="mjpeg-web-06"></a>

- **PIN read timeout/transport failure versus invalid verification.** Same-origin current request body stalls beyond five seconds or fails before parsing; limiting is enabled. Arrange body timeout/failure, then submit a known wrong sequence. Timeout/transport exception exits before verifyPin and does not consume wrong-attempt budget. Do not invent a stable response status for an unhandled read failure. Parser-null requests that reach protected current verification are a separate counted case; blocked requests do not add attempts.

<a id="mjpeg-web-07"></a>

- **PIN HTTP block headers and open response.** Protected peer has fifth wrong/block duration not divisible into supplied remaining seconds; open access is separate. POST same-origin PIN at explicit/current time with faithful clock arrangement. Blocked route returns 429 and ceil(remainingMs/1000) Retry-After; wrong returns 403; valid protected PIN returns 204+cookie; open verification returns 204 without fabricated cookie. Inactive listener is rejected by pipeline/current authority rather than consumes attempts.

## Media authorization and availability

<a id="mjpeg-web-08"></a>

Current listener has open/protected access and selected/no image; correct/incorrect token/cookie and stopped-policy cases are supplied. GET /jpeg and /mjpeg. Unauthorized current media returns 403; authorized no-image/unavailable admission returns 503 Retry-After 1. Authorized selected image returns appropriate media bytes even when capture is stopped with retained/placeholder image. Pipeline stale-listener denial remains 403, separate from delivery's unavailable result.

## WebSocket origin and cookie admission

<a id="mjpeg-web-09"></a>

Trusted current WS request has matching Origin and valid/invalid/missing cookie; valid accessToken query only and Referer-only variants exist. Upgrade /ws and inspect first state/close. WS requires Origin; Referer fallback is disallowed. Protected WS accepts viewing cookie, not token query directly. Missing authorization sends pin_required or blocked with retryAfterMs then normal close; invalid Origin uses policy-violation close. Open access needs no cookie.

## Authorized JSON schema

<a id="mjpeg-web-10"></a>

WebState explicit-title display fixtures and each capture status are supplied, including large/empty titles; current authorization generation exists. Titles include quotes, control characters and non-ASCII/Unicode text. encodeJson directly, then observe authorized WS snapshots. Wire contains access open/authorized, nullable notice, hasImage and display background/title/titleEnabled/layouts/retainImageOnMediaFailure. Layout uses top/bottom and overlay/bar. Notices: permission_required, stopped, paused, failed; Active/Starting/Reconfiguring/Stopping null. Generation and secrets stay off wire; saved compatibility name is not the wire field. Independent JSON decoding preserves title text/escapes without injecting extra fields or exposing diagnostics/secrets.

## Latest snapshots and generation cutoff

<a id="mjpeg-web-11"></a>

Authorized WS waits while display/image/capture changes occur; credentials rotate or listener disappears between snapshot selection and send. Arrange changed current facts and competing cutoff. WS delivers current complete authorized snapshots without requiring every intermediate update. Null/mismatched generation or no longer current listener terminates old collection; old credentials do not authorize successor snapshots. No leaked credentials or invented ordering across unrelated flows.

## Read-only data, control traffic and limits

<a id="mjpeg-web-12"></a>

- **Read-only WS and incoming-close termination.** Authorized WS is collecting state; client sends text/binary application data, protocol ping/pong or close, or disconnects while state is unchanged. Send application data separately from control-frame/transport histories. Application text/binary messages delivered by the default session violate read-only policy and terminate with policy violation. Ktor handles ping/pong/close control frames outside this incoming application channel; do not classify normal control traffic as a command. Incoming channel close/error ends the waiting collector. Default/raw transport cancellation and detached-session settlement need independent completion evidence, not merely absence of new snapshots.

<a id="mjpeg-web-13"></a>

- **WS frame, snapshot and send limits.** Outgoing JSON at/below/above 256 KiB is checked using Unicode and escaped titles to distinguish characters from encoded bytes. Incoming frames >4096 bytes, queue overflow and a nonreading peer are separate unarranged histories, as are held send/close operations. Configured incoming size/overflow policies close invalid transport traffic. Outgoing snapshots at or below the limit remain eligible; above-limit encoded Frame.Text data closes InternalError without sending a valid oversized snapshot. Count UTF-8 JSON bytes including escaping/field overhead, not title characters. Sends/close use five-second bounded operations; ping/timeout are configured 15 seconds. Eligibility does not prove successful network delivery or exact physical cleanup.

[Ktor default sessions](https://api.ktor.io/ktor-websockets/io.ktor.websocket/-default-web-socket-session/index.html) handle control frames outside incoming application data; [origin identity](https://www.rfc-editor.org/rfc/rfc6454.html) uses scheme, host and port.
