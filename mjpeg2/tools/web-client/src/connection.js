function decodeSnapshot(data) {
    let value;
    try { value = JSON.parse(data); } catch (_) { return null; }
    if (!value || typeof value !== 'object') return null;
    if (value.access === 'pin_required' || value.access === 'blocked') return value;
    const display = value.display;
    const validLayout = layout => layout && (layout.position === 'top' || layout.position === 'bottom') &&
        (layout.presentation === 'overlay' || layout.presentation === 'bar') && typeof layout.autoHide === 'boolean';
    if ((value.access !== 'open' && value.access !== 'authorized') ||
        typeof value.hasImage !== 'boolean' ||
        (value.notice !== null && value.notice !== 'permission_required' && value.notice !== 'stopped' &&
            value.notice !== 'paused' && value.notice !== 'failed') ||
        !display || typeof display.title !== 'string' ||
        typeof display.background !== 'string' || typeof display.titleEnabled !== 'boolean' ||
        typeof display.retainImageOnMediaFailure !== 'boolean' ||
        !validLayout(display.titleLayout) || !validLayout(display.controlsLayout)) return null;
    return value;
}

function createConnection(callbacks) {
    const delays = [0, 1000, 2000, 4000, 8000];
    let socket = null, timer = null;
    let retries = 0, paused = false, pinRequest = null;

    function disposeSocket() {
        clearTimeout(timer);
        timer = null;
        const ws = socket;
        socket = null;
        if (ws) {
            ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
            ws.close();
        }
    }
    function failed(ws) {
        if (ws !== socket || paused) return;
        disposeSocket();
        if (retries >= delays.length) {
            callbacks.state('unavailable');
            return;
        }
        callbacks.state('reconnecting');
        const delay = delays[retries++];
        const retryTimer = setTimeout(() => {
            if (timer !== retryTimer || paused) return;
            timer = null;
            open();
        }, delay);
        timer = retryTimer;
    }
    function open() {
        disposeSocket();
        if (paused) return;
        if (!window.WebSocket) { callbacks.state('unsupported'); return; }
        const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
        let ws;
        try { ws = new WebSocket(url); } catch (_) { failed(null); return; }
        socket = ws;
        const deadline = setTimeout(() => {
            if (timer === deadline) failed(ws);
        }, 10000);
        timer = deadline;
        ws.onmessage = event => {
            if (ws !== socket) return;
            const value = decodeSnapshot(event.data);
            if (!value) { failed(ws); return; }
            if (value.access === 'pin_required' || value.access === 'blocked') {
                retries = 0;
                disposeSocket();
                callbacks.snapshot(value);
                return;
            }
            clearTimeout(timer);
            timer = null;
            retries = 0;
            callbacks.snapshot(value);
        };
        ws.onclose = ws.onerror = () => failed(ws);
    }
    function retry() {
        paused = false;
        retries = 0;
        callbacks.state('connecting');
        open();
    }
    function submitPin(pin) {
        if (pinRequest) return;
        if (!/^[0-9]{6}$/.test(pin)) { callbacks.pin('pinInvalid'); return; }
        const request = new XMLHttpRequest();
        pinRequest = request;
        callbacks.pin('pinSending');
        request.open('POST', '/pin', true);
        request.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        request.timeout = 10000;
        const finish = () => { if (pinRequest !== request) return false; pinRequest = null; return true; };
        request.onload = () => {
            if (!finish()) return;
            if (request.status === 204) { callbacks.pin('success'); retry(); }
            else if (request.status === 403) callbacks.pin('pinWrong');
            else if (request.status === 429) {
                const seconds = parseInt(request.getResponseHeader('Retry-After'), 10);
                callbacks.snapshot({ access: 'blocked', retryAfterMs: (isFinite(seconds) && seconds > 0 ? seconds : 1) * 1000 });
            } else callbacks.pin('pinNetwork');
        };
        request.onerror = request.ontimeout = () => { if (finish()) callbacks.pin('pinNetwork'); };
        request.send('pin=' + encodeURIComponent(pin));
    }
    function pause() {
        paused = true;
        disposeSocket();
        if (pinRequest) { const request = pinRequest; pinRequest = null; request.abort(); }
    }
    return { retry, submitPin, pause, healthy: () => !!socket && socket.readyState === 1 && !timer };
}
