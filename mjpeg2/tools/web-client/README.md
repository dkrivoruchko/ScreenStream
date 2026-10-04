# MJPEG web client

Edit the HTML, CSS and JavaScript in `src/`. The JavaScript files separate interface text, connections, media,
presentation and page coordination. `build.cjs` writes the generated `mjpeg2/src/main/assets/mjpeg/index.html`;
do not edit that output directly.

Use **Node.js 24.21.0 and npm 12.2.0**, pinned in `.nvmrc` and `package.json`. From the repository root:

```sh
cd mjpeg2/tools/web-client
```

On first setup or after dependency changes, install from the lock file:

```sh
npm ci --include=dev
```

After editing sources, build the page and check that the output matches:

```sh
npm run build
npm run check
```

The builder combines the sources into one HTML file, transpiles JavaScript with Babel and checks ES5 syntax with Acorn.
Its browser targets are Chrome 49, Firefox 45, Safari 9.1 and iOS 9.3; syntax checks do not verify browser APIs.
Keep exactly one `/* INLINE_STYLE */` and one `/* INLINE_SCRIPT */` marker in the HTML template.
`npm run check` compares the generated file without writing it.

Commit the authored sources and regenerated HTML together. Android builds package the static HTML and require no
Node/npm step; `node_modules` is ignored and stays out of the APK.
