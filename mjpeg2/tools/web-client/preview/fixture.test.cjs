const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { EventEmitter } = require('node:events');
const { setTimeout: delay } = require('node:timers/promises');
const { test: nodeTest } = require('node:test');
const WebSocket = require('ws');
const { buildHtml, inputPaths } = require('../build.cjs');
const { createPreviewServer } = require('./server.cjs');
const { createViewerBuild } = require('./viewer-build.cjs');
const test = (name, body) => nodeTest(name, { timeout: 10000 }, body);

// Exercise the fixture through real HTTP/WS, independently of the viewer's helpers.
async function fixture(t) {
    const instance = createPreviewServer({ port: 0, watch: false });
    const address = await instance.listen();
    t.after(() => instance.close());
    return { ...instance, ...address };
}

async function request(device, path, options = {}) {
    return fetch(device.url + path, {
        redirect: 'manual',
        signal: AbortSignal.timeout(3000),
        ...options,
        headers: { Origin: device.url, ...options.headers }
    });
}

async function state(device) {
    const response = await request(device, '/__preview/api/state');
    assert.equal(response.status, 200);
    return response.json();
}

async function update(device, path, body) {
    const response = await request(device, '/__preview/api/' + path, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    });
    assert.equal(response.status, 200, await response.clone().text());
    return response.json();
}

function scenario(device, id) { return update(device, 'scenario', { id }); }

function pin(device, value, cookie) {
    return request(device, '/pin', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded', ...(cookie ? { Cookie: cookie } : {}) },
        body: 'pin=' + encodeURIComponent(value)
    });
}

function accessCookie(response) {
    const value = response.headers.get('set-cookie');
    assert.match(value || '', /^screenstream_access=[^;]+; Path=\/; HttpOnly; SameSite=Lax$/);
    return value.split(';')[0];
}

function authorizedSnapshot(value, access) {
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

function socketClient(device, cookie) {
    const socket = new WebSocket(device.url.replace('http:', 'ws:') + '/ws', {
        origin: device.url, headers: cookie ? { Cookie: cookie } : {}
    });
    const messages = [], waiters = [];
    let cursor = 0, failure = null, ended = false;
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
        next() {
            if (cursor < messages.length) return Promise.resolve(JSON.parse(messages[cursor++]));
            if (failure || ended) return Promise.reject(failure || new Error('WebSocket is closed'));
            return new Promise((resolve, reject) => {
                const waiter = { resolve: value => { clearTimeout(timer); resolve(value); }, reject: error => { clearTimeout(timer); reject(error); } };
                const timer = setTimeout(() => {
                    const index = waiters.indexOf(waiter);
                    if (index >= 0) waiters.splice(index, 1);
                    reject(new Error('Timed out waiting for a WebSocket snapshot'));
                }, 2000);
                waiters.push(waiter);
            });
        }
    };
}

async function snapshot(device, cookie) {
    const client = socketClient(device, cookie);
    try { return await client.next(); }
    finally { client.socket.terminate(); }
}

function mediaStream(device, path = '/mjpeg', cookie) {
    return new Promise((resolve, reject) => {
        const request = http.get(device.url + path, { headers: cookie ? { Cookie: cookie } : {} });
        request.once('error', reject);
        request.once('response', response => {
            const chunks = [];
            response.on('data', data => chunks.push(data));
            response.on('error', () => {});
            resolve({ request, response, body: () => Buffer.concat(chunks), destroy: () => { response.destroy(); request.destroy(); } });
        });
    });
}

async function until(predicate, message, timeout = 2000) {
    const deadline = Date.now() + timeout;
    while (!predicate()) {
        if (Date.now() >= deadline) assert.fail(message);
        await delay(5);
    }
}

async function jpegPayload(device, cookie) {
    const response = await request(device, '/jpeg', { headers: cookie ? { Cookie: cookie } : {} });
    assert.equal(response.status, 200);
    return Buffer.from(await response.arrayBuffer());
}

function captureJpegs(orientation) {
    return Array.from({ length: 8 }, (_, frame) => fs.readFileSync(path.join(__dirname, 'fixtures', orientation + '-' + frame + '.jpg')));
}

