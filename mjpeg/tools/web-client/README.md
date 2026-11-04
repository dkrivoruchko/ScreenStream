# MJPEG web client

This workspace builds the packaged browser viewer and provides a desktop preview for development. Android serves the generated HTML; the preview composes the same viewer with a virtual device and a separate HTTP/WebSocket server. Read [web-client internals](../../internal/02-delivery/03-web-client.md) for production ownership and recovery contracts, and the [HTTP guide](../../docs/server.md) for the protocol it consumes.

## Development workflow

Use the Node.js release named in [.nvmrc](.nvmrc) and the npm release that [package.json](package.json) declares in `engines` and `packageManager`. Those files own the toolchain versions, and `npm ci` installs exactly what the [lockfile](package-lock.json) records. From the repository root:

```sh
cd mjpeg/tools/web-client
npm ci
npm start
```

Open `http://127.0.0.1:8765/` for the preview panel. **Open viewer** opens the packaged page at `/viewer`. Ctrl+C stops the server. To choose another port and disable source watching, use `npm start -- --port 8877 --no-watch`.

After editing the authored inputs, run the preview checks, regenerate the Android asset and compare it with a fresh build:

```sh
npm test
npm run build
npm run check
```

`npm test` targets the preview/build fixtures, while the Kotlin suite has its own [run instructions](../../internal/03-testing/01-testing.md#running-local-tests) and [evidence requirements](../../internal/03-testing/01-testing.md#evidence-rules).

## Sources and generated output

Edit production HTML, CSS, JavaScript and locale inputs in [`src/`](src). [build.cjs](build.cjs) combines them with the packaged logo into [`mjpeg/src/main/assets/mjpeg/index.html`](../../src/main/assets/mjpeg/index.html). Commit authored inputs and regenerated HTML together. The Android package consumes the checked-in asset directly.

The builder requires exactly one `/* INLINE_STYLE */`, `/* INLINE_SCRIPT */` and `/* INLINE_LOGO */` template marker. It supplies the ordered script module list, inlines CSS/logo and escapes embedded script content. Appearance reaches the viewer through authorized state, so the build contains no per-device settings or credentials.

Babel targets Chrome 49, Firefox 45, Safari 9.1 and iOS 9.3; Acorn verifies ES5 syntax. These are syntax checks. Browser APIs, policy, layout and rendering require separate qualification. `npm run check` compares the generated asset with the current build and writes no output.

## Preview ownership and reloads

[preview/server.cjs](preview/server.cjs) owns desktop HTTP/WebSocket transport. [device.cjs](preview/device.cjs) derives canonical visual presets from [scenarios.cjs](preview/scenarios.cjs), and [panel.js](preview/panel.js) controls that shared virtual device. Eight predefined JPEG frames per orientation animate a spinner at 4 fps; `logo.jpg` stays static. Preview runtime reads those fixtures directly.

[viewer-build.cjs](preview/viewer-build.cjs) builds HTML in memory and watches authored inputs plus the packaged logo. Input changes are debounced for 100 ms; native-watch failure switches to one-second polling. A failed rebuild retains the last good viewer and records the error in Diagnostics. A successful rebuild needs an explicit viewer reload. Restart the server after changing `build.cjs`, including its script module list.

The server defaults to loopback. All tabs share one virtual device, settings and credentials; cookies are host-scoped rather than port-scoped. Source watching and full panel reload have different effects: watching prepares new HTML, while panel reload restores the canonical default device.

## Preview controls

1. Eight states: Normal, PIN required, PIN blocked, Starting, Screen-sharing permission, Paused, Stopped and Failed. Capture changes apply live, preserving connections and viewer choices. PIN/Blocked deliberately start a fresh protected preview context; leaving them restores open access. The viewer reloads at these access boundaries. The test PIN is shown; blocks last 20 seconds.
2. **Inactive stream image** selects App logo (default) or Last frame whenever no current frame is available; a current frame always wins. **Current frame available** controls Normal, PIN required and PIN blocked; when off, those canonical fixtures have no earlier frame. Other scenarios use **Last frame available**: when on, Last frame selects one frozen sample in the chosen stream orientation; when off, it falls back to Logo. Each scenario is independent of earlier choices; the preview does not simulate Android capture history. Diagnostics reports the selected `imageKind`: `none`, `logo`, `live` or `retained`. During permission, the web viewer uses the same retained selection as direct media. A selected logo uses the centered DOM logo without a media request. Notices use the current message surface, which connection or media recovery can replace.
3. **Stream image: Portrait / Landscape** chooses the animated sample orientation. **Browser frame** independently changes the browser frame size. Changing stream orientation also updates the Last frame sample.
4. Appearance saves automatically: title and valid HEX wait 450 ms after typing; other controls apply immediately. Incomplete HEX stays local. Clean controls follow shared server settings; unsaved edits stay local. RGB surrounds a visible image and its bars; access and no-image screens use the system theme.
5. Full panel reload restores all defaults, including Logo, available image, portrait and viewport. **Reload viewer** preserves preview choices. Source watching rebuilds HTML in memory; reload the viewer when ready to apply it. Collapsed read-only **Diagnostics** shows the current build and connection facts.
6. **Simulate image load error** returns 503 for authorized media requests and closes active MJPEG responses while state still reports the selected image. Selecting any scenario or reloading the full panel clears this fault. Capture failed uses the same inactive image policy; the fault checkbox remains independent. Closing an already displayed MJPEG response does not guarantee a browser error event or removal of its image.

The existing [preview fixture suite](preview/fixture.test.cjs) uses isolated ephemeral servers to exercise routes, credentials, canonical transitions, multipart framing, reset and cleanup. Its build-lifecycle fixture exercises watched inputs, fallback polling and last-good output. These fixtures exercise the harness and builder; browser smoke below exercises viewer JavaScript and rendering. Browser-frame dimensions select a viewport size, not an emulated operating system.

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

The preview's `/viewer` consumes the [HTTP server and viewing protocol](../../docs/server.md) through the harness implementation. Record browser/version and observed results for smoke checks. Actual Android capture, Ktor/CIO, LAN permission enforcement and incoming reachability require their own Kotlin/device evidence.
