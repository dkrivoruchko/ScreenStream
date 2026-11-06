const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');
const { test: nodeTest } = require('node:test');
const WebSocket = require('ws');
const { buildHtml } = require('../build.cjs');
const { createPreviewServer } = require('./server.cjs');
const test = (name, body) => nodeTest(name, { timeout: 10000 }, body);

// Exercise the preview server through real HTTP/WS, independently of the viewer's helpers.
async function startPreviewServer(t) {
    const server = createPreviewServer({ port: 0, watch: false });
    const address = await server.listen();
    t.after(() => server.close());
    return { ...server, ...address };
}

async function requestPreview(preview, path, options = {}) {
    return fetch(preview.url + path, {
        redirect: 'manual',
        signal: AbortSignal.timeout(3000),
        ...options,
        headers: { Origin: preview.url, ...options.headers }
    });
}

async function readPreviewState(preview) {
    const response = await requestPreview(preview, '/__preview/api/state');
    assert.equal(response.status, 200);
    return response.json();
}

async function updatePreview(preview, path, body) {
    const response = await requestPreview(preview, '/__preview/api/' + path, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    });
    assert.equal(response.status, 200, await response.clone().text());
    return response.json();
}

function selectScenario(preview, id) { return updatePreview(preview, 'scenario', { id }); }

function submitPin(preview, value, cookie) {
    return requestPreview(preview, '/pin', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded', ...(cookie ? { Cookie: cookie } : {}) },
        body: 'pin=' + encodeURIComponent(value)
    });
}

function readAccessCookie(response) {
    const value = response.headers.get('set-cookie');
    assert.match(value || '', /^screenstream_access=[^;]+; Path=\/; HttpOnly; SameSite=Lax$/);
    return value.split(';')[0];
}

