const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { EventEmitter } = require('node:events');
const { setTimeout: delay } = require('node:timers/promises');
const { test: nodeTest } = require('node:test');
const { inputPaths } = require('../build.cjs');
const { createViewerBuild } = require('./viewer-build.cjs');
const test = (name, body) => nodeTest(name, { timeout: 10000 }, body);

async function until(predicate, message, timeout = 2000) {
    const deadline = Date.now() + timeout;
    while (!predicate()) {
        if (Date.now() >= deadline) assert.fail(message);
        await delay(5);
    }
}

test('viewer source and logo edits rebuild, retain the last good build and stop polling on close', async t => {
    const originalRead = fs.readFileSync;
    const source = inputPaths[0], logo = inputPaths.at(-1);
    let sourceVersion = 0, logoVersion = 0, invalidSource = false, inputReads = 0;
    const watchers = [];
    t.mock.method(fs, 'readFileSync', (input, ...args) => {
        if (inputPaths.includes(input)) inputReads += 1;
        const contents = originalRead(input, ...args);
        if (input === source) return contents + (invalidSource ? '\nfunction (' : '\n// watcher source ' + sourceVersion);
        if (input === logo) return Buffer.concat([contents, Buffer.from([logoVersion])]);
        return contents;
    });
    t.mock.method(fs, 'watch', (directory, changed) => {
        const watcher = new EventEmitter();
        watcher.close = () => { watcher.closed = true; };
        watchers.push({ directory, changed, watcher });
        return watcher;
    });
    const build = createViewerBuild({ watch: true, log: () => {} });
    t.after(() => build.close());
    sourceVersion = 1;
    build.start();
    await until(() => build.state().revision === 2, 'An edit before watcher startup was missed');
    assert.equal(build.state().watchMode, 'native');

    logoVersion = 1;
    const logoWatcher = watchers.find(item => item.directory === path.dirname(logo));
    assert.ok(logoWatcher, 'The packaged logo input is not watched');
    logoWatcher.changed('change', path.basename(logo));
    await until(() => build.state().revision === 3, 'A logo edit did not rebuild the viewer');
    const lastGood = build.template;

    logoWatcher.watcher.emit('error', Object.assign(new Error('Native watch unavailable'), { code: 'EMFILE' }));
    assert.equal(build.state().watchMode, 'polling');
    assert.match(build.state().watchWarning, /EMFILE/);
    assert.ok(watchers.every(item => item.watcher.closed));
    invalidSource = true;
    await until(() => build.state().error !== null, 'Polling did not report a failed build');
    assert.equal(build.template, lastGood);
    assert.equal(build.state().revision, 3);

    invalidSource = false;
    sourceVersion = 2;
    await until(() => build.state().revision === 4, 'Polling did not recover after source repair');
    assert.equal(build.state().error, null);
    build.close();
    const readsAtClose = inputReads;
    sourceVersion = 3;
    await delay(1200);
    assert.equal(inputReads, readsAtClose, 'Closed build continued reading source inputs');
    assert.equal(build.state().revision, 4);
});
