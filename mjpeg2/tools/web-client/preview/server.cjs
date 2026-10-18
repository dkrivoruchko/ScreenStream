'use strict';

const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const net = require('node:net');
const { WebSocketServer, WebSocket } = require('ws');
const { SCENARIOS } = require('./scenarios.cjs');
const { createDevice, DeviceError } = require('./device.cjs');
const { createViewerBuild } = require('./viewer-build.cjs');

const COOKIE_NAME = 'screenstream_access';
const BOUNDARY = 'screenstream-jpeg';
const STATIC_FILES = new Map([
    ['/', ['index.html', 'text/html; charset=utf-8']],
    ['/__preview/panel.js', ['panel.js', 'application/javascript; charset=utf-8']],
    ['/__preview/panel.css', ['panel.css', 'text/css; charset=utf-8']]
]);

class RequestError extends Error {
    constructor(message, status = 400) { super(message); this.status = status; }
}

function readBody(request, limit) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        let size = 0, settled = false;
        const timeout = setTimeout(() => {
            finish(new RequestError('Request body timed out', 408));
            request.destroy();
        }, 5000);
        function finish(error, body) {
            if (settled) return;
            settled = true;
            clearTimeout(timeout);
            if (error) reject(error); else resolve(body);
        }
        request.on('data', chunk => {
            size += chunk.length;
            if (size > limit) finish(null, null);
            else if (!settled) chunks.push(chunk);
        });
        request.once('end', () => finish(null, Buffer.concat(chunks)));
        request.once('error', error => finish(error));
        request.once('aborted', () => finish(new Error('Request aborted')));
    });
}

function accessCookie(request) {
    const values = (request.headers.cookie || '').split(';').map(value => value.trim())
        .filter(value => value.startsWith(COOKIE_NAME + '='));
    return values.length === 1 ? values[0].slice(COOKIE_NAME.length + 1) : null;
}

async function readJson(request) {
    if ((request.headers['content-type'] || '').split(';')[0].trim().toLowerCase() !== 'application/json') {
        throw new RequestError('Expected application/json', 415);
    }
    const bytes = await readBody(request, 65536);
    if (bytes === null) throw new RequestError('Request body exceeds 65536 bytes', 413);
    let body;
    try { body = JSON.parse(bytes.toString('utf8')); } catch (_) { throw new RequestError('Invalid JSON'); }
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new RequestError('Expected a JSON object');
    return body;
}

