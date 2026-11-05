const assert = require('node:assert/strict');
const { test } = require('node:test');
const { runtime, deferred } = require('./runtime.cjs');

function media(t) {
    const images = [], frames = [], states = [];
    class ImageBoundary {
        constructor() {
            Object.assign(this, { naturalWidth: 0, naturalHeight: 0, complete: false, style: {}, attributes: {}, decoding: deferred(), decodes: 0 });
            images.push(this);
        }
        setAttribute(key, value) { this.attributes[key] = value; }
        removeAttribute(key) { delete this.attributes[key]; if (key === 'src') this.src = ''; }
        decode() { this.decodes += 1; return this.decoding.promise; }
        load(width = 640, height = 360, complete = true) {
            Object.assign(this, { naturalWidth: width, naturalHeight: height, complete });
            this.onload();
        }
    }
    const stage = {
        children: [],
        appendChild(image) { this.children.push(image); image.parentNode = this; },
        removeChild(image) { this.children.splice(this.children.indexOf(image), 1); image.parentNode = null; }
    };
    const { createMedia } = runtime(t, 'media', { Image: ImageBoundary });
    const owner = createMedia(stage, 'Screen image', {
        image: (image, width, height) => frames.push({ image, width, height }), state: value => states.push(value)
    });
    t.after(() => owner.pause());
    return { owner, images, frames, states, stage,
        image: () => images.at(-1), frame: () => frames.at(-1), state: () => states.at(-1),
        apply: (value = {}, refresh = false) => owner.apply({ eligible: true, imageKind: 'live', method: 'mjpeg', ...value }, refresh) };
}

test('MJPEG promotes usable dimensions and keeps the displayed frame through replacement failure', t => {
    const h = media(t);
    h.apply();
    const first = h.image();
    assert.match(first.src, /^\/mjpeg\?requestSequence=\d+$/);
    first.load(0, 360);
    first.load(640, 0);
    assert.equal(h.frames.length, 0);
    first.load(640, 360, false);
    assert.equal(h.frame().image, first);
    assert.equal(h.state(), 'viewing');
    h.apply({}, true);
    const failed = h.image();
    assert.equal(h.stage.children.length, 2);
    failed.onerror();
    assert.equal(h.frame().image, first);
    assert.equal(first.parentNode, h.stage);
    assert.equal(failed.src, '');
    assert.equal(h.state(), 'retrying');
    t.mock.timers.tick(0);
    const replacement = h.image();
    replacement.load(360, 640);
    assert.equal(h.frame().image, replacement);
    assert.equal(h.frame().width, 360);
    assert.equal(h.frame().height, 640);
    assert.equal(first.src, '');
    assert.equal(first.parentNode, null);
    assert.equal(h.stage.children.length, 1);
});

test('live JPEG waits 30 ms after successful decoding and remains single-flight', async t => {
    const h = media(t);
    h.apply({ method: 'jpeg' });
    const first = h.image();
    assert.match(first.src, /^\/jpeg\?requestSequence=\d+$/);
    first.load(640, 360, false);
    assert.equal(first.decodes, 0);
    first.load();
    first.onload();
    assert.equal(first.decodes, 1);
    assert.equal(h.images.length, 1);
    assert.equal(h.frames.length, 0);
    t.mock.timers.tick(1000);
    assert.equal(h.images.length, 1);
    assert.equal(h.frames.length, 0);
    first.decoding.resolve();
    await first.decoding.promise;
    assert.equal(h.frame().image, first);
    h.owner.resume();
    assert.equal(h.images.length, 1);
    assert.equal(h.stage.children.length, 1);
    t.mock.timers.tick(29);
    assert.equal(h.images.length, 1);
    t.mock.timers.tick(1);
    assert.equal(h.images.length, 2);
    assert.equal(h.stage.children.length, 2);
    const second = h.image();
    second.load();
    assert.equal(h.images.length, 2);
    assert.equal(h.frame().image, first);
    second.decoding.resolve();
    await second.decoding.promise;
    assert.equal(h.frame().image, second);
    assert.equal(h.images.length, 2);
    assert.equal(first.src, '');
    assert.equal(h.stage.children.length, 1);
    t.mock.timers.tick(29);
    assert.equal(h.images.length, 2);
    t.mock.timers.tick(1);
    assert.equal(h.images.length, 3);
    assert.equal(h.stage.children.length, 2);
});

