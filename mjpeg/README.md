# MJPEG mode

**Local mode · MJPEG** shares an Android screen or app window as JPEG images. The app runs an HTTP server on the streaming device, and viewers open its address in a browser or another image client. The stream contains images only; the devices need a reachable network connection, and viewing works offline.

## Start viewing

1. Select **Local mode · MJPEG** in the app. Connect the viewing device to a network that reaches the streaming device. Complete any **Request permission** or **Open settings** action shown under Stream addresses.
2. Press **Start stream** when available and complete Android's screen-sharing consent.
3. Use **Copy URL** on a listening address and open the link in the viewer's browser. Open, Share and QR use the page link; the Copy dropdown also offers direct MJPEG and JPEG links.

With PIN protection enabled, a copied access link authorizes its recipient directly. Treat that link like a password and share it with intended viewers. HTTP exposes screen images and viewing credentials to the network, so use a trusted network.

## Capabilities

- **Viewing**
  - MJPEG or JPEG selection in the browser.
  - Image sizing controls.
  - Automatic connection recovery.
  - Fullscreen and picture in picture where the browser supports them.
- **Image controls**
  - Capture region selection and cropping.
  - Image rotation and mirroring.
  - Image size, color and JPEG quality settings.
  - Frame-rate limits and capture intervals.
  - Live updates for most image settings.
- **Access and appearance**
  - Optional six-digit PIN protection.
  - Access links for direct viewing.
  - Automatic wrong-PIN blocking.
  - Custom stream name, background and title/control placement.
- **Monitoring and automation**
  - Approximate production FPS, JPEG payload traffic and media client statistics.
  - Screen-awake requests.
  - Optional capture Stop when the screen turns off.
  - Capture Stop after sustained loss of all selected addresses.
  - Slow-reader warnings.

Viewers share produced JPEG bytes and receive separate HTTP transfers. Additional viewers increase outgoing data use; slow MJPEG readers advance to the latest image and skip intermediate publications.

## Guides

| Guide | Purpose |
| --- | --- |
| [Usage](docs/usage.md) | Start, share, protect and configure a stream; interpret statistics and recover from problems. |
| [Architecture](docs/architecture.md) | Understand the Android app, capture, HTTP and viewer responsibilities and lifetimes. |
| [HTTP server and viewing protocol](docs/http-server.md) | Connect a client using the routes, authorization, media format and browser-state protocol. |

## Practical limits

The server uses direct HTTP connections to numeric device addresses. Viewer reachability depends on routing, network isolation and permissions; loopback addresses reach only the streaming device. Frame rate is best effort, and server statistics measure production and payload writes. Peer receipt and browser rendering require observation at the viewer.