function createPreviewServer(options = {}) {
    const host = options.host ?? '127.0.0.1';
    const port = options.port ?? 8765;
    const watch = options.watch !== false;
    if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('Port must be between 0 and 65535');
    if (!net.isIP(host) && host !== 'localhost') throw new Error('Host must be a numeric address or localhost');

    const device = createDevice();
    let failImageRequests = false;
    let heartbeat = null, listenPromise = null, closePromise = null;
    const stateClients = new Map(), mediaRequests = new Set(), logs = [];

    function log(event) {
        logs.push({ time: new Date().toISOString().slice(11, 19), event });
        if (logs.length > 80) logs.shift();
    }
    const viewerBuild = createViewerBuild({ watch, log });
    function state() {
        return {
            ...device.state(),
            media: { activeRequests: mediaRequests.size, failRequests: failImageRequests },
            connection: { clients: stateClients.size },
            build: viewerBuild.state(), logs: logs.slice()
        };
    }
    function publish() {
        const encoded = JSON.stringify(device.snapshot());
        for (const [socket, client] of stateClients) {
            if (client.generation !== device.generation) { socket.terminate(); continue; }
            if (socket.readyState === WebSocket.OPEN && client.lastState !== encoded) {
                client.lastState = encoded;
                socket.send(encoded);
            }
        }
    }
    function closeViewers() {
        for (const socket of sockets.clients) socket.terminate();
        stateClients.clear();
        closeMedia();
    }
    function closeMedia() {
        for (const request of mediaRequests) request.response.destroy();
    }
    function wakeMedia() { for (const request of mediaRequests) request.wake(); }
    function commonHeaders(response, url) {
        response.setHeader('Cache-Control', 'no-store');
        response.setHeader('X-Content-Type-Options', 'nosniff');
        response.setHeader('Referrer-Policy', url.searchParams.has('accessToken') ? 'no-referrer' : 'same-origin');
    }
    function respond(response, status, body = '', contentType = 'text/plain; charset=utf-8') {
        const bytes = Buffer.isBuffer(body) ? body : Buffer.from(body);
        response.writeHead(status, { 'Content-Type': contentType, 'Content-Length': bytes.length });
        response.end(bytes);
    }
    function json(response, status, value) { respond(response, status, JSON.stringify(value), 'application/json; charset=utf-8'); }
    function currentOrigin(request) {
        const authority = request.headers.host;
        const count = request.rawHeaders.filter((_, index) => index % 2 === 0 && request.rawHeaders[index].toLowerCase() === 'host').length;
        if (count !== 1 || !authority) return null;
        try {
            const parsed = new URL('http://' + authority);
            const address = parsed.hostname.replace(/^\[|\]$/g, '');
            const boundPort = server.address()?.port;
            if (parsed.username || parsed.password || parsed.pathname !== '/' || parsed.search || parsed.hash || Number(parsed.port || 80) !== boundPort) return null;
            const loopback = host === '127.0.0.1' || host === '::1' || host === 'localhost';
            const accepted = loopback ? address === host || address === 'localhost' || (host === 'localhost' && ['127.0.0.1', '::1'].includes(address)) :
                host === '0.0.0.0' || host === '::' ? net.isIP(address) !== 0 : address === host;
            return accepted ? parsed.origin : null;
        } catch (_) { return null; }
    }
    function sameOrigin(request, expected, refererAllowed = false) {
        if (request.headers.origin !== undefined) return request.headers.origin === expected;
        if (!refererAllowed || !request.headers.referer) return false;
        try {
            const referer = new URL(request.headers.referer);
            return !referer.username && !referer.password && !referer.hash && referer.origin === expected;
        } catch (_) { return false; }
    }
    function setCookie(response, value) { response.setHeader('Set-Cookie', `${COOKIE_NAME}=${value}; Path=/; HttpOnly; SameSite=Lax`); }

    async function submitPin(request, response, origin) {
        if (!sameOrigin(request, origin, true)) return respond(response, 403);
        const body = await readBody(request, 256);
        let supplied = null;
        if ((request.headers['content-type'] || '').split(';')[0].trim().toLowerCase() === 'application/x-www-form-urlencoded' && body !== null) {
            const encoded = body.toString('utf8');
            try {
                if (/%(?![0-9a-fA-F]{2})/.test(encoded)) throw new Error('Malformed form');
                decodeURIComponent(encoded.replace(/\+/g, ' '));
                const values = new URLSearchParams(encoded).getAll('pin');
                if (values.length === 1 && /^[0-9]{6}$/.test(values[0])) supplied = values[0];
            } catch (_) { /* Malformed input enters ordinary wrong-PIN accounting. */ }
        }
        const result = device.verifyPin(supplied);
        if (result.cookie) setCookie(response, result.cookie);
        if (result.retryAfterMs) response.setHeader('Retry-After', Math.ceil(result.retryAfterMs / 1000));
        log(result.status === 204 ? 'PIN accepted' : result.status === 429 ? 'PIN blocked' : 'PIN rejected');
        respond(response, result.status);
    }
    function serveMedia(request, response, url) {
        if (!device.allowsViewing(accessCookie(request), url.searchParams.get('accessToken'))) return respond(response, 403);
        const initialImage = failImageRequests ? null : device.image();
        if (initialImage === null) {
            response.setHeader('Retry-After', '1');
            return respond(response, 503);
        }
        if (url.pathname === '/jpeg') return respond(response, 200, initialImage, 'image/jpeg');

        const generation = device.generation;
        let timer = null, waitingDrain = false, closed = false;
        let lastImage = null, nextRepeatAt = 0;
        const stream = { response, wake: () => { if (!waitingDrain) schedule(0); } };
        mediaRequests.add(stream);
        const cleanup = () => {
            if (closed) return;
            closed = true;
            clearTimeout(timer);
            response.removeListener('drain', resume);
            mediaRequests.delete(stream);
            log('MJPEG closed');
        };
        response.once('close', cleanup);
        response.once('error', cleanup);
        response.writeHead(200, { 'Content-Type': 'multipart/x-mixed-replace; boundary=' + BOUNDARY });
        response.flushHeaders();
        response.write('--' + BOUNDARY + '\r\n');
        log('MJPEG opened');
        function schedule(delay) {
            if (closed) return;
            clearTimeout(timer);
            timer = setTimeout(writePart, delay);
        }
        function resume() { waitingDrain = false; schedule(0); }
        function writePart() {
            timer = null;
            if (closed) return;
            if (generation !== device.generation) { response.destroy(); return; }
            const payload = device.image();
            if (payload === null) return;
            const now = performance.now();
            if (payload === lastImage && now < nextRepeatAt) { schedule(Math.min(250, nextRepeatAt - now)); return; }
            const header = Buffer.from('Content-Type: image/jpeg\r\nContent-Length: ' + payload.length + '\r\n\r\n');
            const part = Buffer.concat([header, payload, Buffer.from('\r\n--' + BOUNDARY + '\r\n')]);
            lastImage = payload; nextRepeatAt = now + 1000;
            if (response.write(part)) schedule(250);
            else {
                waitingDrain = true;
                response.once('drain', resume);
            }
        }
        schedule(0);
    }

    function applyPreviewChange(endpoint, body) {
        if (endpoint === 'scenario') {
            const accessChanged = device.selectScenario(body.id);
            failImageRequests = false;
            if (accessChanged) closeViewers();
            else { publish(); wakeMedia(); }
            log('Scenario: ' + body.id);
        } else if (endpoint === 'appearance') {
            device.updateAppearance(body); publish(); log('Appearance updated');
        } else if (endpoint === 'options') {
            device.updateOptions(body); publish(); wakeMedia(); log('Device options updated');
        } else if (endpoint === 'action') {
            if (body.id === 'orientation') {
                device.setOrientation(body.orientation); wakeMedia(); log('Orientation: ' + body.orientation);
            } else if (body.id === 'fail-image-requests') {
                if (typeof body.enabled !== 'boolean') throw new RequestError('Media failure must be Boolean');
                failImageRequests = body.enabled;
                if (failImageRequests) closeMedia();
                log('Image request failure ' + (failImageRequests ? 'enabled' : 'disabled'));
            } else throw new RequestError('Unknown action');
        } else if (endpoint === 'reset') {
            device.reset(); failImageRequests = false; closeViewers(); log('Preview reset');
        } else return false;
        return true;
    }

    const server = http.createServer(async (request, response) => {
        try {
            const origin = currentOrigin(request);
            let url;
            try { url = new URL(request.url, origin || 'http://127.0.0.1'); }
            catch (_) { throw new RequestError('Invalid request URL'); }
            commonHeaders(response, url);
            if (!origin) return respond(response, 403);
            if (request.method === 'GET') {
                if (url.pathname === '/viewer') {
                    if (url.searchParams.has('accessToken')) {
                        const exchanged = device.exchangeToken(url.searchParams.get('accessToken'));
                        if (exchanged) setCookie(response, exchanged);
                        response.setHeader('Location', '/viewer');
                        return respond(response, 303);
                    }
                    const template = viewerBuild.template;
                    if (template === null) return respond(response, 503, 'Viewer build failed. See /.');
                    return respond(response, 200, template, 'text/html; charset=utf-8');
                }
                if (url.pathname === '/jpeg' || url.pathname === '/mjpeg') return serveMedia(request, response, url);
                if (url.pathname === '/dev' || url.pathname === '/__preview' || url.pathname === '/__preview/') {
                    response.setHeader('Location', '/'); return respond(response, 303);
                }
                if (url.pathname === '/__preview/api/state') return json(response, 200, state());
                if (url.pathname === '/__preview/api/catalog') return json(response, 200, { scenarios: SCENARIOS });
                const file = STATIC_FILES.get(url.pathname);
                if (file) return respond(response, 200, fs.readFileSync(path.join(__dirname, file[0])), file[1]);
                return respond(response, 404, 'Not found');
            }
            if (request.method === 'POST') {
                if (url.pathname === '/pin') return await submitPin(request, response, origin);
                if (!url.pathname.startsWith('/__preview/api/')) return respond(response, 404, 'Not found');
                if (!sameOrigin(request, origin)) return json(response, 403, { error: 'Same-origin request required' });
                if (!applyPreviewChange(url.pathname.slice('/__preview/api/'.length), await readJson(request))) {
                    return json(response, 404, { error: 'Unknown preview endpoint' });
                }
                return json(response, 200, state());
            }
            respond(response, 405, 'Method not allowed');
        } catch (error) {
            if (response.headersSent || request.aborted) response.destroy();
            else json(response, error instanceof DeviceError ? 400 : error.status || 500, { error: error.message });
        }
    });
    const sockets = new WebSocketServer({ noServer: true, maxPayload: 4096, perMessageDeflate: false });
    sockets.on('headers', (headers, request) => {
        const url = new URL(request.url, 'http://127.0.0.1');
        headers.push('Cache-Control: no-store', 'X-Content-Type-Options: nosniff',
            'Referrer-Policy: ' + (url.searchParams.has('accessToken') ? 'no-referrer' : 'same-origin'));
    });
    server.on('upgrade', (request, socket, head) => {
        const origin = currentOrigin(request);
        let url;
        function reject(status) {
            const referrer = url?.searchParams.has('accessToken') ? 'no-referrer' : 'same-origin';
            socket.end(`HTTP/1.1 ${status}\r\nConnection: close\r\nContent-Length: 0\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: ${referrer}\r\n\r\n`);
        }
        try { url = new URL(request.url, origin || 'http://127.0.0.1'); }
        catch (_) { return reject('400 Bad Request'); }
        if (!origin) return reject('403 Forbidden');
        if (url.pathname !== '/ws') return reject('404 Not Found');
        sockets.handleUpgrade(request, socket, head, ws => {
            ws.on('error', () => {});
            ws.once('close', () => { stateClients.delete(ws); log('WebSocket closed'); });
            if (!sameOrigin(request, origin)) { ws.close(1008, 'Origin rejected'); return; }
            if (!device.allowsViewing(accessCookie(request))) {
                const remaining = device.remainingBlock();
                const denied = remaining > 0 ? { access: 'blocked', retryAfterMs: remaining } : { access: 'pin_required' };
                ws.send(JSON.stringify(denied), () => { if (ws.readyState === WebSocket.OPEN) ws.close(1000, 'Access required'); });
                return;
            }
            const encoded = JSON.stringify(device.snapshot());
            const client = { generation: device.generation, lastState: encoded, alive: true };
            stateClients.set(ws, client);
            ws.on('pong', () => { client.alive = true; });
            ws.on('message', () => ws.close(1008, 'Read-only connection'));
            ws.send(encoded);
            log('WebSocket opened');
        });
    });

    async function listen() {
        if (closePromise) throw new Error('Preview server is closed');
        if (!listenPromise) listenPromise = new Promise((resolve, reject) => {
            function failed(error) { server.removeListener('listening', ready); reject(error); }
            function ready() { server.removeListener('error', failed); resolve(); }
            server.once('error', failed); server.once('listening', ready); server.listen(port, host);
        });
        await listenPromise;
        if (closePromise) throw new Error('Preview server is closed');
        viewerBuild.start();
        if (!heartbeat) heartbeat = setInterval(() => {
            for (const [socket, client] of stateClients) {
                if (!client.alive) socket.terminate();
                else { client.alive = false; socket.ping(); }
            }
        }, 15000);
        const bound = server.address();
        const urlHost = net.isIP(host) === 6 ? '[' + host + ']' : host;
        return { host: bound.address, port: bound.port, url: 'http://' + urlHost + ':' + bound.port };
    }
    function close() {
        if (closePromise) return closePromise;
        viewerBuild.close(); clearInterval(heartbeat);
        closeViewers(); sockets.close();
        closePromise = (async () => {
            // A bind can still be pending even while server.listening is false.
            await listenPromise?.catch(() => {});
            if (server.listening) await new Promise((resolve, reject) => {
                server.close(error => error ? reject(error) : resolve());
                server.closeAllConnections();
            });
        })();
        return closePromise;
    }
    return { server, listen, close };
}

module.exports = { createPreviewServer };

if (require.main === module) {
    const options = {};
    try {
        const args = process.argv.slice(2);
        while (args.length) {
            const argument = args.shift();
            if (argument === '--port') options.port = Number(args.shift());
            else if (argument === '--host') {
                options.host = args.shift();
                if (!options.host) throw new Error('--host requires an address');
            }
            else if (argument === '--no-watch') options.watch = false;
            else throw new Error('Unknown option: ' + argument);
        }
        const preview = createPreviewServer(options);
        preview.listen().then(ready => {
            console.log('SCREENSTREAM_PREVIEW_READY ' + JSON.stringify(ready));
            console.log('Viewer preview: ' + ready.url + '/');
            console.log('Source changes rebuild in memory. Reload the viewer explicitly.');
        }, error => { console.error(error.message); preview.close().finally(() => { process.exitCode = 1; }); });
        for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => {
            preview.close().then(() => { process.exitCode = 0; }, error => { console.error(error.message); process.exitCode = 1; });
        });
    } catch (error) { console.error(error.message); process.exitCode = 1; }
}
