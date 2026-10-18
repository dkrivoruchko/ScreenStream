function createPictureInPicture(pip, video, callbacks) {
    let image = null, currentAttempt = null, pendingOperation = null;
    const pipSupported = !!(window.HTMLCanvasElement && HTMLCanvasElement.prototype.captureStream &&
        video.requestPictureInPicture && 'srcObject' in video && document.pictureInPictureEnabled);
    pip.hidden = !pipSupported;
    pip.disabled = true;
    function updateButton() { pip.disabled = !image || pendingOperation !== null; }
    function isCurrent(attempt) { return currentAttempt === attempt; }
    function release(attempt) {
        if (attempt.phase === 'released') return;
        if (isCurrent(attempt)) currentAttempt = null;
        attempt.phase = 'released';
        clearTimeout(attempt.paintTimer); clearTimeout(attempt.prepareTimer);
        attempt.paintTimer = attempt.prepareTimer = null;
        if (attempt.stream && video.srcObject === attempt.stream) {
            video.onloadedmetadata = null;
            video.pause(); video.srcObject = null;
        }
        if (attempt.stream) attempt.stream.getTracks().forEach(track => track.stop());
        // Pending browser promises retain the attempt, so release every media reference now.
        attempt.canvas = attempt.context = attempt.stream = attempt.window = null;
    }
    function finishOperation(operation) {
        if (pendingOperation === operation) pendingOperation = null;
        updateButton();
    }
    function exit() {
        if (pendingOperation || document.pictureInPictureElement !== video) return;
        const operation = {};
        pendingOperation = operation;
        updateButton();
        try {
            document.exitPictureInPicture().then(() => finishOperation(operation), () => finishOperation(operation));
        } catch (_) { finishOperation(operation); }
    }
    function stopAttempt(attempt) {
        const wasCurrent = isCurrent(attempt);
        release(attempt);
        if (wasCurrent) exit();
        updateButton();
    }
    function stop() {
        if (currentAttempt) stopAttempt(currentAttempt);
        else exit();
    }
    function fail(attempt) {
        if (!isCurrent(attempt)) return;
        callbacks.notice('pipError');
        stopAttempt(attempt);
    }
    function enter(attempt) {
        if (!isCurrent(attempt) || !attempt.stream || pendingOperation) return;
        attempt.phase = 'entering';
        const operation = {};
        pendingOperation = operation;
        updateButton();
        let result;
        try { result = video.requestPictureInPicture(); }
        catch (_) { finishOperation(operation); fail(attempt); return; }
        result.then(pipWindow => {
            finishOperation(operation);
            if (!isCurrent(attempt)) {
                // Native requests cannot be cancelled; retire a late entry before another attempt.
                exit();
                return;
            }
            if (document.pictureInPictureElement !== video) { stopAttempt(attempt); return; }
            attempt.phase = 'active';
            attempt.window = pipWindow;
            clearTimeout(attempt.prepareTimer); attempt.prepareTimer = null;
        }, error => {
            finishOperation(operation);
            if (!isCurrent(attempt)) return;
            if (error.name === 'NotAllowedError') {
                attempt.phase = 'ready';
                callbacks.notice('pipReady');
                clearTimeout(attempt.prepareTimer);
                attempt.prepareTimer = setTimeout(() => stopAttempt(attempt), 30000);
            } else fail(attempt);
        });
    }
    function toggle() {
        if (!pipSupported || !image || pendingOperation) return;
        if (document.pictureInPictureElement === video) { stop(); return; }
        if (currentAttempt) {
            if (currentAttempt.phase === 'ready' && video.readyState >= 2) enter(currentAttempt);
            return;
        }
        const attempt = { phase: 'preparing', canvas: null, context: null, stream: null,
            paintTimer: null, prepareTimer: null, window: null };
        currentAttempt = attempt;
        try {
            attempt.canvas = document.createElement('canvas');
            attempt.context = attempt.canvas.getContext('2d');
            const paint = () => {
                if (!isCurrent(attempt) || !image) return;
                try {
                    if (attempt.canvas.width !== image.naturalWidth || attempt.canvas.height !== image.naturalHeight) {
                        attempt.canvas.width = image.naturalWidth; attempt.canvas.height = image.naturalHeight;
                    }
                    attempt.context.drawImage(image, 0, 0);
                } catch (_) { fail(attempt); return; }
                attempt.paintTimer = setTimeout(paint, 100);
            };
            paint();
            if (!isCurrent(attempt)) return;
            attempt.stream = attempt.canvas.captureStream(10);
            video.onloadedmetadata = () => {
                if (!isCurrent(attempt) || video.srcObject !== attempt.stream) return;
                video.onloadedmetadata = null;
                // Keep the preparation deadline through play() and the native PiP request.
                try { video.play().then(() => enter(attempt), () => fail(attempt)); }
                catch (_) { fail(attempt); }
            };
            attempt.prepareTimer = setTimeout(() => fail(attempt), 10000);
            video.srcObject = attempt.stream;
        } catch (_) { fail(attempt); }
    }
    video.addEventListener('leavepictureinpicture', event => {
        const attempt = currentAttempt;
        if (attempt && attempt.phase === 'active' && event.pictureInPictureWindow === attempt.window &&
            document.pictureInPictureElement !== video) stopAttempt(attempt);
    });
    pip.addEventListener('click', toggle);
    return {
        setImage: value => { image = value; updateButton(); if (!image) stop(); },
        stop
    };
}
