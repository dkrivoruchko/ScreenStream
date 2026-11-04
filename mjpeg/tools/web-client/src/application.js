function isAuthorizedSnapshot(snapshot) {
    return !!snapshot && (snapshot.access === 'open' || snapshot.access === 'authorized');
}

function canLoadScreenImage(snapshot) {
    return isAuthorizedSnapshot(snapshot) && (snapshot.imageKind === 'live' || snapshot.imageKind === 'retained');
}

function desiredMedia(state) {
    return {
        eligible: !state.lifecycle.paused && !state.lifecycle.revalidation && canLoadScreenImage(state.server),
        imageKind: state.server ? state.server.imageKind : 'none',
        method: state.method
    };
}

function connectionStatus(state) {
    if (!state.pin.checking) {
        if (state.pin.visible) return state.pin.mode;
        if (state.server && !isAuthorizedSnapshot(state.server)) return state.server.access;
    }
    return state.transport;
}

function captureNotice(state) {
    return connectionStatus(state) === 'connected' && isAuthorizedSnapshot(state.server) ? state.server.notice : null;
}

function selectPrimary(state) {
    const result = (key, retry, alternate, failed) => ({ key, retry: !!retry, alternate: !!alternate, failed: !!failed });
    const connection = connectionStatus(state);
    if (state.pin.visible || connection === 'pin_required' || connection === 'blocked') return result('');
    if (connection === 'unsupported') return result('unsupported');
    if (connection !== 'connected') return result(connection);
    if (canLoadScreenImage(state.server)) {
        if (state.media.phase === 'failed') return result('mediaFailed', true, true, true);
        if (state.media.phase === 'retrying') return result('mediaRetrying');
    }
    const notice = captureNotice(state);
    if (notice) return result(notice, false, false, notice === 'failed');
    if (!state.media.available && canLoadScreenImage(state.server)) return result('mediaStarting');
    return null;
}

function deriveViewModel(state, now) {
    const primary = selectPrimary(state);
    const status = primary || { key: state.notice.expiresAt > now ? state.notice.key : '', retry: false, alternate: false, failed: false };
    status.access = state.pin.visible;
    status.empty = !state.pin.visible && !state.media.available;
    const connection = connectionStatus(state);
    status.logo = !state.pin.visible && (!isAuthorizedSnapshot(state.server) || connection !== 'connected' || state.server.imageKind === 'logo');
    const imageKeys = { idle: 'unknownSize', starting: 'mediaStarting', retrying: 'mediaRetrying', failed: 'mediaFailed', viewing: 'viewing' };
    return {
        status,
        connection,
        image: imageKeys[state.media.phase],
        notice: captureNotice(state),
        width: state.media.width,
        height: state.media.height,
        method: state.method,
        methodEnabled: canLoadScreenImage(state.server),
        pin: {
            visible: state.pin.visible,
            mode: state.pin.mode,
            seconds: Math.max(0, Math.ceil((state.pin.retryAt - now) / 1000)),
            result: state.pin.result
        }
    };
}