function multipartImages(body) {
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

test('root serves the panel and viewer serves the unchanged shared build', async t => {
    const device = await fixture(t);
    const panel = await request(device, '/');
    assert.equal(panel.status, 200);
    const panelHtml = await panel.text();
    assert.match(panelHtml, /id="viewer"/);
    assert.match(panelHtml, /href="\/viewer"/);
    assert.match(panelHtml, /src="\/__preview\/panel\.js"/);
    assert.match(panelHtml, /href="\/__preview\/panel\.css"/);
    const initial = await state(device);
    const invalid = await request(device, '/__preview/api/appearance', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ background: '#123456; </style><script>invalid</script>' })
    });
    assert.equal(invalid.status, 400);
    assert.equal((await state(device)).appearance.background, initial.appearance.background);
    const appearance = { ...initial.appearance, background: '#a1B2c3', title: 'Private fixture title <not HTML>' };
    await update(device, 'appearance', appearance);
    await scenario(device, 'pin');
    const response = await request(device, '/viewer');
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
    const html = await response.text();
    const current = await state(device);
    assert.ok(html === buildHtml(), 'Viewer differs from the shared build; finish source edits before running checks');
    assert.ok(!html.includes(appearance.background));
    assert.ok(!html.includes(appearance.title));
    const denied = socketClient(device);
    assert.deepEqual(await denied.next(), { access: 'pin_required' });
    assert.equal((await denied.closed).code, 1000);
    const catalog = await (await request(device, '/__preview/api/catalog')).json();
    assert.deepEqual(catalog.scenarios.map(value => value.id), ['normal', 'pin', 'blocked', 'starting', 'permission_required', 'paused', 'stopped', 'failed']);
    assert.equal(Object.hasOwn(catalog, 'actions'), false);
    assert.equal(current.build.error, null);
    assert.ok(current.build.bytes > 0);
});

test('scenario pairs are canonical, preserve settings and fully reset to defaults', async t => {
    const device = await fixture(t);
    const defaults = (await state(device)).appearance;
    const appearance = { ...defaults, background: '#234567', title: 'Preset order check' };
    await update(device, 'appearance', appearance);
    assert.deepEqual((await state(device)).options, { postStopImage: 'logo', imageAvailable: true });
    const options = { postStopImage: 'last', imageAvailable: true };
    await update(device, 'options', options);
    await update(device, 'action', { id: 'orientation', orientation: 'landscape' });
    const ids = ['normal', 'pin', 'blocked', 'starting', 'permission_required', 'paused', 'stopped', 'failed'];
    const landscape = captureJpegs('landscape');
    for (const previous of ids) for (const id of ids) {
        await scenario(device, previous);
        const current = await scenario(device, id);
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
        const wire = await snapshot(device);
        if (protectedAccess) assert.equal(wire.access, id === 'pin' ? 'pin_required' : 'blocked');
        else {
            authorizedSnapshot(wire, 'open');
            assert.equal(wire.imageKind, imageKind);
            assert.equal(wire.notice, notice);
            assert.deepEqual(wire.display, appearance);
        }
        const jpeg = await request(device, '/jpeg' + (protectedAccess ? '?accessToken=' + encodeURIComponent(current.delivery.credentials.accessToken) : ''));
        assert.equal(jpeg.status, 200);
        const bytes = Buffer.from(await jpeg.arrayBuffer());
        if (live) assert.ok(landscape.some(frame => frame.equals(bytes)));
        else assert.deepEqual(bytes, landscape[0]);
    }
    const normal = await scenario(device, 'normal');
    assert.equal(normal.delivery.notice, null);
    assert.equal(normal.delivery.hasImage, true);
    assert.equal((await request(device, '/jpeg')).status, 200);
    await update(device, 'options', { imageAvailable: false });
    for (const id of ['normal', 'pin', 'blocked']) {
        const unavailable = await scenario(device, id);
        assert.equal(unavailable.delivery.notice, null);
        assert.equal(unavailable.delivery.imageKind, 'logo');
        const token = unavailable.delivery.credentials.accessToken;
        assert.deepEqual(Buffer.from(await (await request(device, '/jpeg?accessToken=' + encodeURIComponent(token))).arrayBuffer()),
            fs.readFileSync(path.join(__dirname, 'fixtures', 'logo.jpg')));
    }
    await update(device, 'options', { postStopImage: 'last', imageAvailable: false });
    await scenario(device, 'blocked');
    const denied = await snapshot(device);
    assert.deepEqual(Object.keys(denied).sort(), ['access', 'retryAfterMs']);
    assert.equal(denied.access, 'blocked');
    assert.ok(denied.retryAfterMs > 0);
    await update(device, 'action', { id: 'orientation', orientation: 'landscape' });
    const reset = await update(device, 'reset', {});
    assert.equal(reset.scenario.id, 'normal');
    assert.equal(reset.delivery.access, 'open');
    assert.equal(reset.delivery.orientation, 'portrait');
    assert.equal(reset.delivery.blockedRemainingMs, 0);
    assert.deepEqual(reset.appearance, defaults);
    assert.deepEqual(reset.options, { postStopImage: 'logo', imageAvailable: true });
});