for (const action of ['pause', 'ineligibility', 'method switch', 'refresh']) test(`${action} cancels the pending live-JPEG interval`, async t => {
    const h = media(t);
    h.apply({ method: 'jpeg' });
    const displayed = h.image();
    displayed.load();
    displayed.decoding.resolve();
    await displayed.decoding.promise;
    t.mock.timers.tick(15);
    if (action === 'pause') {
        h.owner.pause();
        assert.equal(h.frame().image, null);
        h.owner.resume();
    } else if (action === 'ineligibility') {
        h.apply({ method: 'jpeg', eligible: false });
        assert.equal(h.frame().image, null);
        h.apply({ method: 'jpeg' });
    } else if (action === 'method switch') h.apply({ method: 'mjpeg' });
    else h.apply({ method: 'jpeg' }, true);
    assert.equal(h.images.length, 2);
    const replacement = h.image();
    assert.match(replacement.src, action === 'method switch' ? /^\/mjpeg\?/ : /^\/jpeg\?/);
    t.mock.timers.tick(15);
    assert.equal(h.images.length, 2);
    t.mock.timers.tick(30);
    assert.equal(h.images.length, 2);
    assert.equal(h.image(), replacement);
    assert.notEqual(replacement.src, '');
});

test('a refreshed JPEG rejects an old decode without scheduling a polling interval', async t => {
    const h = media(t);
    h.apply({ method: 'jpeg' });
    const stale = h.image();
    stale.load();
    h.apply({ method: 'jpeg' }, true);
    const replacement = h.image();
    stale.decoding.resolve();
    await stale.decoding.promise;
    t.mock.timers.tick(30);
    assert.equal(h.images.length, 2);
    assert.equal(h.frames.length, 0);
    assert.equal(h.image(), replacement);
    replacement.load();
    replacement.decoding.resolve();
    await replacement.decoding.promise;
    assert.equal(h.frame().image, replacement);
    t.mock.timers.tick(29);
    assert.equal(h.images.length, 2);
    t.mock.timers.tick(1);
    assert.equal(h.images.length, 3);
});

test('retired load, error and decode callbacks cannot replace a newer image', async t => {
    const h = media(t);
    h.apply({ method: 'jpeg' });
    const stale = h.image(), lateLoad = stale.onload, lateError = stale.onerror;
    stale.load();
    h.apply({ method: 'mjpeg' });
    const current = h.image();
    current.load(360, 640);
    const frames = h.frames.length, images = h.images.length;
    stale.decoding.resolve();
    await stale.decoding.promise;
    lateLoad();
    lateError();
    t.mock.timers.tick(30);
    assert.equal(h.state(), 'viewing');
    assert.equal(h.frame().image, current);
    assert.equal(h.frames.length, frames);
    assert.equal(h.images.length, images);
    assert.equal(stale.src, '');
    assert.equal(stale.parentNode, null);
});

test('retained JPEG waits 500 ms and retained-to-live wakes the next request immediately', async t => {
    const h = media(t);
    h.apply({ method: 'jpeg', imageKind: 'retained' });
    const first = h.image();
    first.load();
    first.decoding.resolve();
    await first.decoding.promise;
    assert.equal(h.images.length, 1);
    t.mock.timers.tick(499);
    assert.equal(h.images.length, 1);
    t.mock.timers.tick(1);
    assert.equal(h.images.length, 2);
    const second = h.image();
    second.load();
    second.decoding.resolve();
    await second.decoding.promise;
    t.mock.timers.tick(100);
    h.apply({ method: 'jpeg', imageKind: 'live' });
    assert.equal(h.images.length, 3);
    t.mock.timers.tick(400);
    assert.equal(h.images.length, 3);
    assert.equal(h.frame().image, second);
    h.apply({ method: 'jpeg', imageKind: 'retained' });
    assert.equal(h.images.length, 3);
});

test('initial-image deadline bounds attempts but an established MJPEG image has no freshness deadline', t => {
    const h = media(t);
    h.apply();
    t.mock.timers.tick(9999);
    assert.equal(h.images.length, 1);
    assert.equal(h.state(), 'starting');
    t.mock.timers.tick(1);
    t.mock.timers.tick(0);
    assert.equal(h.images.length, 2);
    assert.equal(h.images[0].src, '');
    const displayed = h.image();
    displayed.load();
    for (let elapsed = 0; elapsed < 12000; elapsed += 500) t.mock.timers.tick(500);
    assert.equal(h.images.length, 2);
    assert.equal(h.frame().image, displayed);
    assert.equal(h.state(), 'viewing');
});

