const assert = require('node:assert/strict');
const { test } = require('node:test');
const { runtime, deferred } = require('./runtime.cjs');

function eventTarget(properties = {}) {
    const listeners = new Map();
    return {
        ...properties,
        addEventListener: (name, callback) => listeners.set(name, callback),
        emit: (name, value = {}) => listeners.get(name)?.(value)
    };
}

function pictureInPicture(t) {
    const canvases = [], plays = [], entries = [], exits = [], notices = [], timers = new Set();
    const pip = eventTarget();
    const video = eventTarget({
        srcObject: null, readyState: 2, pauses: 0,
        pause() { this.pauses += 1; },
        play() { const operation = deferred(); plays.push(operation); return operation.promise; },
        requestPictureInPicture() { const operation = deferred(); entries.push(operation); return operation.promise; }
    });
    class Canvas {
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
        createElement(name) { assert.equal(name, 'canvas'); return new Canvas(); },
        exitPictureInPicture() { const operation = deferred(); exits.push(operation); return operation.promise; }
    };
    const { createPictureInPicture } = runtime(t, 'picture-in-picture', {
        document, window: { HTMLCanvasElement: Canvas }, HTMLCanvasElement: Canvas,
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

function assertReleased(h, canvas) {
    assert.equal(canvas.track.stops, 1);
    assert.equal(h.video.srcObject, null);
    assert.equal(h.video.onloadedmetadata, null);
    assert.equal(h.video.pauses, 1);
    assert.equal(h.timers.size, 0);
}

for (const outcome of ['resolve', 'reject']) test(`revocation releases a pending play and ignores its late ${outcome}`, async t => {
    const h = pictureInPicture(t);
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
    const h = pictureInPicture(t);
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
