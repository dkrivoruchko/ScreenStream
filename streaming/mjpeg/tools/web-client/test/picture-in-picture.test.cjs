const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createViewerTestContext, createDeferred } = require('./viewer-test-support.cjs');

function createEventTarget(properties = {}) {
    const listeners = new Map();
    return {
        ...properties,
        addEventListener: (name, callback) => listeners.set(name, callback),
        emit: (name, value = {}) => listeners.get(name)?.(value)
    };
}

function createPictureInPictureHarness(t) {
    const canvases = [], plays = [], entries = [], exits = [], notices = [], timers = new Set();
    const pip = createEventTarget();
    const video = createEventTarget({
        srcObject: null, readyState: 2, pauses: 0,
        pause() { this.pauses += 1; },
        play() { const operation = createDeferred(); plays.push(operation); return operation.promise; },
        requestPictureInPicture() { const operation = createDeferred(); entries.push(operation); return operation.promise; }
    });
    class FakeCanvas {
        constructor() {
            this.paints = 0;
            this.track = { stops: 0, stop() { this.stops += 1; } };
            this.stream = { getTracks: () => [this.track] };
            canvases.push(this);
        }
        getContext() { return { drawImage: () => { this.paints += 1; } }; }
        captureStream() { return this.stream; }
    }
    const document = {
        pictureInPictureEnabled: true, pictureInPictureElement: null,
        createElement(name) { assert.equal(name, 'canvas'); return new FakeCanvas(); },
        exitPictureInPicture() { const operation = createDeferred(); exits.push(operation); return operation.promise; }
    };
    const { createPictureInPicture } = createViewerTestContext(t, 'picture-in-picture', {
        document, window: { HTMLCanvasElement: FakeCanvas }, HTMLCanvasElement: FakeCanvas,
        setTimeout(callback, delay) {
            const timer = setTimeout(() => { timers.delete(timer); callback(); }, delay);
            timers.add(timer);
            return timer;
        },
        clearTimeout(timer) { timers.delete(timer); clearTimeout(timer); }
    });
    const owner = createPictureInPicture(pip, video, { notice: value => notices.push(value) });
    owner.start();
    owner.setImage({ naturalWidth: 640, naturalHeight: 360 });
    t.after(() => owner.stop());
    return { owner, pip, video, document, canvases, plays, entries, exits, notices, timers,
        click: () => pip.emit('click'),
        metadata: () => video.onloadedmetadata(),
        replaceImage: () => owner.setImage({ naturalWidth: 360, naturalHeight: 640 }) };
}

function assertReleased(h, canvas, pauses = 1) {
    assert.equal(canvas.track.stops, 1);
    assert.equal(h.video.srcObject, null);
    assert.equal(h.video.onloadedmetadata, null);
    assert.equal(h.video.pauses, pauses);
    assert.equal(h.timers.size, 0);
}

async function prepareNativeEntry(h) {
    h.click();
    h.metadata();
    const play = h.plays.at(-1);
    play.resolve();
    await play.promise;
    return h.entries.at(-1);
}

test('active PiP outlives preparation, releases on normal leave and ignores an older window leave', async t => {
    const h = createPictureInPictureHarness(t);
    const entry = await prepareNativeEntry(h), window = {};
    const canvas = h.canvases[0];
    h.document.pictureInPictureElement = h.video;
    entry.resolve(window);
    await entry.promise;
    assert.equal(h.pip.disabled, false);
    assert.equal(h.timers.size, 1);
    const paints = canvas.paints;
    t.mock.timers.tick(10001);
    assert.ok(canvas.paints > paints);
    assert.equal(canvas.track.stops, 0);
    assert.equal(h.video.srcObject, canvas.stream);
    assert.equal(h.exits.length, 0);
    assert.deepEqual(h.notices, []);

    h.document.pictureInPictureElement = null;
    h.video.emit('leavepictureinpicture', { pictureInPictureWindow: window });
    assertReleased(h, canvas);
    h.video.emit('leavepictureinpicture', { pictureInPictureWindow: window });
    assertReleased(h, canvas);
    const releasedPaints = canvas.paints;
    t.mock.timers.tick(1000);
    assert.equal(canvas.paints, releasedPaints);
    assert.equal(h.exits.length, 0);

    const replacementEntry = await prepareNativeEntry(h), replacementWindow = {};
    const replacement = h.canvases[1];
    h.document.pictureInPictureElement = h.video;
    replacementEntry.resolve(replacementWindow);
    await replacementEntry.promise;
    h.document.pictureInPictureElement = null;
    h.video.emit('leavepictureinpicture', { pictureInPictureWindow: window });
    assert.equal(replacement.track.stops, 0);
    assert.equal(h.video.srcObject, replacement.stream);
    assert.equal(h.timers.size, 1);
    h.video.emit('leavepictureinpicture', { pictureInPictureWindow: replacementWindow });
    assertReleased(h, replacement, 2);
    assert.deepEqual(h.notices, []);
});