function assertAuthorizedSnapshot(value, access) {
    assert.equal(value.access, access);
    assert.deepEqual(Object.keys(value).sort(), ['access', 'display', 'hasImage', 'imageKind', 'notice']);
    assert.equal(typeof value.hasImage, 'boolean');
    assert.ok(['none', 'logo', 'live', 'retained'].includes(value.imageKind));
    assert.equal(value.hasImage, value.imageKind !== 'none');
    assert.ok([null, 'permission_required', 'stopped', 'paused', 'failed'].includes(value.notice));
    assert.deepEqual(Object.keys(value.display).sort(), ['background', 'controlsLayout', 'title', 'titleEnabled', 'titleLayout']);
    assert.match(value.display.background, /^#[0-9a-fA-F]{6}$/);
    assert.equal(typeof value.display.title, 'string');
    assert.equal(typeof value.display.titleEnabled, 'boolean');
    for (const layout of [value.display.titleLayout, value.display.controlsLayout]) {
        assert.deepEqual(Object.keys(layout).sort(), ['autoHide', 'position', 'presentation']);
        assert.ok(['top', 'bottom'].includes(layout.position));
        assert.ok(['bar', 'overlay'].includes(layout.presentation));
        assert.equal(typeof layout.autoHide, 'boolean');
    }
    return value;
}

function connectStateClient(preview, cookie) {
    const socket = new WebSocket(preview.url.replace('http:', 'ws:') + '/ws', {
        origin: preview.url, headers: cookie ? { Cookie: cookie } : {}
    });
    const messages = [], waiters = [];
    let cursor = 0, failure = null, ended = false, lastSnapshot = null;
    const closed = new Promise(resolve => socket.once('close', (code, reason) => {
        ended = true;
        while (waiters.length) waiters.shift().reject(new Error('WebSocket closed before its next snapshot'));
        resolve({ code, reason: reason.toString() });
    }));
    socket.on('error', error => {
        failure = error;
        while (waiters.length) waiters.shift().reject(error);
    });
    socket.on('message', data => {
        const value = data.toString();
        messages.push(value);
        if (waiters.length) { cursor += 1; waiters.shift().resolve(JSON.parse(value)); }
    });
    return {
        socket, messages, closed,
        async next(timeout = 2000) {
            let value;
            if (cursor < messages.length) value = JSON.parse(messages[cursor++]);
            else {
                if (failure || ended) throw failure || new Error('WebSocket is closed');
                value = await new Promise((resolve, reject) => {
                    const waiter = { resolve: value => { clearTimeout(timer); resolve(value); }, reject: error => { clearTimeout(timer); reject(error); } };
                    const timer = setTimeout(() => {
                        const index = waiters.indexOf(waiter);
                        if (index >= 0) waiters.splice(index, 1);
                        reject(new Error('Timed out waiting for a WebSocket snapshot'));
                    }, timeout);
                    waiters.push(waiter);
                });
            }
            lastSnapshot = value;
            return value;
        },
        async nextMatching(predicate, timeout = 2000) {
            const deadline = Date.now() + timeout;
            while (true) {
                const remaining = deadline - Date.now();
                assert.ok(remaining > 0, 'Timed out waiting for the expected WebSocket transition');
                const previous = lastSnapshot;
                const value = await this.next(remaining);
                if (predicate(value)) return value;
                // Only unchanged keepalives may precede the requested transition.
                assert.deepEqual(value, previous, 'Unexpected WebSocket state before the expected transition');
            }
        }
    };
}

async function readStateSnapshot(preview, cookie) {
    const client = connectStateClient(preview, cookie);
    try { return await client.next(); }
    finally { client.socket.terminate(); }
}

function openMediaStream(preview, path = '/mjpeg', cookie) {
    return new Promise((resolve, reject) => {
        const request = http.get(preview.url + path, { headers: cookie ? { Cookie: cookie } : {} });
        request.once('error', reject);
        request.once('response', response => {
            const chunks = [];
            response.on('data', data => chunks.push(data));
            response.on('error', () => {});
            resolve({ request, response, body: () => Buffer.concat(chunks), destroy: () => { response.destroy(); request.destroy(); } });
        });
    });
}

async function waitForCondition(predicate, message, timeout = 2000) {
    const deadline = Date.now() + timeout;
    while (!predicate()) {
        if (Date.now() >= deadline) assert.fail(message);
        await delay(5);
    }
}

async function readJpegPayload(preview, cookie) {
    const response = await requestPreview(preview, '/jpeg', { headers: cookie ? { Cookie: cookie } : {} });
    assert.equal(response.status, 200);
    return Buffer.from(await response.arrayBuffer());
}

function readCaptureJpegs(orientation) {
    return Array.from({ length: 8 }, (_, frame) => fs.readFileSync(path.join(__dirname, 'fixtures', orientation + '-' + frame + '.jpg')));
}

function parseMultipartImages(body) {
    const boundary = Buffer.from('--screenstream-jpeg\r\n');
    const footer = Buffer.from('\r\n--screenstream-jpeg\r\n');
    const images = [];
    let offset = boundary.length;
    if (body.length < offset) return images;
    assert.deepEqual(body.subarray(0, offset), boundary);
    while (offset < body.length) {
        const end = body.indexOf('\r\n\r\n', offset);
        if (end < 0) break;
        const header = body.toString('ascii', offset, end);
        const length = /^Content-Type: image\/jpeg\r\nContent-Length: ([0-9]+)$/.exec(header);
        assert.ok(length, 'Invalid multipart part headers');
        const start = end + 4, next = start + Number(length[1]);
        if (body.length < next + footer.length) break;
        assert.deepEqual(body.subarray(next, next + footer.length), footer);
        images.push(body.subarray(start, next));
        offset = next + footer.length;
    }
    return images;
}

async function waitForLiveFramesAfter(stream, start, fixtures, message) {
    await waitForCondition(() => {
        const live = parseMultipartImages(stream.body()).slice(start).filter(image => fixtures.some(frame => frame.equals(image)));
        return live.length > 1 && live.some(image => !image.equals(live[0]));
    }, message);
}

test('root serves the panel and viewer serves the unchanged shared build', async t => {
    const preview = await startPreviewServer(t);
    const panel = await requestPreview(preview, '/');
    assert.equal(panel.status, 200);
    const panelHtml = await panel.text();
    assert.match(panelHtml, /id="viewer"/);
    assert.match(panelHtml, /href="\/viewer"/);
    assert.match(panelHtml, /src="\/__preview\/panel\.js"/);
    assert.match(panelHtml, /href="\/__preview\/panel\.css"/);
    const initial = await readPreviewState(preview);
    const invalid = await requestPreview(preview, '/__preview/api/appearance', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ background: '#123456; </style><script>invalid</script>' })
    });
    assert.equal(invalid.status, 400);
    assert.equal((await readPreviewState(preview)).appearance.background, initial.appearance.background);
    const appearance = { ...initial.appearance, background: '#a1B2c3', title: 'Private fixture title <not HTML>' };
    await updatePreview(preview, 'appearance', appearance);
    await selectScenario(preview, 'pin');
    const response = await requestPreview(preview, '/viewer');
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
    const html = await response.text();
    const current = await readPreviewState(preview);
    assert.ok(html === buildHtml(), 'Viewer differs from the shared build; finish source edits before running checks');
    assert.ok(!html.includes(appearance.background));
    assert.ok(!html.includes(appearance.title));
    const denied = connectStateClient(preview);
    assert.deepEqual(await denied.next(), { access: 'pin_required' });
    assert.equal((await denied.closed).code, 1000);
    const catalog = await (await requestPreview(preview, '/__preview/api/catalog')).json();
    assert.deepEqual(catalog.scenarios.map(value => value.id), ['normal', 'pin', 'blocked', 'starting', 'permission_required', 'paused', 'stopped', 'failed']);
    assert.equal(Object.hasOwn(catalog, 'actions'), false);
    assert.equal(current.build.error, null);
    assert.ok(current.build.bytes > 0);
});

