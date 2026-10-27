[Usage](usage.md) · [Architecture](architecture.md) · HTTP viewing API

# HTTP viewing API

MJPEG exposes direct HTTP viewing and a read-only WebSocket state connection on each selected listening device address. All listeners share image selection and applied access settings. This is a viewing protocol: it cannot start or stop Android capture, change settings or provide audio.

Use the supplied device URL with its current port. Opening the HTML page, authorization, image availability and successful payload delivery are separate results. The packaged page handles those steps; a custom viewer must handle them explicitly.

## Connect a viewer

1. Open `/` for the packaged browser page, or select `/jpeg` or `/mjpeg` for a media client.
2. With protection enabled, exchange a copied link's `accessToken` at `/` for the viewing cookie, or submit the PIN. Media clients can instead supply the current token directly on their media request.
3. Browser/state clients open `/ws` with the matching Origin and a current cookie when protected. Apply each complete snapshot, including authorization, `notice`, `hasImage` and `imageKind`; do not treat it as one event per captured frame.
4. Reauthorize after protection or an enabled PIN changes. A connection can end before it receives another state, and reconnecting does not start capture.

## Routes and response headers

These results assume a trusted current listener; PIN and WebSocket also require the Origin checks below. The common authority/listener check can reject a request with 403 before its route runs.

| Request | Current result |
| --- | --- |
| `GET /` | One packaged HTML page; protected viewing is established separately through `/ws` |
| `GET /?accessToken=…` | Always `303` to `/`; valid protected token also sets the existing viewing cookie |
| `POST /pin` | Same-origin check, bounded form parsing, then `204` + cookie on success; `403` on wrong PIN; `429` + `Retry-After` while blocked |
| `GET /jpeg` | One JPEG, authorized by open access, token or cookie; `403` if denied; `503` + `Retry-After: 1` if unavailable |
| `GET /mjpeg` | Multipart stream with the same authorization/unavailability policy |
| `GET /ws` upgrade | Read-only full state snapshots, authorized by open access or cookie; token is not accepted directly |

The common interceptor adds `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`. `Referrer-Policy` is `no-referrer` whenever the query contains an `accessToken` parameter, otherwise `same-origin`. It rejects untrusted authority or an inactive exact listener with `403` before normal route work. Unknown paths then follow Ktor routing behavior; there is no separate asset-route family because CSS/JavaScript are embedded in HTML.

`GET /` returns the cached packaged HTML bytes without configured display settings, image state or credentials. Access screens and states without a displayed image use a neutral system-theme background. An authorized snapshot supplies `display.background`, which fills the viewing canvas and separate bars when an image is displayed. Standalone HTML uses the same neutral access-screen fallback.

