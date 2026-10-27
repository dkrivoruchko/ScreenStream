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
        (value.imageKind !== 'none' && value.imageKind !== 'logo' && value.imageKind !== 'live' && value.imageKind !== 'retained') ||
        value.hasImage !== (value.imageKind !== 'none') ||
        (value.notice !== null && value.notice !== 'permission_required' && value.notice !== 'stopped' &&
            value.notice !== 'paused' && value.notice !== 'failed') ||
        !display || typeof display.title !== 'string' ||
        typeof display.background !== 'string' || typeof display.titleEnabled !== 'boolean' ||
        !validLayout(display.titleLayout) || !validLayout(display.controlsLayout)) return null;
    return value;
}

function createConnection(callbacks) {
    const delays = [0, 1000, 2000, 4000, 5000];
    const livenessTimeout = 5000;
    const admissionInterval = 5000;
    let socket = null, timer = null, livenessTimer = null;
    let lastSnapshotAt = 0;
    let retries = 0, paused = false, pinRequest = null, accessDenied = false;

    function disposeSocket() {
        clearTimeout(timer);
        clearTimeout(livenessTimer);
        timer = livenessTimer = null;
        lastSnapshotAt = 0;
        const ws = socket;
        socket = null;
        if (ws) {
            ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
            ws.close();
        }
    }
    function schedule(delay) {
        const retryTimer = setTimeout(() => {
            if (timer !== retryTimer || paused) return;
            timer = null;
            open();
        }, delay);
        timer = retryTimer;
    }
    function denied(value) {
        accessDenied = true;
        retries = 0;
        disposeSocket();
        callbacks.snapshot(value);
        // Denied sockets remain short-lived; only a foreground page rechecks admission.
        const remaining = value.access === 'blocked' && isFinite(value.retryAfterMs) ? value.retryAfterMs : admissionInterval;
        schedule(Math.max(100, Math.min(admissionInterval, remaining)));
    }
    function failed(ws) {
        if (ws !== socket || paused) return;
        disposeSocket();
        if (!accessDenied) callbacks.state('reconnecting');
        const delay = delays[retries];
        retries = Math.min(retries + 1, delays.length - 1);
        schedule(delay);
    }
    function open() {
        disposeSocket();
        if (paused || pinRequest || accessDenied && document.hidden) return;
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
                denied(value);
                return;
            }
            accessDenied = false;
            clearTimeout(timer);
            timer = null;
            retries = 0;
            lastSnapshotAt = Date.now();
            clearTimeout(livenessTimer);
            livenessTimer = setTimeout(() => {
                livenessTimer = null;
                // Background throttling is revalidated by the page's visibility-return path.
                if (ws === socket && !paused && !document.hidden) failed(ws);
            }, livenessTimeout);
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
        // Do not let an older admission reply race the explicit login result.
        disposeSocket();
        const request = new XMLHttpRequest();
        pinRequest = request;
        callbacks.pin('pinSending');
        request.open('POST', '/pin', true);
        request.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        request.timeout = 10000;
        const finish = () => {
            if (pinRequest !== request) return false;
            pinRequest = null;
            if (accessDenied) schedule(admissionInterval);
            return true;
        };
        request.onload = () => {
            if (!finish()) return;
            if (request.status === 204) { callbacks.pin('success'); retry(); }
            else if (request.status === 403) callbacks.pin('pinWrong');
            else if (request.status === 429) {
                const seconds = parseInt(request.getResponseHeader('Retry-After'), 10);
                denied({ access: 'blocked', retryAfterMs: (isFinite(seconds) && seconds > 0 ? seconds : 1) * 1000 });
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
    return { retry, submitPin, pause,
        healthy: () => !!socket && socket.readyState === 1 && lastSnapshotAt > 0 && Date.now() - lastSnapshotAt < livenessTimeout };
}