test('scenario pairs are canonical, preserve settings and fully reset to defaults', async t => {
    const preview = await startPreviewServer(t);
    const defaults = (await readPreviewState(preview)).appearance;
    const appearance = { ...defaults, background: '#234567', title: 'Preset order check' };
    await updatePreview(preview, 'appearance', appearance);
    assert.deepEqual((await readPreviewState(preview)).options, { postStopImage: 'logo', imageAvailable: true });
    const options = { postStopImage: 'last', imageAvailable: true };
    await updatePreview(preview, 'options', options);
    await updatePreview(preview, 'action', { id: 'orientation', orientation: 'landscape' });
    const ids = ['normal', 'pin', 'blocked', 'starting', 'permission_required', 'paused', 'stopped', 'failed'];
    const landscape = readCaptureJpegs('landscape');
    for (const previous of ids) for (const id of ids) {
        await selectScenario(preview, previous);
        const current = await selectScenario(preview, id);
        const protectedAccess = id === 'pin' || id === 'blocked';
        const live = ['normal', 'pin', 'blocked'].includes(id);
        const imageKind = live ? 'live' : 'retained';
        const notice = live || id === 'starting' ? null : id;
        assert.equal(current.delivery.access, protectedAccess ? 'protected' : 'open', previous + ' -> ' + id);
        if (id === 'blocked') assert.ok(current.delivery.blockedRemainingMs > 0);
        else assert.equal(current.delivery.blockedRemainingMs, 0);
        assert.equal(current.delivery.hasImage, true);
        assert.equal(current.delivery.imageKind, imageKind);
        assert.equal(current.delivery.notice, notice);
        assert.equal(current.delivery.orientation, 'landscape');
        assert.deepEqual(current.appearance, appearance);
        assert.deepEqual(current.options, options);
        const wire = await readStateSnapshot(preview);
        if (protectedAccess) assert.equal(wire.access, id === 'pin' ? 'pin_required' : 'blocked');
        else {
            assertAuthorizedSnapshot(wire, 'open');
            assert.equal(wire.imageKind, imageKind);
            assert.equal(wire.notice, notice);
            assert.deepEqual(wire.display, appearance);
        }
        const jpeg = await requestPreview(preview, '/jpeg' + (protectedAccess ? '?token=' + encodeURIComponent(current.delivery.credentials.token) : ''));
        assert.equal(jpeg.status, 200);
        const bytes = Buffer.from(await jpeg.arrayBuffer());
        if (live) assert.ok(landscape.some(frame => frame.equals(bytes)));
        else assert.deepEqual(bytes, landscape[0]);
    }
    const normal = await selectScenario(preview, 'normal');
    assert.equal(normal.delivery.notice, null);
    assert.equal(normal.delivery.hasImage, true);
    assert.equal((await requestPreview(preview, '/jpeg')).status, 200);
    await updatePreview(preview, 'options', { imageAvailable: false });
    for (const id of ['normal', 'pin', 'blocked']) {
        const unavailable = await selectScenario(preview, id);
        assert.equal(unavailable.delivery.notice, null);
        assert.equal(unavailable.delivery.imageKind, 'logo');
        const token = unavailable.delivery.credentials.token;
        assert.deepEqual(Buffer.from(await (await requestPreview(preview, '/jpeg?token=' + encodeURIComponent(token))).arrayBuffer()),
            fs.readFileSync(path.join(__dirname, 'fixtures', 'logo.jpg')));
    }
    await updatePreview(preview, 'options', { postStopImage: 'last', imageAvailable: false });
    await selectScenario(preview, 'blocked');
    const denied = await readStateSnapshot(preview);
    assert.deepEqual(Object.keys(denied).sort(), ['access', 'retryAfterMs']);
    assert.equal(denied.access, 'blocked');
    assert.ok(denied.retryAfterMs > 0);
    await updatePreview(preview, 'action', { id: 'orientation', orientation: 'landscape' });
    const reset = await updatePreview(preview, 'reset', {});
    assert.equal(reset.scenario.id, 'normal');
    assert.equal(reset.delivery.access, 'open');
    assert.equal(reset.delivery.orientation, 'portrait');
    assert.equal(reset.delivery.blockedRemainingMs, 0);
    assert.deepEqual(reset.appearance, defaults);
    assert.deepEqual(reset.options, { postStopImage: 'logo', imageAvailable: true });
});