function createApplication(ports) {
    const clock = ports.clock;
    const state = {
        lifecycle: { paused: false, hiddenAt: 0, revalidation: true },
        server: null,
        transport: 'connecting',
        method: 'mjpeg',
        pin: { visible: false, mode: null, retryAt: 0, result: null, checking: true },
        media: { phase: 'idle', available: false, width: 0, height: 0 },
        notice: { key: '', expiresAt: 0 }
    };
    const pending = [];
    let processing = false, started = false, noticeTimer = null, blockTimer = null;

    function clearNotice() {
        clock.clearTimeout(noticeTimer); noticeTimer = null;
        state.notice = { key: '', expiresAt: 0 };
    }
    function render(model) {
        ports.access.render(model.pin);
        ports.presentation.render(model);
    }
    function dispatch(action) {
        pending.push(action);
        if (processing) return;
        processing = true;
        try {
            do {
                while (pending.length) pending.shift()();
                if (selectPrimary(state)) clearNotice();
                render(deriveViewModel(state, clock.now()));
            } while (pending.length);
        } finally { processing = false; }
    }
    function applyMedia(refresh) { ports.media.apply(desiredMedia(state), !!refresh); }
    function blockTick() {
        clock.clearTimeout(blockTimer); blockTimer = null;
        if (state.lifecycle.paused || !state.pin.visible || state.pin.mode !== 'blocked') return;
        if (state.pin.retryAt <= clock.now()) {
            state.pin.mode = 'pin_required';
            state.pin.retryAt = 0;
            state.pin.result = null;
            state.pin.checking = false;
        } else blockTimer = clock.setTimeout(() => dispatch(blockTick), 1000);
    }
    function connect() {
        state.transport = 'connecting';
        state.pin.checking = true;
        ports.connection.retry();
    }
    function connectionState(value) {
        dispatch(() => {
            if (state.lifecycle.paused) return;
            state.transport = value;
            if (value === 'connecting' || value === 'unsupported') state.pin.checking = true;
        });
    }
    function snapshot(value) {
        dispatch(() => {
            if (state.lifecycle.paused) return;
            const firstSnapshot = state.transport !== 'connected';
            state.server = value;
            if (!isAuthorizedSnapshot(value)) {
                const samePanel = state.pin.visible && state.pin.mode === value.access;
                const previousResult = state.pin.result;
                state.transport = 'reconnecting';
                state.lifecycle.revalidation = true;
                state.pin = {
                    visible: true,
                    mode: value.access,
                    checking: false,
                    retryAt: value.access === 'blocked' ? clock.now() + Math.max(0, isFinite(value.retryAfterMs) ? value.retryAfterMs : 1000) : 0,
                    result: samePanel && value.access === 'pin_required' && previousResult && previousResult.key !== 'pinNetwork' ? previousResult : null
                };
                ports.presentation.revokeFrame();
                applyMedia(false);
                ports.presentation.accessDenied();
                blockTick();
            } else {
                // Reconnect must retry media even when the server snapshot is unchanged.
                state.transport = 'connected';
                state.lifecycle.revalidation = false;
                state.pin = { visible: false, mode: null, retryAt: 0, result: null, checking: false };
                clock.clearTimeout(blockTimer); blockTimer = null;
                ports.presentation.configure(value.display, firstSnapshot);
                if (!canLoadScreenImage(value)) ports.presentation.revokeFrame();
                applyMedia(firstSnapshot);
            }
        });
    }
    function pinResult(value) {
        dispatch(() => {
            if (state.lifecycle.paused) return;
            state.pin.result = { key: value };
            if (value === 'success') {
                state.pin.visible = false;
                state.pin.mode = null;
                state.pin.retryAt = 0;
                clock.clearTimeout(blockTimer); blockTimer = null;
            }
        });
    }
    function mediaState(value) { dispatch(() => { state.media.phase = value; }); }
    function frame(value, width, height) {
        dispatch(() => {
            if (value && !desiredMedia(state).eligible) return;
            state.media.available = !!value && width > 0 && height > 0;
            state.media.width = width;
            state.media.height = height;
            ports.presentation.setFrame(value, width, height);
        });
    }
    function notice(key) {
        dispatch(() => {
            if (selectPrimary(state)) { clearNotice(); return; }
            state.notice = { key, expiresAt: clock.now() + 10000 };
            clock.clearTimeout(noticeTimer);
            noticeTimer = clock.setTimeout(() => dispatch(clearNotice), 10000);
        });
    }
    function retry() {
        dispatch(() => {
            if (state.lifecycle.paused) return;
            ports.presentation.closeMenu(false);
            connect();
        });
    }
    function submitPin(pin) {
        dispatch(() => {
            if (!state.lifecycle.paused && state.pin.visible && state.pin.mode !== 'blocked' &&
                (!state.pin.result || state.pin.result.key !== 'pinSending')) ports.connection.submitPin(pin);
        });
    }
    function selectMethod(value, closeMenu) {
        dispatch(() => {
            if (state.lifecycle.paused) return;
            if ((value === 'mjpeg' || value === 'jpeg') && value !== state.method) {
                state.method = value;
                applyMedia(false);
            }
            if (closeMenu) ports.presentation.closeMenu(true);
        });
    }
    function pause() {
        dispatch(() => {
            if (state.lifecycle.paused) return;
            state.lifecycle.paused = true;
            state.lifecycle.revalidation = true;
            if (state.pin.result && state.pin.result.key === 'pinSending') state.pin.result = null;
            clock.clearTimeout(blockTimer); blockTimer = null;
            clearNotice();
            ports.presentation.revokeFrame();
            ports.connection.pause();
            ports.media.pause();
            ports.presentation.pause();
        });
    }
    function resume() {
        dispatch(() => {
            if (!state.lifecycle.paused) return;
            state.lifecycle.paused = false;
            connect();
            ports.presentation.resume();
            ports.access.resume();
            dispatch(blockTick);
        });
    }
    function visibility(hidden) {
        dispatch(() => {
            if (hidden) { state.lifecycle.hiddenAt = clock.now(); return; }
            const stale = state.lifecycle.hiddenAt && clock.now() - state.lifecycle.hiddenAt > 30000;
            state.lifecycle.hiddenAt = 0;
            if (state.lifecycle.paused) resume();
            else {
                if (stale || !ports.connection.healthy()) connect();
                else ports.media.resume();
                ports.presentation.activity();
            }
        });
    }
    function start() {
        if (started) return;
        started = true;
        dispatch(() => {});
        dispatch(connect);
    }
    return { start, connectionState, snapshot, pinResult, mediaState, frame, notice, retry, submitPin,
        selectMethod, switchMethod: () => selectMethod(state.method === 'mjpeg' ? 'jpeg' : 'mjpeg', true), pause, resume, visibility };
}
