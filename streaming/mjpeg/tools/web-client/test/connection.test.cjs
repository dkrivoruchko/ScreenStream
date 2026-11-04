const assert = require('node:assert/strict');
const { test } = require('node:test');
const { runtime, snapshot } = require('./runtime.cjs');

function connection(t, protocol = 'http:', integrated = false) {
    const sockets = [], requests = [], states = [], snapshots = [], pins = [], models = [], media = [], frames = [];
    const document = { hidden: false };
    class Socket {
        constructor(url) { this.url = url; this.readyState = 0; this.closes = 0; sockets.push(this); }
        close() { this.closes += 1; this.readyState = 3; }
        message(value) { this.data(JSON.stringify(value)); }
        data(value) { this.readyState = 1; this.onmessage({ data: value }); }
    }
    class Request {
        constructor() { this.headers = {}; requests.push(this); }
        open(method, url, async) { Object.assign(this, { method, url, async }); }
        setRequestHeader(key, value) { this.headers[key] = value; }
        send(body) { this.body = body; }
        abort() { this.aborted = true; }
        getResponseHeader(name) { return name.toLowerCase() === 'retry-after' ? this.retryAfter ?? null : null; }
        load(status, retryAfter) { Object.assign(this, { status, retryAfter }); this.onload(); }
    }
    const context = runtime(t, integrated ? ['connection', 'application'] : 'connection', {
        window: { WebSocket: Socket }, WebSocket: Socket, XMLHttpRequest: Request,
        location: { protocol, host: '[::1]:8765' }, document
    });
    let app;
    const client = context.createConnection({
        state: value => { states.push(value); if (app) app.connectionState(value); },
        snapshot: value => { snapshots.push(value); if (app) app.snapshot(value); },
        pin: value => { pins.push(value); if (app) app.pinResult(value); }
    });
    if (integrated) app = context.createApplication({
        connection: client,
        clock: { now: () => Date.now(), setTimeout, clearTimeout },
        media: { apply: (value, refresh) => media.push({ value, refresh }), pause() {}, resume() {} },
        access: { render() {}, resume() {} },
        presentation: {
            render: value => models.push(value), configure() {}, revokeFrame: () => frames.push(null),
            setFrame: value => frames.push(value), accessDenied() {}, closeMenu() {}, pause() {}, resume() {}, activity() {}
        }
    });
    t.after(() => client.pause());
    return { client, app, sockets, requests, states, snapshots, pins, models, media, frames, document, context,
        socket: () => sockets.at(-1), request: () => requests.at(-1) };
}

test('state sockets use the page authority and choose ws or wss without credentials', t => {
    const h = connection(t);
    h.client.retry();
    assert.equal(h.socket().url, 'ws://[::1]:8765/ws');
    h.context.location.protocol = 'https:';
    h.client.retry();
    assert.equal(h.socket().url, 'wss://[::1]:8765/ws');
    assert.equal(h.sockets[0].closes, 1);
    h.context.window.WebSocket = undefined;
    h.client.retry();
    assert.equal(h.states.at(-1), 'unsupported');
    assert.equal(h.sockets.length, 2);
});

test('wire snapshots accept known values and extras without taking presentation validation ownership', t => {
    const h = connection(t);
    const accepted = [
        snapshot(), snapshot({ access: 'authorized', extra: 'future' }),
        snapshot({ imageKind: 'none', hasImage: false }), snapshot({ imageKind: 'logo' }),
        ...['permission_required', 'stopped', 'paused', 'failed'].map(notice => snapshot({ imageKind: 'retained', notice }))
    ];
    const appearance = snapshot();
    appearance.display.background = 'not a color';
    appearance.display.titleLayout = { position: 'bottom', presentation: 'bar', autoHide: false, extra: true };
    accepted.push(appearance);
    for (const value of accepted) {
        h.client.retry();
        const before = h.snapshots.length;
        h.socket().message(value);
        assert.equal(h.snapshots.length, before + 1);
        assert.equal(h.snapshots.at(-1).imageKind, value.imageKind);
        assert.equal(h.client.healthy(), true);
    }
    assert.equal(h.snapshots.at(-1).display.background, 'not a color');
    for (const value of [{ access: 'pin_required', extra: true }, { access: 'blocked' }, { access: 'blocked', retryAfterMs: 'bad' }]) {
        h.client.retry();
        h.socket().message(value);
        assert.equal(h.snapshots.at(-1).access, value.access);
        assert.equal(h.socket().closes, 1);
    }
});