test('viewing cookies are reused and remain admitted while PIN entry is blocked', async t => {
    const preview = await startPreviewServer(t);
    const configured = await selectScenario(preview, 'pin');
    const correct = configured.delivery.credentials.pin;
    const first = await submitPin(preview, correct);
    assert.equal(first.status, 204);
    const cookie = readAccessCookie(first);
    const second = await submitPin(preview, correct);
    assert.equal(second.status, 204);
    assert.equal(readAccessCookie(second), cookie);
    const wrong = correct === '000000' ? '111111' : '000000';
    for (let attempt = 1; attempt <= 5; attempt += 1) {
        const response = await submitPin(preview, wrong);
        assert.equal(response.status, attempt < 5 ? 403 : 429);
        if (attempt === 5) {
            const remaining = Number(response.headers.get('retry-after'));
            assert.ok(remaining > 0 && remaining <= 20);
        }
    }
    assert.equal((await submitPin(preview, correct)).status, 429);
    assert.equal((await readStateSnapshot(preview)).access, 'blocked');
    assertAuthorizedSnapshot(await readStateSnapshot(preview, cookie), 'authorized');
    assert.equal((await requestPreview(preview, '/jpeg', { headers: { Cookie: cookie } })).status, 200);
});

test('token exchange and access scenario boundaries revoke old credentials', async t => {
    const preview = await startPreviewServer(t);
    const configured = await selectScenario(preview, 'pin');
    const token = configured.delivery.credentials.token;
    for (const supplied of ['', 'invalid']) {
        const denied = await requestPreview(preview, '/viewer?token=' + supplied);
        assert.equal(denied.status, 303);
        assert.equal(denied.headers.get('location'), '/viewer');
        assert.equal(denied.headers.get('set-cookie'), null);
        assert.equal(denied.headers.get('referrer-policy'), 'no-referrer');
    }
    const exchanged = await requestPreview(preview, '/viewer?token=' + encodeURIComponent(token));
    assert.equal(exchanged.status, 303);
    assert.equal(exchanged.headers.get('location'), '/viewer');
    const cookie = readAccessCookie(exchanged);
    assert.equal(readAccessCookie(await submitPin(preview, configured.delivery.credentials.pin)), cookie);
    assert.equal((await requestPreview(preview, '/jpeg?token=' + encodeURIComponent(token))).status, 200);
    assertAuthorizedSnapshot(await readStateSnapshot(preview, cookie), 'authorized');
    await updatePreview(preview, 'action', { id: 'fail-image-requests', enabled: true });
    for (const path of ['/jpeg', '/mjpeg']) {
        assert.equal((await requestPreview(preview, path)).status, 403, 'Authorization must precede media fault injection');
        assert.equal((await requestPreview(preview, path, { headers: { Cookie: cookie } })).status, 503);
    }
    assert.equal(assertAuthorizedSnapshot(await readStateSnapshot(preview, cookie), 'authorized').hasImage, true);
    await updatePreview(preview, 'reset', {});
    const rotated = await selectScenario(preview, 'pin');
    assert.equal((await requestPreview(preview, '/jpeg', { headers: { Cookie: cookie } })).status, 403);
    assert.deepEqual(await readStateSnapshot(preview, cookie), { access: 'pin_required' });
    assert.equal((await requestPreview(preview, '/viewer?token=' + encodeURIComponent(token))).headers.get('set-cookie'), null);
    const currentCookie = readAccessCookie(await submitPin(preview, rotated.delivery.credentials.pin));
    const admitted = connectStateClient(preview, currentCookie);
    assertAuthorizedSnapshot(await admitted.next(), 'authorized');
    await selectScenario(preview, 'blocked');
    await admitted.closed;
    const denied = connectStateClient(preview, currentCookie);
    assert.equal((await denied.next()).access, 'blocked');
    assert.equal((await denied.closed).code, 1000);
    const capture = await selectScenario(preview, 'permission_required');
    assert.equal(capture.delivery.access, 'open');
    assert.equal(capture.delivery.blockedRemainingMs, 0);
    const resumed = connectStateClient(preview);
    try {
        const pending = assertAuthorizedSnapshot(await resumed.next(), 'open');
        assert.equal(pending.notice, 'permission_required');
        assert.equal(pending.imageKind, 'logo');
        await selectScenario(preview, 'normal');
        assert.equal(assertAuthorizedSnapshot(await resumed.nextMatching(value => value.notice === null && value.imageKind === 'live'), 'open').notice, null);
        assert.equal(resumed.socket.readyState, WebSocket.OPEN);
        assert.equal((await requestPreview(preview, '/jpeg')).status, 200);
    } finally { resumed.socket.terminate(); }
});

