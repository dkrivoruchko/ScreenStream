# MJPEG web client

Use Node.js **24.21.0** and npm **12.2.0**, pinned in `.nvmrc` and `package.json`. From the repository root:

```sh
cd mjpeg/tools/web-client
npm ci
npm start
```

Open `http://127.0.0.1:8765/` for the desktop preview panel. **Open viewer** opens the actual packaged page at
`/viewer`. Ctrl+C stops the server. Optional: `npm start -- --port 8877 --no-watch`.

Run the checks and regenerate the Android asset after editing `src/`:

```sh
npm test
npm run build
npm run check
```

1. `src/` contains the production HTML, CSS and JavaScript. Edit these files, not the generated
   `mjpeg/src/main/assets/mjpeg/index.html`. Commit authored sources and regenerated HTML together.
2. `build.cjs` supplies the shared HTML builder. Babel targets Chrome 49, Firefox 45, Safari 9.1 and iOS 9.3;
   Acorn verifies ES5 syntax. Keep exactly one `/* INLINE_STYLE */`, `/* INLINE_SCRIPT */`,
   `/* INLINE_LOGO */` template marker. The viewer receives appearance through its authorized state connection.
   `check` compares the generated asset without writing it. Syntax targets do not prove runtime API support.
3. `preview/server.cjs` owns desktop HTTP/WS and `viewer-build.cjs` owns in-memory builds and source watching.
   `device.cjs` derives canonical visual presets from the chosen scenario and options; `scenarios.cjs` declares
   their catalog and defaults. `panel.js` controls the preview. Eight predefined JPEG frames per orientation animate a small spinner at
   4 fps; `logo.jpg` remains static. No image-processing dependency is needed at runtime. Android packages
   only the generated HTML and requires no Node/npm build step. Watching covers the authored sources and
   packaged logo, with one-second polling when native watching is unavailable. Restart the preview server
   after changing `build.cjs`, including its JavaScript module list.

## Preview controls

1. Eight states: Normal, PIN required, PIN blocked, Starting, Screen-sharing permission, Paused, Stopped and Failed. Capture
   changes apply live, preserving connections and viewer choices. PIN/Blocked deliberately start a fresh
   protected preview context; leaving them restores open access. The viewer reloads at these access boundaries.
   The test PIN is shown; blocks last 20 seconds.
2. **Inactive stream image** selects App logo (default) or Last frame whenever no current frame is available.
   A current frame always wins. **Current frame available** controls Normal, PIN required and PIN blocked;
   when off, those canonical fixtures have no earlier frame. Other scenarios use **Last frame available**:
   Last frame selects one frozen sample in the chosen stream orientation, falling back to Logo when off.
   Each scenario is independent of earlier choices; the preview does not simulate
   Android capture history. Diagnostics reports the selected `imageKind`: `none`, `logo`, `live` or `retained`.
   The web viewer uses the same retained selection during permission as direct media. Selected logo uses
   the centered DOM logo without a media request; notices use the current message surface, which connection
   or media recovery can replace.
3. **Stream image: Portrait / Landscape** chooses the animated sample orientation. **Browser frame** independently changes the
   browser frame size. Changing stream orientation also updates the Last frame sample.
4. Appearance saves automatically: title and valid HEX wait 450 ms after typing; other controls apply
   immediately. Incomplete HEX stays local. Clean controls follow shared server settings; unsaved edits stay
   local. RGB surrounds a visible image and its bars; access and no-image screens use the system theme.
5. Full panel reload restores all defaults, including Logo, available image, portrait and viewport.
   **Reload viewer** preserves preview choices. Source watching rebuilds HTML in memory; reload the viewer
   when ready to apply it. Collapsed read-only **Diagnostics** shows the current build and connection facts.
6. **Simulate image load error** returns 503 for authorized media requests and closes active MJPEG responses while
   state still reports the selected image. Selecting any scenario or reloading the full panel clears this fault.
   Capture failed uses the same inactive image policy; the fault checkbox remains independent. Closing an already
   displayed MJPEG response does not guarantee a browser error event or removal of its image.

The server defaults to loopback. All tabs share one virtual device and its settings/credentials; cookies are
host-scoped rather than port-scoped. Eight HTTP/WS checks use isolated ephemeral servers and cover the
routes, credentials, live transitions, canonical presets, multipart framing, reset and cleanup. One build
lifecycle check covers watched inputs, fallback polling and last-good output. They do not run
viewer JavaScript or check browser rendering. Frame sizes do not emulate iOS.

## Browser smoke

Run these four checks in Chrome and Safari after changing viewer behavior; no browser test framework is needed.

1. In Normal, check MJPEG and JPEG, both stream orientations, each sizing mode and live appearance changes.
2. Select JPEG and wait for **Viewing** with a visible image, then enable **Simulate image load error**.
   Check the media retry/error state while the previous successfully loaded JPEG remains visible.
   Disable the fault and use **Retry connection**
   (or reload and reselect JPEG); expect viewing to recover.
3. Use **Open viewer** and choose JPEG plus a nondefault sizing mode. In the panel, switch Normal → PIN required;
   enter the shown PIN in the standalone viewer and check both choices survive. The panel's iframe reloads
   at access boundaries, so use the standalone viewer for this check.
4. Keep that standalone viewer open. Stop the server until **Reconnecting**, then restart on the same port.
   Check that the compact message follows the visible HUD, viewing recovers automatically and local choices survive.
   The preview repeats authorized state snapshots every two seconds, matching the production liveness contract.
   A full panel reload resets defaults.

The desktop harness follows the [HTTP server and viewing protocol](../../docs/http-server.md), serving its viewer at
`/viewer`. It does not run Android capture or Ktor/CIO and does not replace Kotlin protocol tests or device
checks. See [web client internals](../../internal/03-delivery/web-client.md) for implementation and recovery details.