test('retry budget exhausts without snapshot resets and explicit refresh can recover', t => {
    const h = media(t);
    h.apply();
    for (const delay of [0, 1000, 2000, 4000, 8000]) {
        h.image().onerror();
        const count = h.images.length;
        h.apply();
        assert.equal(h.images.length, count);
        if (delay) {
            t.mock.timers.tick(delay - 1);
            assert.equal(h.images.length, count);
            t.mock.timers.tick(1);
        } else t.mock.timers.tick(0);
        assert.equal(h.images.length, count + 1);
    }
    h.image().onerror();
    assert.equal(h.state(), 'failed');
    const exhausted = h.images.length;
    h.apply({ imageKind: 'retained' });
    t.mock.timers.tick(10000);
    assert.equal(h.images.length, exhausted);
    assert.equal(h.state(), 'failed');
    h.apply({}, true);
    assert.equal(h.images.length, exhausted + 1);
    h.image().onerror();
    t.mock.timers.tick(0);
    assert.equal(h.images.length, exhausted + 2);
    h.image().load();
    h.apply();
    assert.equal(h.images.length, exhausted + 2);
    h.image().onerror();
    t.mock.timers.tick(0);
    assert.equal(h.images.length, exhausted + 3);
    h.apply({ eligible: false });
    h.apply();
    assert.equal(h.images.length, exhausted + 4);
});

test('displayed-image loss preserves a loading replacement and its original deadline', t => {
    const h = media(t);
    h.apply();
    const displayed = h.image();
    displayed.load();
    h.apply({}, true);
    const replacement = h.image();
    t.mock.timers.tick(9000);
    displayed.onerror();
    assert.equal(h.frame().image, null);
    assert.equal(h.state(), 'retrying');
    assert.equal(h.images.length, 2);
    assert.equal(replacement.parentNode, h.stage);
    t.mock.timers.tick(999);
    assert.equal(h.images.length, 2);
    t.mock.timers.tick(1);
    t.mock.timers.tick(0);
    assert.equal(h.images.length, 3);
    assert.equal(replacement.src, '');
    h.image().load();
    assert.equal(h.state(), 'viewing');
});

test('dimension changes are reported and lost dimensions do not replace a scheduled retry', t => {
    const h = media(t);
    h.apply();
    const displayed = h.image();
    displayed.load();
    displayed.naturalWidth = 360;
    displayed.naturalHeight = 640;
    t.mock.timers.tick(500);
    assert.equal(h.frame().width, 360);
    assert.equal(h.frame().height, 640);
    h.apply({}, true);
    h.image().onerror();
    t.mock.timers.tick(0);
    h.image().onerror();
    const attempts = h.images.length;
    displayed.naturalWidth = 0;
    t.mock.timers.tick(500);
    assert.equal(h.frame().image, null);
    assert.equal(h.state(), 'retrying');
    assert.equal(h.images.length, attempts);
    t.mock.timers.tick(499);
    assert.equal(h.images.length, attempts);
    t.mock.timers.tick(1);
    assert.equal(h.images.length, attempts + 1);
});

test('decode rejection retries while preserving a displayed JPEG', async t => {
    const h = media(t);
    h.apply({ method: 'jpeg' });
    const displayed = h.image();
    displayed.load();
    displayed.decoding.resolve();
    await displayed.decoding.promise;
    t.mock.timers.tick(30);
    const rejected = h.image();
    rejected.load();
    rejected.decoding.reject(new Error('decode rejected'));
    await assert.rejects(rejected.decoding.promise, /decode rejected/);
    assert.equal(h.frame().image, displayed);
    assert.equal(h.state(), 'retrying');
    t.mock.timers.tick(0);
    assert.equal(h.images.length, 3);
    assert.equal(h.stage.children.length, 2);
});

test('pause and ineligibility release images and reject late decode completion', async t => {
    const h = media(t);
    for (const stop of [() => h.owner.pause(), () => h.apply({ eligible: false })]) {
        h.apply({ method: 'jpeg' }, true);
        const displayed = h.image();
        displayed.load();
        displayed.decoding.resolve();
        await displayed.decoding.promise;
        t.mock.timers.tick(30);
        const pending = h.image(), lateError = pending.onerror;
        pending.load();
        stop();
        assert.equal(h.frame().image, null);
        assert.equal(h.stage.children.length, 0);
        for (const image of [displayed, pending]) {
            assert.equal(image.src, '');
            assert.equal(image.onload, null);
            assert.equal(image.onerror, null);
        }
        const frames = h.frames.length, images = h.images.length;
        pending.decoding.resolve();
        await pending.decoding.promise;
        lateError();
        t.mock.timers.tick(20000);
        assert.equal(h.frames.length, frames);
        assert.equal(h.images.length, images);
    }
    h.owner.resume();
    assert.equal(h.stage.children.length, 0);
    h.apply();
    assert.equal(h.stage.children.length, 1);
});

test('resume restarts missing eligible media but leaves a healthy displayed image alone', t => {
    const h = media(t);
    h.apply();
    const first = h.image();
    h.owner.resume();
    assert.equal(h.images.length, 1);
    first.load();
    h.owner.resume();
    assert.equal(h.images.length, 1);
    h.owner.pause();
    h.owner.resume();
    assert.equal(h.images.length, 2);
});