test('JPEG orientation, length and multipart bytes match the delivery framing', async t => {
    const preview = await startPreviewServer(t);
    const jpeg = await requestPreview(preview, '/jpeg');
    const bytes = Buffer.from(await jpeg.arrayBuffer());
    assert.equal(jpeg.headers.get('content-type'), 'image/jpeg');
    assert.equal(Number(jpeg.headers.get('content-length')), bytes.length);
    assert.equal(bytes.readUInt16BE(0), 0xffd8);
    assert.equal(bytes.readUInt16BE(bytes.length - 2), 0xffd9);
    const oriented = await updatePreview(preview, 'action', { id: 'orientation', orientation: 'landscape' });
    assert.equal(oriented.delivery.orientation, 'landscape');
    const landscape = Buffer.from(await (await requestPreview(preview, '/jpeg')).arrayBuffer());
    assert.ok(readCaptureJpegs('landscape').some(frame => frame.equals(landscape)));
    await selectScenario(preview, 'stopped');
    const stream = await openMediaStream(preview);
    try {
        assert.equal(stream.response.statusCode, 200);
        assert.equal(stream.response.headers['content-type'], 'multipart/x-mixed-replace; boundary=screenstream-jpeg');
        assert.equal(stream.response.headers['content-length'], undefined);
        const prefix = /^--screenstream-jpeg\r\nContent-Type: image\/jpeg\r\nContent-Length: ([0-9]+)\r\n\r\n/;
        let header;
        await waitForCondition(() => { header = prefix.exec(stream.body().toString('latin1')); return !!header; }, 'No complete first multipart header');
        const length = Number(header[1]), start = header[0].length;
        const footer = Buffer.from('\r\n--screenstream-jpeg\r\n');
        // A static frame repeats after one second; its boundary must flush with the first payload.
        await waitForCondition(() => stream.body().length >= start + length + footer.length, 'First part boundary waited for another frame', 500);
        const body = stream.body();
        assert.equal(body.readUInt16BE(start), 0xffd8);
        assert.equal(body.readUInt16BE(start + length - 2), 0xffd9);
        assert.deepEqual(body.subarray(start + length, start + length + footer.length), footer);
    } finally { stream.destroy(); }
    await selectScenario(preview, 'paused');
    await updatePreview(preview, 'options', { postStopImage: 'logo' });
    assert.deepEqual(await readJpegPayload(preview), fs.readFileSync(path.join(__dirname, 'fixtures', 'logo.jpg')));
});