test('NotAllowedError keeps the prepared stream for a second gesture that can enter PiP', async t => {
    const h = createPictureInPictureHarness(t);
    const entry = await prepareNativeEntry(h), canvas = h.canvases[0];
    entry.reject(Object.assign(new Error('Activation required'), { name: 'NotAllowedError' }));
    await assert.rejects(entry.promise, { name: 'NotAllowedError' });
    assert.deepEqual(h.notices, ['pipReady']);
    assert.equal(h.pip.disabled, false);
    assert.equal(canvas.track.stops, 0);
    assert.equal(h.video.srcObject, canvas.stream);
    assert.equal(h.timers.size, 2);
    t.mock.timers.tick(10001);
    assert.equal(canvas.track.stops, 0);
    h.click();
    assert.equal(h.entries.length, 2);
    assert.equal(h.canvases.length, 1);
    assert.equal(h.plays.length, 1);
    assert.equal(h.video.srcObject, canvas.stream);
    assert.equal(h.pip.disabled, true);
    const window = {};
    h.document.pictureInPictureElement = h.video;
    h.entries[1].resolve(window);
    await h.entries[1].promise;
    const paints = canvas.paints;
    t.mock.timers.tick(30000);
    assert.ok(canvas.paints > paints);
    assert.equal(canvas.track.stops, 0);
    assert.equal(h.video.srcObject, canvas.stream);
    assert.equal(h.pip.disabled, false);
    assert.deepEqual(h.notices, ['pipReady']);
    h.document.pictureInPictureElement = null;
    h.video.emit('leavepictureinpicture', { pictureInPictureWindow: window });
    assertReleased(h, canvas);
});

test('NotAllowedError readiness expires after 30 seconds and releases the prepared stream once', async t => {
    const h = createPictureInPictureHarness(t);
    const entry = await prepareNativeEntry(h), canvas = h.canvases[0];
    entry.reject(Object.assign(new Error('Activation required'), { name: 'NotAllowedError' }));
    await assert.rejects(entry.promise, { name: 'NotAllowedError' });
    t.mock.timers.tick(29999);
    assert.equal(canvas.track.stops, 0);
    assert.equal(h.video.srcObject, canvas.stream);
    t.mock.timers.tick(1);
    assertReleased(h, canvas);
    const paints = canvas.paints;
    t.mock.timers.tick(30000);
    assert.equal(canvas.paints, paints);
    assertReleased(h, canvas);
    assert.deepEqual(h.notices, ['pipReady']);
    assert.equal(h.entries.length, 1);
    assert.equal(h.exits.length, 0);
    h.click();
    assert.equal(h.canvases.length, 2);
    assert.equal(h.video.srcObject, h.canvases[1].stream);
});

test('an ordinary native entry failure reports pipError and immediately releases the stream', async t => {
    const h = createPictureInPictureHarness(t);
    const entry = await prepareNativeEntry(h), canvas = h.canvases[0];
    entry.reject(new Error('Native entry failed'));
    await assert.rejects(entry.promise, /Native entry failed/);
    assert.deepEqual(h.notices, ['pipError']);
    assertReleased(h, canvas);
    const paints = canvas.paints;
    t.mock.timers.tick(30000);
    assert.equal(canvas.paints, paints);
    assertReleased(h, canvas);
    assert.equal(h.exits.length, 0);
    h.click();
    assert.equal(h.canvases.length, 2);
    assert.equal(h.video.srcObject, h.canvases[1].stream);
});

for (const outcome of ['resolve', 'reject']) test(`revocation releases a pending play and ignores its late ${outcome}`, async t => {
    const h = createPictureInPictureHarness(t);
    h.click();
    const canvas = h.canvases[0];
    assert.equal(h.video.srcObject, canvas.stream);
    assert.equal(h.timers.size, 2);
    h.metadata();
    assert.equal(h.plays.length, 1);
    h.owner.setImage(null);
    assertReleased(h, canvas);
    assert.equal(h.pip.disabled, true);
    const paints = canvas.paints;
    if (outcome === 'resolve') h.plays[0].resolve();
    else h.plays[0].reject(new Error('Late play failure'));
    await h.plays[0].promise.catch(() => {});
    t.mock.timers.tick(30000);
    assert.equal(canvas.paints, paints);
    assert.equal(h.entries.length, 0);
    assert.equal(h.exits.length, 0);
    assert.deepEqual(h.notices, []);
    assertReleased(h, canvas);
    h.replaceImage();
    h.click();
    assert.equal(h.canvases.length, 2);
    assert.equal(h.video.srcObject, h.canvases[1].stream);
});

test('stopping pending native entry releases resources, exits late entry and serializes the next attempt', async t => {
    const h = createPictureInPictureHarness(t);
    h.click();
    h.metadata();
    h.plays[0].resolve();
    await h.plays[0].promise;
    assert.equal(h.entries.length, 1);
    assert.equal(h.pip.disabled, true);
    const canvas = h.canvases[0];
    h.owner.stop();
    assertReleased(h, canvas);
    h.replaceImage();
    h.click();
    assert.equal(h.canvases.length, 1, 'A pending native entry must prevent an overlapping attempt');
    assert.equal(h.entries.length, 1);
    const paints = canvas.paints;
    t.mock.timers.tick(30000);
    assert.equal(canvas.paints, paints);
    assert.deepEqual(h.notices, []);

    h.document.pictureInPictureElement = h.video;
    h.entries[0].resolve({});
    await h.entries[0].promise;
    assert.equal(h.exits.length, 1, 'A late successful native entry must be exited');
    assertReleased(h, canvas);
    h.click();
    assert.equal(h.canvases.length, 1, 'A pending native exit must prevent an overlapping attempt');
    assert.equal(h.pip.disabled, true);
    h.document.pictureInPictureElement = null;
    h.exits[0].resolve();
    await h.exits[0].promise;
    assert.equal(h.pip.disabled, false);
    h.click();
    assert.equal(h.canvases.length, 2);
    const replacement = h.canvases[1];
    assert.equal(h.video.srcObject, replacement.stream);
    assert.equal(replacement.track.stops, 0);
    assert.equal(h.entries.length, 1, 'The retired attempt must not request entry again');
    assert.deepEqual(h.notices, []);
});