test('invalid wire families reconnect without delivering a partial snapshot', t => {
    const h = connection(t);
    const invalid = ['{', 'null', '[]', '42'];
    const changes = [
        value => { delete value.access; }, value => { value.access = 'future'; },
        value => { value.hasImage = 'true'; }, value => { value.hasImage = false; },
        value => { value.imageKind = 'future'; }, value => { delete value.notice; },
        value => { value.notice = 'future'; }, value => { delete value.display; },
        value => { value.display.title = null; }, value => { value.display.background = 1; },
        value => { value.display.titleEnabled = 1; }, value => { delete value.display.titleLayout; },
        value => { value.display.controlsLayout.position = 'left'; },
        value => { value.display.titleLayout.presentation = 'future'; },
        value => { value.display.controlsLayout.autoHide = 'true'; }
    ];
    for (const change of changes) { const value = snapshot(); change(value); invalid.push(JSON.stringify(value)); }
    for (const data of invalid) {
        h.client.retry();
        const socket = h.socket();
        socket.data(data);
        assert.equal(h.snapshots.length, 0, data);
        assert.equal(socket.closes, 1, data);
        assert.equal(h.states.at(-1), 'reconnecting', data);
    }
});

test('initial deadline and capped reconnect backoff reset after an accepted snapshot', t => {
    const h = connection(t);
    h.client.retry();
    t.mock.timers.tick(9999);
    assert.equal(h.sockets.length, 1);
    t.mock.timers.tick(1);
    t.mock.timers.tick(0);
    assert.equal(h.sockets.length, 2);
    for (const delay of [1000, 2000, 4000, 5000, 5000]) {
        const count = h.sockets.length;
        h.socket().onerror();
        t.mock.timers.tick(delay - 1);
        assert.equal(h.sockets.length, count);
        t.mock.timers.tick(1);
        assert.equal(h.sockets.length, count + 1);
    }
    h.socket().message(snapshot());
    const count = h.sockets.length;
    h.socket().onclose();
    t.mock.timers.tick(0);
    assert.equal(h.sockets.length, count + 1);
});

test('accepted snapshots renew liveness; hidden expiry waits for visibility revalidation', t => {
    const h = connection(t);
    h.client.retry();
    h.socket().message(snapshot());
    t.mock.timers.tick(4999);
    assert.equal(h.client.healthy(), true);
    h.socket().message(snapshot());
    t.mock.timers.tick(4999);
    assert.equal(h.sockets.length, 1);
    t.mock.timers.tick(1);
    t.mock.timers.tick(0);
    assert.equal(h.sockets.length, 2);
    assert.equal(h.client.healthy(), false);
    h.socket().message(snapshot());
    h.document.hidden = true;
    t.mock.timers.tick(5000);
    assert.equal(h.sockets.length, 2);
    assert.equal(h.client.healthy(), false);
    h.document.hidden = false;
    h.client.retry();
    assert.equal(h.sockets.length, 3);
});

test('denied probes use bounded deadlines and stop opening while hidden or paused', t => {
    const h = connection(t);
    for (const [value, delay] of [
        [{ access: 'pin_required' }, 5000], [{ access: 'blocked', retryAfterMs: 1200 }, 1200],
        [{ access: 'blocked', retryAfterMs: 0 }, 100], [{ access: 'blocked', retryAfterMs: 9000 }, 5000],
        [{ access: 'blocked', retryAfterMs: 'bad' }, 5000]
    ]) {
        h.client.retry();
        h.socket().message(value);
        const count = h.sockets.length;
        t.mock.timers.tick(delay - 1);
        assert.equal(h.sockets.length, count);
        t.mock.timers.tick(1);
        assert.equal(h.sockets.length, count + 1);
    }
    h.socket().message({ access: 'pin_required' });
    h.document.hidden = true;
    const count = h.sockets.length;
    t.mock.timers.tick(5000);
    t.mock.timers.tick(5000);
    assert.equal(h.sockets.length, count);
    h.document.hidden = false;
    h.client.retry();
    assert.equal(h.sockets.length, count + 1);
    h.socket().message({ access: 'pin_required' });
    h.client.pause();
    t.mock.timers.tick(10000);
    assert.equal(h.sockets.length, count + 1);
});