test('capture changes preserve transports and request faults affect only media', async t => {
    const preview = await startPreviewServer(t);
    const configured = await readPreviewState(preview);
    const portrait = readCaptureJpegs('portrait');
    const logo = fs.readFileSync(path.join(__dirname, 'fixtures', 'logo.jpg'));
    const client = connectStateClient(preview);
    assertAuthorizedSnapshot(await client.next(), 'open');
    const stream = await openMediaStream(preview);
    try {
        await waitForCondition(() => {
            const images = parseMultipartImages(stream.body());
            return images.length > 1 && images.some(image => !image.equals(images[0]));
        }, 'Normal MJPEG did not deliver changing complete frames');
        // Deliberately queue a real unchanged keepalive before the next transition.
        await waitForCondition(() => client.messages.length >= 2, 'No state keepalive arrived', 3000);
        await selectScenario(preview, 'paused');
        const paused = assertAuthorizedSnapshot(await client.nextMatching(value => value.notice === 'paused'), 'open');
        assert.equal(paused.notice, 'paused');
        assert.equal(paused.imageKind, 'logo');
        await waitForCondition(() => parseMultipartImages(stream.body()).some(image => image.equals(logo)), 'Paused stream did not show the logo');
        assert.equal(stream.response.destroyed, false);
        const pausedCount = parseMultipartImages(stream.body()).length;
        await selectScenario(preview, 'normal');
        const restored = assertAuthorizedSnapshot(await client.nextMatching(value => value.notice === null && value.imageKind === 'live'), 'open');
        assert.equal(restored.imageKind, 'live');
        await waitForLiveFramesAfter(stream, pausedCount, portrait, 'Paused media did not resume live frames');
        await selectScenario(preview, 'stopped');
        const stopped = assertAuthorizedSnapshot(await client.nextMatching(value => value.notice === 'stopped'), 'open');
        assert.equal(stopped.notice, 'stopped');
        assert.equal(stopped.hasImage, true);
        await selectScenario(preview, 'normal');
        assert.equal(assertAuthorizedSnapshot(await client.nextMatching(value => value.notice === null && value.imageKind === 'live'), 'open').notice, null);
        await updatePreview(preview, 'options', { imageAvailable: false });
        const fallback = assertAuthorizedSnapshot(await client.nextMatching(value => value.imageKind === 'logo'), 'open');
        assert.equal(fallback.notice, null);
        assert.equal(fallback.hasImage, true);
        assert.equal(fallback.imageKind, 'logo');
        assert.deepEqual(await readJpegPayload(preview), logo);
        const fallbackStart = parseMultipartImages(stream.body()).length;
        await waitForCondition(() => parseMultipartImages(stream.body()).slice(fallbackStart).some(image => image.equals(logo)), 'Unavailable stream did not show the logo');
        const missingCount = parseMultipartImages(stream.body()).length;
        await updatePreview(preview, 'options', { imageAvailable: true });
        const available = assertAuthorizedSnapshot(await client.nextMatching(value => value.imageKind === 'live' && value.notice === null), 'open');
        assert.equal(available.imageKind, 'live');
        await waitForLiveFramesAfter(stream, missingCount, portrait, 'Media did not resume live frames after image availability returned');
        const current = await readPreviewState(preview);
        assert.equal(current.delivery.credentials.token, configured.delivery.credentials.token);
        assert.equal(current.connection.clients, 1);
        assert.equal(client.socket.readyState, WebSocket.OPEN);
        assert.equal(stream.response.destroyed, false);
        const beforeFault = assertAuthorizedSnapshot(await readStateSnapshot(preview), 'open');
        const fault = await updatePreview(preview, 'action', { id: 'fail-image-requests', enabled: true });
        assert.equal(fault.media.failRequests, true);
        assert.equal(fault.delivery.imageKind, current.delivery.imageKind);
        assert.equal(fault.delivery.notice, current.delivery.notice);
        assert.deepEqual(assertAuthorizedSnapshot(await readStateSnapshot(preview), 'open'), beforeFault);
        assert.equal(client.socket.readyState, WebSocket.OPEN);
        await waitForCondition(() => stream.response.destroyed, 'Media failure did not terminate the active multipart response');
        for (const path of ['/jpeg', '/mjpeg']) {
            const failed = await requestPreview(preview, path);
            assert.equal(failed.status, 503);
            assert.equal(failed.headers.get('retry-after'), '1');
        }
        const selected = await selectScenario(preview, 'normal');
        assert.equal(selected.media.failRequests, false);
        assert.deepEqual(selected.appearance, current.appearance);
        assert.deepEqual(selected.options, current.options);
        assert.equal(selected.delivery.orientation, current.delivery.orientation);
        assert.equal((await requestPreview(preview, '/jpeg')).status, 200);
        await updatePreview(preview, 'action', { id: 'fail-image-requests', enabled: true });
        assert.equal((await updatePreview(preview, 'reset', {})).media.failRequests, false);
        assert.equal((await requestPreview(preview, '/jpeg')).status, 200);
    } finally { client.socket.terminate(); stream.destroy(); }
});

