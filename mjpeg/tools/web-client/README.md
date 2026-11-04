# MJPEG web client

This workspace builds the browser viewer that Android serves and runs a desktop preview for working on it. [Web-client internals](../../internal/02-delivery/03-web-client.md) explain the page's state ownership and recovery rules. The [HTTP server guide](../../docs/server.md) owns the protocol the page consumes.

## Sources and generated asset

Edit the HTML template, CSS, JavaScript and locale catalogs in [`src/`](src). [build.cjs](build.cjs) combines them with the packaged logo into one self-contained Android asset, [`mjpeg/src/main/assets/mjpeg/index.html`](../../src/main/assets/mjpeg/index.html). Don't edit that file by hand; commit it together with the sources that produced it.

The builder checks every locale catalog against English and requires each template marker exactly once. It wraps the scripts, in the order it lists, in one strict-mode function, escapes the inline script, and checks that the final script parses as ES2015.

## Commands

Use the Node.js release in [.nvmrc](.nvmrc). [package.json](package.json) declares the supported Node.js and npm ranges and the preferred npm release. From the repository root:

```sh
cd mjpeg/tools/web-client
npm ci          # install the locked dependencies
npm run build   # regenerate the Android asset
npm run check   # fail if the asset is stale; writes nothing
npm test        # preview and build fixtures, viewer owner tests
```

`npm test` runs two Node suites. The preview fixtures exercise the preview harness and the builder. The [owner tests](test) load the authored application, connection and media scripts from `src/` and drive them with mock timers and small doubles for browser objects and neighboring owners. They execute those owners' logic, but they don't run the packaged page or its `page.js` wiring in a browser, so image decoding, native timer scheduling, cookies, fullscreen, picture in picture, layout and focus still need the [browser checks](#browser-checks). The Kotlin suite has its own [run instructions](../../internal/03-testing/01-testing.md#running-local-tests).

## Preview

`npm start` serves the preview panel at `http://127.0.0.1:8765/`, and **Open viewer** opens the packaged page at `/viewer`. To choose another port and stop watching sources, use `npm start -- --port 8877 --no-watch`.

While watching, source changes rebuild the page in memory, not the Android asset. Reload the viewer to apply a rebuild; a failed rebuild keeps the last good page. Restart the server after editing `build.cjs`.

All tabs share one virtual device, with its settings and credentials. Loading the panel page resets that device to its defaults for every tab and disconnects open viewers. The preview doesn't reproduce Android capture, Ktor or CIO, or real network permissions.

## Browser checks

After changing viewer behavior, check it by hand in a current browser, using the standalone `/viewer`; the panel's frame reloads when access changes. Cover MJPEG and JPEG; PIN entry, a block, and revocation when the panel switches access scenario; and reconnection after restarting the server on the same port. For an access link, select PIN required and open `/viewer?token=…` with `delivery.credentials.token` from `/__preview/api/state`. The page should land on `/viewer` without the token, admitted by its cookie. Also try the sizing modes, keyboard use, and fullscreen or Picture in picture where the browser offers them.

## Browser compatibility

The viewer targets the 2019–2020 browser generation: Chrome 81, Firefox 68, and Safari 12.1 or iOS 12.2. This target comes from source review and compatibility data, not from runs in those browsers, and older browsers aren't a goal. The ES2015 parse proves syntax only. New code should stay within the target or detect the feature first.