test('PIN cookies are reused and remain valid during a peer block', async t => {
    const device = await fixture(t);
    const configured = await scenario(device, 'pin');
    const correct = configured.delivery.credentials.pin;
    const first = await pin(device, correct);
    assert.equal(first.status, 204);
    const cookie = accessCookie(first);
    const second = await pin(device, correct);
    assert.equal(second.status, 204);
    assert.equal(accessCookie(second), cookie);
    const wrong = correct === '000000' ? '111111' : '000000';
    for (let attempt = 1; attempt <= 5; attempt += 1) {
        const response = await pin(device, wrong);
        assert.equal(response.status, attempt < 5 ? 403 : 429);
        if (attempt === 5) {
            const remaining = Number(response.headers.get('retry-after'));
            assert.ok(remaining > 0 && remaining <= 20);
        }
    }
    assert.equal((await pin(device, correct)).status, 429);
    assert.equal((await snapshot(device)).access, 'blocked');
    authorizedSnapshot(await snapshot(device, cookie), 'authorized');
    assert.equal((await request(device, '/jpeg', { headers: { Cookie: cookie } })).status, 200);
});

test('token exchange and access scenario boundaries revoke old credentials', async t => {
    const device = await fixture(t);
    const configured = await scenario(device, 'pin');
    const token = configured.delivery.credentials.accessToken;
    for (const supplied of ['', 'invalid']) {
        const denied = await request(device, '/viewer?accessToken=' + supplied);
        assert.equal(denied.status, 303);
        assert.equal(denied.headers.get('location'), '/viewer');
        assert.equal(denied.headers.get('set-cookie'), null);
        assert.equal(denied.headers.get('referrer-policy'), 'no-referrer');
    }
    const exchanged = await request(device, '/viewer?accessToken=' + encodeURIComponent(token));
    assert.equal(exchanged.status, 303);
    assert.equal(exchanged.headers.get('location'), '/viewer');
    const cookie = accessCookie(exchanged);
    assert.equal(accessCookie(await pin(device, configured.delivery.credentials.pin)), cookie);
    assert.equal((await request(device, '/jpeg?accessToken=' + encodeURIComponent(token))).status, 200);
    authorizedSnapshot(await snapshot(device, cookie), 'authorized');
    await update(device, 'action', { id: 'fail-image-requests', enabled: true });
    for (const path of ['/jpeg', '/mjpeg']) {
        assert.equal((await request(device, path)).status, 403, 'Authorization must precede media fault injection');
        assert.equal((await request(device, path, { headers: { Cookie: cookie } })).status, 503);
    }
    assert.equal(authorizedSnapshot(await snapshot(device, cookie), 'authorized').hasImage, true);
    await update(device, 'reset', {});
    const rotated = await scenario(device, 'pin');
    assert.notEqual(rotated.delivery.credentials.accessToken, token);
    assert.equal((await request(device, '/jpeg', { headers: { Cookie: cookie } })).status, 403);
    assert.deepEqual(await snapshot(device, cookie), { access: 'pin_required' });
    assert.equal((await request(device, '/viewer?accessToken=' + encodeURIComponent(token))).headers.get('set-cookie'), null);
    const currentCookie = accessCookie(await pin(device, rotated.delivery.credentials.pin));
    const admitted = socketClient(device, currentCookie);
    authorizedSnapshot(await admitted.next(), 'authorized');
    await scenario(device, 'blocked');
    await admitted.closed;
    const denied = socketClient(device, currentCookie);
    assert.equal((await denied.next()).access, 'blocked');
    assert.equal((await denied.closed).code, 1000);
    const capture = await scenario(device, 'permission_required');
    assert.equal(capture.delivery.access, 'open');
    assert.equal(capture.delivery.blockedRemainingMs, 0);
    const resumed = socketClient(device);
    try {
        const pending = authorizedSnapshot(await resumed.next(), 'open');
        assert.equal(pending.notice, 'permission_required');
        assert.equal(pending.imageKind, 'logo');
        await scenario(device, 'normal');
        assert.equal(authorizedSnapshot(await resumed.next(), 'open').notice, null);
        assert.equal(resumed.socket.readyState, WebSocket.OPEN);
        assert.equal((await request(device, '/jpeg')).status, 200);
    } finally { resumed.socket.terminate(); }
});

