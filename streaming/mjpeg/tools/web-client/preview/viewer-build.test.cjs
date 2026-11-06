const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { EventEmitter } = require('node:events');
const { setTimeout: delay } = require('node:timers/promises');
const { test: nodeTest } = require('node:test');
const { inputPaths } = require('../build.cjs');
const { createViewerBuild } = require('./viewer-build.cjs');
const test = (name, body) => nodeTest(name, { timeout: 10000 }, body);

async function waitForCondition(predicate, message, timeout = 2000) {
    const deadline = Date.now() + timeout;
    while (!predicate()) {
        if (Date.now() >= deadline) assert.fail(message);
        await delay(5);
    }
}

test('viewer source and logo edits rebuild, retain the last good build and stop polling on close', async t => {
    const originalRead = fs.readFileSync;
    const sourcePath = inputPaths[0], logoPath = inputPaths.at(-1);
    let sourceVersion = 0, logoVersion = 0, invalidSource = false, inputReads = 0;
    const watchers = [];
    t.mock.method(fs, 'readFileSync', (input, ...args) => {
        if (inputPaths.includes(input)) inputReads += 1;
        const contents = originalRead(input, ...args);
        if (input === sourcePath) return contents + (invalidSource ? '\nfunction (' : '\n// watcher source ' + sourceVersion);
        if (input === logoPath) return Buffer.concat([contents, Buffer.from([logoVersion])]);
        return contents;
    });
    t.mock.method(fs, 'watch', (directory, changed) => {
        const watcher = new EventEmitter();
        watcher.close = () => { watcher.closed = true; };
        watchers.push({ directory, changed, watcher });
        return watcher;
    });
    const viewerBuild = createViewerBuild({ watch: true, log: () => {} });
    t.after(() => viewerBuild.close());
    sourceVersion = 1;
    viewerBuild.start();
    await waitForCondition(() => viewerBuild.state().revision === 2, 'An edit before watcher startup was missed');
    assert.equal(viewerBuild.state().watchMode, 'native');

    logoVersion = 1;
    const logoWatcher = watchers.find(item => item.directory === path.dirname(logoPath));
    assert.ok(logoWatcher, 'The packaged logo input is not watched');
    logoWatcher.changed('change', path.basename(logoPath));
    await waitForCondition(() => viewerBuild.state().revision === 3, 'A logo edit did not rebuild the viewer');
    const lastGoodHtml = viewerBuild.html;

    logoWatcher.watcher.emit('error', Object.assign(new Error('Native watch unavailable'), { code: 'EMFILE' }));
    assert.equal(viewerBuild.state().watchMode, 'polling');
    assert.match(viewerBuild.state().watchWarning, /EMFILE/);
    assert.ok(watchers.every(item => item.watcher.closed));
    invalidSource = true;
    await waitForCondition(() => viewerBuild.state().error !== null, 'Polling did not report a failed build');
    assert.equal(viewerBuild.html, lastGoodHtml);
    assert.equal(viewerBuild.state().revision, 3);

    invalidSource = false;
    sourceVersion = 2;
    await waitForCondition(() => viewerBuild.state().revision === 4, 'Polling did not recover after source repair');
    assert.equal(viewerBuild.state().error, null);
    viewerBuild.close();
    const readsAtClose = inputReads;
    sourceVersion = 3;
    await delay(1200);
    assert.equal(inputReads, readsAtClose, 'Closed build continued reading source inputs');
    assert.equal(viewerBuild.state().revision, 4);
});
