# MJPEG web client

This workspace builds the browser viewer that Android serves and runs a desktop preview for working on it. [Web-client internals](../../internal/02-delivery/03-web-client.md) explain the page's state ownership and recovery rules. The [HTTP server guide](../../docs/server.md) owns the protocol the page consumes.

## Sources and generated asset

Edit the HTML template, CSS, JavaScript and locale catalogs in [`src/`](src). [build.cjs](build.cjs) combines them with the packaged logo into one self-contained Android asset, [`streaming/mjpeg/src/main/assets/mjpeg/index.html`](../../src/main/assets/mjpeg/index.html). Don't edit that file by hand; commit it together with the sources that produced it.

The builder checks every locale catalog against English and requires each template marker exactly once. It wraps the scripts, in the order it lists, in one strict-mode function, escapes the inline script, and checks that the final script parses as ES2015.

## Commands

Use Node.js and npm meeting the minimum versions in [package.json](package.json). From the repository root:

```sh
cd streaming/mjpeg/tools/web-client
npm ci          # install the locked dependencies
npm run build   # regenerate the Android asset
npm run check   # fail if the asset is stale; writes nothing
npm test        # preview and build fixtures, viewer owner tests
```

`npm test` runs two Node suites. The preview fixtures exercise the preview harness and the builder. The [owner tests](test) load the authored access, application, connection, media and picture-in-picture scripts from `src/` into a Node `vm` context and drive them with mock timers and small doubles for browser objects and neighboring owners. They don't run the packaged page or its `page.js` wiring in a browser, so image decoding and MJPEG rendering, native timer scheduling, cookies, fullscreen, native picture-in-picture entry and user activation, layout, focus, screen readers and reduced motion still need the [browser checks](#browser-checks). The Kotlin suite has its own [run instructions](../../internal/03-testing/01-testing.md#running-local-tests).

## Preview

`npm start` serves the preview panel at `http://127.0.0.1:8765/`, and **Open viewer** opens a page built in memory from the current sources at `/viewer`. To choose another port and stop watching sources, use `npm start -- --port 8877 --no-watch`.

While watching, source changes rebuild the page in memory, not the Android asset. Reload the viewer to apply a rebuild; a failed rebuild keeps the last good page. Restart the server after editing `build.cjs`.

All tabs share one virtual device, with its settings and credentials. Loading the panel page resets that device to its defaults for every tab and disconnects open viewers. The preview doesn't reproduce Android capture, Ktor or CIO, or real network permissions.

## Browser checks

After changing viewer behavior, use the standalone `/viewer` in a current browser. The panel's embedded viewer reloads when access changes.

1. Try MJPEG, JPEG, the sizing modes, keyboard navigation, and fullscreen where available. Restart the preview server on the same port and check that the viewer reconnects.
2. In **PIN required**, submit an incomplete PIN and then six wrong digits. Check that the field receives focus and the error is announced with a screen reader. The wrong PIN should be selected. Paste the correct PIN in localized digits, such as `١٢٣٤٥٦`, and check that it replaces the selection and clears the error. Submit it and confirm the viewer opens.
3. Switch to **Normal stream** and back to **PIN required**. Confirm the viewer asks for the PIN again. Submit wrong PINs by pressing Enter while the field is focused until a block starts. Keep the viewer focused and wait without further interaction. Check that the countdown is visible, the screen reader announces the block once and announces when entry is available again, and focus returns to the field.
4. For an access link, select **PIN required** and open `/viewer?token=…` with `delivery.credentials.token` from `/__preview/api/state`. Verify that the browser lands on `/viewer` with access through its cookie and the token disappears from the address bar.
5. In **Normal stream**, set **Inactive stream image** to **Last frame**. Open **Picture in picture** where available, then switch to **Stream stopped**. Check that the same floating window keeps displaying the retained image. Change the image orientation and confirm that the window shows the replacement frame. Select **App logo** and check that the floating window closes.

## Browser compatibility

The viewer targets the 2019–2020 browser generation: Chrome 81, Firefox 68, and Safari 12.1 or iOS 12.2. This target comes from source review and compatibility data, not from runs in those browsers, and older browsers aren't a goal. The ES2015 parse proves syntax only. New code should stay within the target or detect the feature first.