test('JPEG orientation, length and multipart bytes match the delivery framing', async t => {
    const device = await fixture(t);
    const jpeg = await request(device, '/jpeg');
    const bytes = Buffer.from(await jpeg.arrayBuffer());
    assert.equal(jpeg.headers.get('content-type'), 'image/jpeg');
    assert.equal(Number(jpeg.headers.get('content-length')), bytes.length);
    assert.equal(bytes.readUInt16BE(0), 0xffd8);
    assert.equal(bytes.readUInt16BE(bytes.length - 2), 0xffd9);
    const oriented = await update(device, 'action', { id: 'orientation', orientation: 'landscape' });
    assert.equal(oriented.delivery.orientation, 'landscape');
    const landscape = Buffer.from(await (await request(device, '/jpeg')).arrayBuffer());
    assert.ok(captureJpegs('landscape').some(frame => frame.equals(landscape)));
    await scenario(device, 'stopped');
    const stream = await mediaStream(device);
    try {
        assert.equal(stream.response.statusCode, 200);
        assert.equal(stream.response.headers['content-type'], 'multipart/x-mixed-replace; boundary=screenstream-jpeg');
        assert.equal(stream.response.headers['content-length'], undefined);
        const prefix = /^--screenstream-jpeg\r\nContent-Type: image\/jpeg\r\nContent-Length: ([0-9]+)\r\n\r\n/;
        let header;
        await until(() => { header = prefix.exec(stream.body().toString('latin1')); return !!header; }, 'No complete first multipart header');
        const length = Number(header[1]), start = header[0].length;
        const footer = Buffer.from('\r\n--screenstream-jpeg\r\n');
        // A static frame repeats after one second; its boundary must flush with the first payload.
        await until(() => stream.body().length >= start + length + footer.length, 'First part boundary waited for another frame', 500);
        const body = stream.body();
        assert.equal(body.readUInt16BE(start), 0xffd8);
        assert.equal(body.readUInt16BE(start + length - 2), 0xffd9);
        assert.deepEqual(body.subarray(start + length, start + length + footer.length), footer);
    } finally { stream.destroy(); }
    await scenario(device, 'paused');
    await update(device, 'options', { postStopImage: 'logo' });
    assert.deepEqual(await jpegPayload(device), fs.readFileSync(path.join(__dirname, 'fixtures', 'logo.jpg')));
});

test('capture changes preserve transports and request faults affect only media', async t => {
    const device = await fixture(t);
    const configured = await state(device);
    const client = socketClient(device);
    authorizedSnapshot(await client.next(), 'open');
    const stream = await mediaStream(device);
    try {
        await until(() => {
            const images = multipartImages(stream.body());
            return images.length > 1 && images.some(image => !image.equals(images[0]));
        }, 'Normal MJPEG did not deliver changing complete frames');
        await scenario(device, 'paused');
        const paused = authorizedSnapshot(await client.next(), 'open');
        assert.equal(paused.notice, 'paused');
        assert.equal(paused.imageKind, 'logo');
        await delay(250);
        assert.equal(stream.response.destroyed, false);
        const pausedCount = multipartImages(stream.body()).length;
        await scenario(device, 'normal');
        assert.equal(authorizedSnapshot(await client.next(), 'open').hasImage, true);
        await until(() => multipartImages(stream.body()).length > pausedCount, 'Paused media did not resume');
        await scenario(device, 'stopped');
        const stopped = authorizedSnapshot(await client.next(), 'open');
        assert.equal(stopped.notice, 'stopped');
        assert.equal(stopped.hasImage, true);
        await scenario(device, 'normal');
        assert.equal(authorizedSnapshot(await client.next(), 'open').notice, null);
        await update(device, 'options', { imageAvailable: false });
        const fallback = authorizedSnapshot(await client.next(), 'open');
        assert.equal(fallback.notice, null);
        assert.equal(fallback.hasImage, true);
        assert.equal(fallback.imageKind, 'logo');
        assert.deepEqual(await jpegPayload(device), fs.readFileSync(path.join(__dirname, 'fixtures', 'logo.jpg')));
        const missingCount = multipartImages(stream.body()).length;
        await update(device, 'options', { imageAvailable: true });
        assert.equal(authorizedSnapshot(await client.next(), 'open').hasImage, true);
        await until(() => multipartImages(stream.body()).length > missingCount, 'Media did not resume after image availability returned');
        const current = await state(device);
        assert.equal(current.delivery.credentials.accessToken, configured.delivery.credentials.accessToken);
        assert.equal(current.connection.clients, 1);
        assert.equal(client.socket.readyState, WebSocket.OPEN);
        assert.equal(stream.response.destroyed, false);
        const fault = await update(device, 'action', { id: 'fail-image-requests', enabled: true });
        assert.equal(fault.media.failRequests, true);
        assert.equal(fault.delivery.hasImage, true);
        assert.equal(authorizedSnapshot(await snapshot(device), 'open').hasImage, true);
        assert.equal(client.socket.readyState, WebSocket.OPEN);
        await until(() => stream.response.destroyed, 'Media failure did not terminate the active multipart response');
        for (const path of ['/jpeg', '/mjpeg']) {
            const failed = await request(device, path);
            assert.equal(failed.status, 503);
            assert.equal(failed.headers.get('retry-after'), '1');
        }
        const selected = await scenario(device, 'normal');
        assert.equal(selected.media.failRequests, false);
        assert.deepEqual(selected.appearance, current.appearance);
        assert.deepEqual(selected.options, current.options);
        assert.equal(selected.delivery.orientation, current.delivery.orientation);
        assert.equal((await request(device, '/jpeg')).status, 200);
        await update(device, 'action', { id: 'fail-image-requests', enabled: true });
        assert.equal((await update(device, 'reset', {})).media.failRequests, false);
        assert.equal((await request(device, '/jpeg')).status, 200);
    } finally { client.socket.terminate(); stream.destroy(); }
});