Presence of the token parameter, including an invalid or empty value, triggers the root redirect. An invalid token grants no new access and does not erase a valid existing cookie. Media auth is independent of loading the page; logo and captured frames use the same endpoints. [JPEG delivery](../internal/03-delivery/jpeg-delivery.md#writing-jpeg-and-mjpeg) owns content types, lengths and multipart framing.

## Request authority, origin and credentials

Use a numeric device IP and listening port as the request authority. Exactly one `Host` header must match that listener; `localhost` is accepted only for a loopback listener. IPv6 URLs use brackets. Arbitrary DNS aliases, reverse-proxy authority and forwarded host/scheme headers are not accepted as substitutes for the direct address. A reachable socket can therefore still return 403 for an unsupported Host.

`POST /pin` requires a matching HTTP Origin. When Origin is absent, a matching Referer can supply it, including its path/query. `/ws` requires Origin and has no Referer fallback. Match the actual scheme, host spelling and effective port; equivalent IPv6 bytes with different textual spellings are not automatically the same Origin. Media authorization does not require this PIN/WebSocket Origin check. Origin alone is never a viewing credential.

The viewing cookie is `screenstream_access=<hex>; Path=/; HttpOnly; SameSite=Lax`, without Domain, expiry or Max-Age. It is host-only and session-lived by browser policy; switching to a different device IP does not automatically carry that host's cookie. Use a current protected link or PIN on that address. Cookie authorization still depends on the current in-memory delivery credentials; retaining a browser cookie does not preserve access across a new delivery lifetime. There is no Secure attribute because the listeners use ordinary HTTP. HTTP exposes content and credentials to the network.

Correct PIN login and valid token exchange return the existing cookie without rotating other tabs' credentials. The token and cookie independently authorize media; the token does not directly authorize `/ws`. These are delivery-wide credentials, not a per-page session identifier. Changing protection or an enabled PIN revokes old next-frame and WebSocket authority. A JPEG payload already acquired by a response can finish. Wrong-PIN blocks apply to the immediate peer IP across listeners, without invalidating already valid viewing credentials; see [Usage](usage.md#urls-and-viewing).

## PIN submission

Send `POST /pin` with `Content-Type: application/x-www-form-urlencoded`, exactly one `pin` field and six ASCII digits, retaining leading zeros. Keep the encoded body within 256 bytes. Unsupported or malformed input can count as a wrong PIN. A successful protected request returns 204 with the current cookie; open access returns 204 without a protected cookie.

For 429, `Retry-After` is the remaining block duration rounded upward to seconds. A correct PIN remains blocked until expiry, and requests do not extend that block. Body-read failures or timeout are transport failures without a dedicated response-status guarantee. The internal [PIN parser](../internal/03-delivery/web-client.md#pin-body-parser) defines exact parsing, accounting and read limits.

Wrong-PIN history is limited to 1024 peer IPs. Partial counters expire after 15 minutes without another wrong PIN; PIN verification and denied WebSocket admission remove expired counters and blocks. If all records are still live, a new IP receives 429 before PIN comparison, even for a correct PIN, with `Retry-After` until the earliest record expiry. A denied `/ws` admission reports the same capacity wait as `blocked`. Known unblocked peers can still authorize, and existing viewing cookies or tokens continue working. Repeated requests from a waiting new IP do not refresh stored deadlines.

## Consume images

`/jpeg` returns `image/jpeg` with the pinned image's Content-Length. `/mjpeg` returns `multipart/x-mixed-replace; boundary=screenstream-jpeg`; parts carry their own JPEG Content-Type and Content-Length. Read each payload by its declared length rather than assuming network reads align with images. A response can terminate at cutoff or failure without a final closing multipart boundary.

An authorized new media request can receive 503 and `Retry-After: 1` when image or runtime admission is unavailable. An already admitted multipart reader can wait for another image. Slow readers skip intermediate images; unchanged selected bytes can repeat after one second without another capture. All clients use the same media selection and endpoints: without a current capture frame, App logo selects the logo, and Last frame selects one retained real frame or logo if absent. That real frame survives fallback edits and capture attempts until replacement or delivery disposal. Authorization is always checked before image availability. Neither a successful response nor repeated bytes proves a newly captured screen or browser repaint. Exact framing, lease and write-accounting rules are in [JPEG delivery](../internal/03-delivery/jpeg-delivery.md).

The packaged page keeps a previously loaded image while a replacement request fails or times out. It clears an image that reports an error or loses its intrinsic dimensions, then recovers the selected method. This is browser image availability, not per-frame tracking or a saved copy of the last MJPEG frame. Image events do not expose HTTP status or a precise failure cause; an open WebSocket does not prove that media continues to arrive. See [browser recovery](../internal/03-delivery/web-client.md#browser-owners-and-recovery) for its retry and platform limits.

## WebSocket state

An accepted `/ws` connection receives a complete authorized snapshot, immediate changed snapshots and a repeated current snapshot after two seconds without a change. Repeated snapshots provide connection liveness, not image freshness. This is latest state: intermediate updates can conflate, unchanged image availability emits no per-frame message, and HTTP counters are absent. Credential generation, PINs, tokens, cookies and internal capture diagnostics are omitted.

| Authorized snapshot field | Wire values |
| --- | --- |
| `access` | `open` or `authorized` |
| `notice` | Required null, or `permission_required`, `stopped`, `paused`, `failed` |
| `hasImage` | Required Boolean, derived as `imageKind != none` |
| `imageKind` | Required `none`, `logo`, `live` (current producer frame), or `retained` (previous producer frame) |
| `display.background` | Shared RGB text |
| `display.title`, `display.titleEnabled` | Shared text and Boolean |
| `display.titleLayout`, `display.controlsLayout` | `position: top/bottom`, `presentation: overlay/bar`, `autoHide: Boolean` |

A notice describes capture availability: `permission_required` means consent is pending, `stopped` means Stop was accepted or capture ended, `paused` means suspended capture and `failed` means the capture attempt failed, including a retained cleanup failure. Other capture phases send null. Notice and image availability are independent; a stopped notice can accompany a retained image. A null notice does not prove that a fresh image exists or has been displayed. The packaged page uses a centered DOM logo for selected `logo` and requests no logo media. It loads real media for `live`/`retained`, including retained frames while permission is pending. Initial connection and connection loss without a usable displayed image keep the generic connection logo; access branding is separate. One current browser message describes connection recovery, eligible media recovery or the current capture notice, in that order; a displayed retained image with no notice needs no waiting message. Losing the state connection replaces old capture text without removing a usable image. Media and state arrive independently, so a transition can briefly display an image before its matching state; there is no separate browser media flow or atomic state/image pairing.

Denied admission sends only `{ "access": "pin_required" }`, or `{ "access": "blocked", "retryAfterMs": <remaining> }`, and closes normally. Denial does not require display, notice or image fields. A valid cookie still authorizes during a peer PIN block. Listener/access revocation can cancel transport without delivering a denial snapshot.

For example, an open stream with a selected image can send:

```json
{
  "access": "open",
  "notice": null,
  "hasImage": true,
  "imageKind": "live",
  "display": {
    "background": "#101418",
    "title": "Android device",
    "titleEnabled": true,
    "titleLayout": { "position": "top", "presentation": "overlay", "autoHide": true },
    "controlsLayout": { "position": "bottom", "presentation": "overlay", "autoHide": true }
  }
}
```

Handle denied `access` values before expecting authorized fields. The packaged browser requires all authorized fields and known image-kind/notice/layout values and consistent image availability; extra fields are not version negotiation. The packaged page and server use the same unversioned schema; reload an already open viewer after an app update changes that schema. The display settings are shared across viewers; sizing mode, JPEG/MJPEG choice and information-panel visibility are page-local choices described in [Usage](usage.md#browser-controls).

The connection is read-only. Application text/binary messages close it with policy violation; normal WebSocket control frames remain transport traffic. Incoming application frames are limited to 4096 bytes and outgoing encoded JSON snapshots to 256 KiB. Ping/timeout and send/close budgets do not establish a bound on total transport memory or physical cleanup. [Web client internals](../internal/03-delivery/web-client.md#websocket-snapshots-and-transport) extend this contract with transport and browser resource ownership.

[WebRoutes](../src/main/kotlin/io/screenstream/mjpeg/http/web/WebRoutes.kt) and [WebState](../src/main/kotlin/io/screenstream/mjpeg/http/web/WebState.kt) implement this protocol. Android host control uses the separate [manager APIs](usage.md#select-and-control-the-module).