test('inactive image policies share JPEG, MJPEG and state selection across canonical scenarios', async t => {
    const preview = await startPreviewServer(t);
    const logo = fs.readFileSync(path.join(__dirname, 'fixtures', 'logo.jpg'));
    const portrait = readCaptureJpegs('portrait');
    for (const id of ['normal', 'pin', 'blocked', 'starting', 'permission_required', 'paused', 'stopped', 'failed']) {
        const selected = await selectScenario(preview, id);
        const protectedAccess = selected.delivery.access === 'protected';
        const cookie = protectedAccess ? readAccessCookie(await requestPreview(preview,
            '/viewer?token=' + encodeURIComponent(selected.delivery.credentials.token))) : undefined;
        for (const postStopImage of ['logo', 'last']) for (const imageAvailable of [false, true]) {
            const live = ['normal', 'pin', 'blocked'].includes(id) && imageAvailable;
            const expected = live ? 'live' :
                postStopImage === 'last' && imageAvailable ? 'retained' : 'logo';
            const current = await updatePreview(preview, 'options', { postStopImage, imageAvailable });
            assert.equal(current.delivery.imageKind, expected, id + ': ' + postStopImage + '/' + imageAvailable);
            const wire = assertAuthorizedSnapshot(await readStateSnapshot(preview, cookie), protectedAccess ? 'authorized' : 'open');
            assert.equal(wire.imageKind, expected);
            if (protectedAccess) for (const path of ['/jpeg', '/mjpeg']) {
                assert.equal((await requestPreview(preview, path)).status, 403, 'Access precedes inactive image selection');
            }
            const jpeg = await readJpegPayload(preview, cookie);
            const stream = await openMediaStream(preview, '/mjpeg', cookie);
            try {
                assert.equal(stream.response.statusCode, 200);
                await waitForCondition(() => parseMultipartImages(stream.body()).length > 0, 'No selected multipart image');
                const multipart = parseMultipartImages(stream.body())[0];
                if (expected === 'live') {
                    assert.ok(portrait.some(frame => frame.equals(jpeg)));
                    assert.ok(portrait.some(frame => frame.equals(multipart)));
                } else {
                    assert.deepEqual(jpeg, expected === 'logo' ? logo : portrait[0]);
                    assert.deepEqual(multipart, jpeg);
                }
            } finally { stream.destroy(); }
        }
    }
    await selectScenario(preview, 'stopped');
    await updatePreview(preview, 'options', { postStopImage: 'last', imageAvailable: true });
    await updatePreview(preview, 'action', { id: 'orientation', orientation: 'landscape' });
    assert.deepEqual(await readJpegPayload(preview), readCaptureJpegs('landscape')[0]);
    await delay(250);
    assert.deepEqual(await readJpegPayload(preview), readCaptureJpegs('landscape')[0]);
});

test('close releases live sockets and multipart responses without hanging', async t => {
    const binding = createPreviewServer({ port: 0, watch: false });
    t.after(() => binding.close());
    const pending = binding.listen();
    const closed = binding.close();
    await assert.rejects(pending, /Preview server is closed/);
    await closed;
    assert.equal(binding.server.listening, false, 'Close must wait for a pending bind');
    await assert.rejects(binding.listen(), /Preview server is closed/);

    const preview = await startPreviewServer(t);
    const client = connectStateClient(preview);
    assertAuthorizedSnapshot(await client.next(), 'open');
    const stream = await openMediaStream(preview);
    try {
        let timer;
        const deadline = new Promise((resolve, reject) => { timer = setTimeout(() => reject(new Error('Preview close did not release its transports')), 1500); });
        try { await Promise.race([preview.close(), deadline]); }
        finally { clearTimeout(timer); }
        await client.closed;
        await waitForCondition(() => stream.response.destroyed, 'Response survived server close');
        await preview.close();
    } finally { client.socket.terminate(); stream.destroy(); }
});