test('PIN requests preserve leading zeros, serialize submissions and map wire outcomes', t => {
    const h = connection(t);
    h.client.retry();
    h.socket().message({ access: 'pin_required' });
    for (const invalid of ['', '12345', '1234567', '１２３４５６', '12345a']) h.client.submitPin(invalid);
    assert.equal(h.requests.length, 0);
    assert.equal(h.pins.at(-1), 'pinInvalid');
    h.client.submitPin('012345');
    const request = h.request();
    assert.equal(request.method, 'POST');
    assert.equal(request.url, '/pin');
    assert.equal(request.async, true);
    assert.equal(request.headers['Content-Type'], 'application/x-www-form-urlencoded');
    assert.equal(request.body, 'pin=012345');
    assert.equal(request.timeout, 10000);
    assert.equal(h.pins.at(-1), 'pinSending');
    h.client.submitPin('654321');
    assert.equal(h.requests.length, 1);
    request.load(403);
    assert.equal(h.pins.at(-1), 'pinWrong');
    for (const [status, event] of [[500, 'onload'], [0, 'onerror'], [0, 'ontimeout']]) {
        h.client.submitPin('012345');
        h.request().status = status;
        h.request()[event]();
        assert.equal(h.pins.at(-1), 'pinNetwork');
    }
    for (const [header, milliseconds] of [['12', 12000], [undefined, 1000], ['bad', 1000], ['0', 1000], ['-2', 1000]]) {
        h.client.submitPin('012345');
        h.request().load(429, header);
        assert.equal(h.snapshots.at(-1).access, 'blocked');
        assert.equal(h.snapshots.at(-1).retryAfterMs, milliseconds);
    }
    const count = h.sockets.length;
    h.client.submitPin('012345');
    h.request().load(204);
    assert.equal(h.pins.at(-1), 'success');
    assert.equal(h.sockets.length, count + 1);
    assert.equal(h.states.at(-1), 'connecting');
    assert.equal(h.client.healthy(), false);
});

test('PIN takeover and pause reject late socket and aborted-request callbacks', t => {
    const h = connection(t);
    h.client.retry();
    const socket = h.socket(), oldMessage = socket.onmessage, oldError = socket.onerror;
    h.client.submitPin('012345');
    const request = h.request(), lateLoad = request.onload, lateError = request.onerror;
    oldMessage({ data: JSON.stringify({ access: 'blocked', retryAfterMs: 9000 }) });
    oldError();
    assert.equal(h.snapshots.length, 0);
    assert.equal(h.states.at(-1), 'connecting');
    assert.equal(socket.closes, 1);
    assert.equal(socket.onmessage, null);
    assert.equal(socket.onerror, null);
    h.client.pause();
    assert.equal(request.aborted, true);
    request.status = 204;
    lateLoad();
    lateError();
    t.mock.timers.tick(20000);
    assert.equal(h.sockets.length, 1);
    assert.equal(h.pins.at(-1), 'pinSending');
    h.client.retry();
    h.client.submitPin('012345');
    assert.equal(h.requests.length, 2);
    lateLoad();
    assert.equal(h.pins.at(-1), 'pinSending');
    h.request().load(403);
    assert.equal(h.pins.at(-1), 'pinWrong');
});

test('connection and application require fresh admission after login and preserve frames through reconnect', t => {
    const h = connection(t, 'http:', true), image = {};
    h.app.start();
    h.socket().message({ access: 'pin_required' });
    h.app.submitPin('012345');
    h.request().load(403);
    assert.equal(h.models.at(-1).pin.result.key, 'pinWrong');
    t.mock.timers.tick(5000);
    h.socket().message({ access: 'pin_required' });
    assert.equal(h.models.at(-1).pin.result.key, 'pinWrong');
    h.app.submitPin('012345');
    h.request().load(204);
    assert.equal(h.media.at(-1).value.eligible, false);
    h.app.frame(image, 640, 360);
    assert.equal(h.frames.at(-1), null);
    h.socket().message(snapshot({ access: 'authorized' }));
    assert.equal(h.media.at(-1).value.eligible, true);
    h.app.frame(image, 640, 360);
    h.app.mediaState('viewing');
    h.socket().onclose();
    assert.equal(h.frames.at(-1), image);
    t.mock.timers.tick(0);
    h.socket().message(snapshot({ access: 'authorized' }));
    assert.equal(h.media.at(-1).refresh, true);
    h.socket().message(snapshot({ access: 'authorized' }));
    assert.equal(h.media.at(-1).refresh, false);
    h.socket().message({ access: 'pin_required' });
    assert.equal(h.frames.at(-1), null);
    assert.equal(h.media.at(-1).value.eligible, false);
});