test('inactive image policies share JPEG, MJPEG and state selection across canonical scenarios', async t => {
    const device = await fixture(t);
    const logo = fs.readFileSync(path.join(__dirname, 'fixtures', 'logo.jpg'));
    const portrait = captureJpegs('portrait');
    for (const id of ['normal', 'pin', 'blocked', 'starting', 'permission_required', 'paused', 'stopped', 'failed']) {
        const selected = await scenario(device, id);
        const protectedAccess = selected.delivery.access === 'protected';
        const cookie = protectedAccess ? accessCookie(await request(device,
            '/viewer?accessToken=' + encodeURIComponent(selected.delivery.credentials.accessToken))) : undefined;
        for (const postStopImage of ['logo', 'last']) for (const imageAvailable of [false, true]) {
            const live = ['normal', 'pin', 'blocked'].includes(id) && imageAvailable;
            const expected = live ? 'live' :
                postStopImage === 'last' && imageAvailable ? 'retained' : 'logo';
            const current = await update(device, 'options', { postStopImage, imageAvailable });
            assert.equal(current.delivery.imageKind, expected, id + ': ' + postStopImage + '/' + imageAvailable);
            const wire = authorizedSnapshot(await snapshot(device, cookie), protectedAccess ? 'authorized' : 'open');
            assert.equal(wire.imageKind, expected);
            if (protectedAccess) for (const path of ['/jpeg', '/mjpeg']) {
                assert.equal((await request(device, path)).status, 403, 'Access precedes inactive image selection');
            }
            const jpeg = await jpegPayload(device, cookie);
            const stream = await mediaStream(device, '/mjpeg', cookie);
            try {
                assert.equal(stream.response.statusCode, 200);
                await until(() => multipartImages(stream.body()).length > 0, 'No selected multipart image');
                const multipart = multipartImages(stream.body())[0];
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
    await scenario(device, 'stopped');
    await update(device, 'options', { postStopImage: 'last', imageAvailable: true });
    await update(device, 'action', { id: 'orientation', orientation: 'landscape' });
    assert.deepEqual(await jpegPayload(device), captureJpegs('landscape')[0]);
    await delay(250);
    assert.deepEqual(await jpegPayload(device), captureJpegs('landscape')[0]);
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

    const device = await fixture(t);
    const client = socketClient(device);
    authorizedSnapshot(await client.next(), 'open');
    const stream = await mediaStream(device);
    try {
        let timer;
        const deadline = new Promise((resolve, reject) => { timer = setTimeout(() => reject(new Error('Preview close did not release its transports')), 1500); });
        try { await Promise.race([device.close(), deadline]); }
        finally { clearTimeout(timer); }
        await client.closed;
        await until(() => stream.response.destroyed, 'Response survived server close');
        await device.close();
    } finally { client.socket.terminate(); stream.destroy(); }
});

test('viewer builds cover all inputs, retain the last good build and release fallback polling', async t => {
    const originalRead = fs.readFileSync;
    const source = inputPaths[0], logo = inputPaths.at(-1);
    let sourceVersion = 0, logoVersion = 0, invalidSource = false;
    const watchers = [];
    t.mock.method(fs, 'readFileSync', (input, ...args) => {
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
    sourceVersion = 3;
    await delay(1200);
    assert.equal(build.state().revision, 4, 'Closed build continued polling');
});
