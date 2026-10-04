function createPresentation(callbacks) {
    const shell = byId('shell'), viewport = byId('viewport'), stage = byId('image-stage');
    const caption = byId('caption'), controls = byId('controls'), menu = byId('menu');
    const full = byId('fullscreen'), pip = byId('pip'), video = byId('pip-video');
    let display = null, mode = 'fit', image = null, width = 0, height = 0;
    let inactivity = null, interacting = false, menuOpen = false;
    let pipAttempt = null, pipOperation = null;
    const fullRequest = shell.requestFullscreen || shell.webkitRequestFullscreen || shell.mozRequestFullScreen || shell.msRequestFullscreen;
    const fullExit = document.exitFullscreen || document.webkitExitFullscreen || document.mozCancelFullScreen || document.msExitFullscreen;
    const pipSupported = !!(window.HTMLCanvasElement && HTMLCanvasElement.prototype.captureStream &&
        video.requestPictureInPicture && 'srcObject' in video && document.pictureInPictureEnabled);
    full.hidden = !fullRequest || !fullExit;
    pip.hidden = !pipSupported;
    pip.disabled = true;
    function fullscreenElement() {
        return document.fullscreenElement || document.webkitFullscreenElement || document.mozFullScreenElement || document.msFullscreenElement;
    }
    function showBlocks() {
        caption.classList.remove('autohidden'); controls.classList.remove('autohidden');
    }
    function holdsFocus() { return controls.contains(document.activeElement) || menu.contains(document.activeElement); }
    function activity() {
        showBlocks();
        clearTimeout(inactivity);
        inactivity = setTimeout(() => {
            inactivity = null;
            if (!display || interacting || menuOpen || holdsFocus()) return;
            if (display.titleLayout.autoHide) caption.classList.add('autohidden');
            if (display.controlsLayout.autoHide) controls.classList.add('autohidden');
        }, 4000);
    }
    function sizeImage() {
        if (!image || !width || !height) { stage.style.width = stage.style.height = '100%'; return; }
        const vw = viewport.clientWidth, vh = viewport.clientHeight;
        let scale = 1;
        if (mode === 'fit') scale = Math.min(vw / width, vh / height);
        else if (mode === 'width') scale = vw / width;
        const iw = Math.max(1, width * scale), ih = Math.max(1, height * scale);
        // Keep oversized images at the scroll origin so their edges remain reachable.
        image.style.width = iw + 'px'; image.style.height = ih + 'px';
        image.style.left = Math.max(0, (vw - iw) / 2) + 'px';
        image.style.top = (mode === 'width' ? 0 : Math.max(0, (vh - ih) / 2)) + 'px';
        stage.style.width = Math.max(vw, iw) + 'px'; stage.style.height = Math.max(vh, ih) + 'px';
    }
    function layout() {
        const visual = window.visualViewport;
        const visibleHeight = Math.min(shell.clientHeight, visual && visual.scale === 1 ? visual.height : window.innerHeight || shell.clientHeight);
        const pinPanel = byId('pin-panel');
        pinPanel.style.top = Math.round(visibleHeight * (visibleHeight <= 400 ? .03 : .1)) + 'px';
        pinPanel.style.maxHeight = Math.max(48, visibleHeight * .8) + 'px';
        if (!display) return;
        const topBar = byId('top-bar'), bottomBar = byId('bottom-bar');
        const topSafe = parseFloat(window.getComputedStyle(topBar).paddingTop) || 4;
        const bottomSafe = parseFloat(window.getComputedStyle(bottomBar).paddingBottom) || 4;
        const blocks = [{ node: caption, settings: display.titleLayout }, { node: controls, settings: display.controlsLayout }];
        const totals = { top: topSafe, bottom: bottomSafe }, reserved = { top: topSafe, bottom: bottomSafe };
        // Caption is outermost; controls sit nearer the image.
        blocks.forEach(block => {
            const node = block.node, settings = block.settings;
            if (node.hidden) return;
            node.classList.toggle('separate-bar', settings.presentation === 'bar');
            node.style.top = node.style.bottom = '';
            const edge = settings.position === 'top' ? 'top' : 'bottom';
            node.style[edge] = totals[edge] + 'px';
            totals[edge] += node.offsetHeight;
            if (settings.presentation === 'bar') reserved[edge] = totals[edge];
        });
        topBar.style.height = reserved.top + 'px'; bottomBar.style.height = reserved.bottom + 'px';
        viewport.style.top = reserved.top + 'px'; viewport.style.bottom = reserved.bottom + 'px';
        byId('status').style.top = totals.top + 'px';
        menu.style.top = menu.style.bottom = '';
        const edge = display.controlsLayout.position === 'top' ? 'top' : 'bottom';
        menu.style[edge] = totals[edge] + 4 + 'px';
        menu.style.maxHeight = Math.max(48, visibleHeight - totals.top - totals.bottom - 12) + 'px';
        sizeImage();
    }
    function sameLayout(previous, next) {
        return previous.position === next.position && previous.presentation === next.presentation &&
            previous.autoHide === next.autoHide;
    }
    function sameDisplay(previous, next) {
        return !!previous && previous.background === next.background && previous.title === next.title &&
            previous.titleEnabled === next.titleEnabled &&
            previous.retainImageOnMediaFailure === next.retainImageOnMediaFailure &&
            sameLayout(previous.titleLayout, next.titleLayout) && sameLayout(previous.controlsLayout, next.controlsLayout);
    }
    function configure(value, force) {
        // Unchanged snapshots must not reset the viewer's inactivity timer.
        if (!force && sameDisplay(display, value)) return;
        display = value;
        shell.style.backgroundColor = /^#[0-9a-fA-F]{6}$/.test(value.background) ? value.background : '#101418';
        text(byId('caption-text'), value.title);
        caption.title = value.title;
        document.title = value.title || words.app;
        caption.hidden = !value.titleEnabled;
        controls.hidden = false;
        activity(); layout();
    }
    function setImage(value, w, h) {
        image = value;
        width = w; height = h;
        updatePipButton();
        byId('placeholder').hidden = !!image;
        if (!image) stopPip();
        sizeImage();
    }
    function closeMenu(focus) {
        menuOpen = false; menu.hidden = true; byId('more').setAttribute('aria-expanded', 'false');
        if (focus) byId('more').focus();
        activity();
    }
    function toggleMenu() {
        if (menuOpen) { closeMenu(true); return; }
        menuOpen = true; menu.hidden = false; byId('more').setAttribute('aria-expanded', 'true');
        activity(); layout(); byId('show-info').focus();
    }
    function updatePipButton() { pip.disabled = !image || pipOperation !== null; }
    function isCurrentPipAttempt(attempt) { return pipAttempt === attempt; }
    function releasePipAttempt(attempt) {
        if (attempt.phase === 'released') return;
        if (isCurrentPipAttempt(attempt)) pipAttempt = null;
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
    function finishPipOperation(operation) {
        if (pipOperation === operation) pipOperation = null;
        updatePipButton();
    }
    function exitPip() {
        if (pipOperation || document.pictureInPictureElement !== video) return;
        const operation = {};
        pipOperation = operation;
        updatePipButton();
        try {
            document.exitPictureInPicture().then(() => finishPipOperation(operation), () => finishPipOperation(operation));
        } catch (_) { finishPipOperation(operation); }
    }
    function stopPipAttempt(attempt) {
        const wasCurrent = isCurrentPipAttempt(attempt);
        releasePipAttempt(attempt);
        if (wasCurrent) exitPip();
        updatePipButton();
    }
    function stopPip() {
        if (pipAttempt) stopPipAttempt(pipAttempt);
        else exitPip();
    }
    function failPipAttempt(attempt) {
        if (!isCurrentPipAttempt(attempt)) return;
        callbacks.notice('pipError');
        stopPipAttempt(attempt);
    }
    function enterPip(attempt) {
        if (!isCurrentPipAttempt(attempt) || !attempt.stream || pipOperation) return;
        attempt.phase = 'entering';
        const operation = {};
        pipOperation = operation;
        updatePipButton();
        let result;
        try { result = video.requestPictureInPicture(); }
        catch (_) { finishPipOperation(operation); failPipAttempt(attempt); return; }
        result.then(pipWindow => {
            finishPipOperation(operation);
            if (!isCurrentPipAttempt(attempt)) {
                // Native requests cannot be cancelled; retire a late entry before another attempt.
                exitPip();
                return;
            }
            if (document.pictureInPictureElement !== video) { stopPipAttempt(attempt); return; }
            attempt.phase = 'active';
            attempt.window = pipWindow;
            clearTimeout(attempt.prepareTimer); attempt.prepareTimer = null;
        }, error => {
            finishPipOperation(operation);
            if (!isCurrentPipAttempt(attempt)) return;
            if (error.name === 'NotAllowedError') {
                attempt.phase = 'ready';
                callbacks.notice('pipReady');
                clearTimeout(attempt.prepareTimer);
                attempt.prepareTimer = setTimeout(() => stopPipAttempt(attempt), 30000);
            } else failPipAttempt(attempt);
        });
    }
    function openPip() {
        if (!pipSupported || !image || pipOperation) return;
        if (document.pictureInPictureElement === video) { stopPip(); return; }
        if (pipAttempt) {
            if (pipAttempt.phase === 'ready' && video.readyState >= 2) enterPip(pipAttempt);
            return;
        }
        const attempt = { phase: 'preparing', canvas: null, context: null, stream: null,
            paintTimer: null, prepareTimer: null, window: null };
        pipAttempt = attempt;
        try {
            attempt.canvas = document.createElement('canvas');
            attempt.context = attempt.canvas.getContext('2d');
            const paint = () => {
                if (!isCurrentPipAttempt(attempt) || !image) return;
                try {
                    if (attempt.canvas.width !== image.naturalWidth || attempt.canvas.height !== image.naturalHeight) {
                        attempt.canvas.width = image.naturalWidth; attempt.canvas.height = image.naturalHeight;
                    }
                    attempt.context.drawImage(image, 0, 0);
                } catch (_) { failPipAttempt(attempt); return; }
                attempt.paintTimer = setTimeout(paint, 100);
            };
            paint();
            if (!isCurrentPipAttempt(attempt)) return;
            attempt.stream = attempt.canvas.captureStream(10);
            video.onloadedmetadata = () => {
                if (!isCurrentPipAttempt(attempt) || video.srcObject !== attempt.stream) return;
                video.onloadedmetadata = null;
                // Keep the preparation deadline through play() and the native PiP request.
                try { video.play().then(() => enterPip(attempt), () => failPipAttempt(attempt)); }
                catch (_) { failPipAttempt(attempt); }
            };
            attempt.prepareTimer = setTimeout(() => failPipAttempt(attempt), 10000);
            video.srcObject = attempt.stream;
        } catch (_) { failPipAttempt(attempt); }
    }
    video.addEventListener('leavepictureinpicture', event => {
        const attempt = pipAttempt;
        if (attempt && attempt.phase === 'active' && event.pictureInPictureWindow === attempt.window &&
            document.pictureInPictureElement !== video) stopPipAttempt(attempt);
    });
    full.addEventListener('click', () => {
        try {
            const result = fullscreenElement() ? fullExit.call(document) : fullRequest.call(shell);
            if (result && result.catch) result.catch(() => callbacks.notice('fullscreenError'));
        } catch (_) { callbacks.notice('fullscreenError'); }
    });
    ['fullscreenchange', 'webkitfullscreenchange', 'mozfullscreenchange', 'MSFullscreenChange'].forEach(name => {
        document.addEventListener(name, () => { text(full, fullscreenElement() ? words.leaveFullscreen : words.fullscreen); layout(); });
    });
    pip.addEventListener('click', openPip);
    byId('view-mode').addEventListener('change', event => { mode = event.target.value; viewport.scrollLeft = viewport.scrollTop = 0; sizeImage(); activity(); });
    byId('more').addEventListener('click', toggleMenu);
    byId('close-menu').addEventListener('click', () => closeMenu(true));
    byId('show-info').addEventListener('change', event => { byId('information').hidden = !event.target.checked; layout(); });
    document.addEventListener('keydown', event => {
        activity();
        if ((event.key === 'Escape' || event.key === 'BrowserBack' || event.keyCode === 27) && menuOpen) { event.preventDefault(); closeMenu(true); return; }
        if (!menuOpen && controls.contains(document.activeElement) && document.activeElement.tagName !== 'SELECT') {
            const key = event.key || ({37: 'ArrowLeft', 38: 'ArrowUp', 39: 'ArrowRight', 40: 'ArrowDown'})[event.keyCode];
            if (key === 'ArrowLeft' || key === 'ArrowRight' || key === 'ArrowUp' || key === 'ArrowDown') {
                const nodes = Array.prototype.slice.call(controls.querySelectorAll('button,select')).filter(node => !node.hidden && !node.disabled);
                const index = nodes.indexOf(document.activeElement), step = key === 'ArrowLeft' || key === 'ArrowUp' ? -1 : 1;
                event.preventDefault(); nodes[(index + step + nodes.length) % nodes.length].focus();
            }
        }
    });
    document.addEventListener('mousemove', activity);
    document.addEventListener('touchstart', () => { interacting = true; activity(); });
    document.addEventListener('touchend', () => { interacting = false; activity(); });
    document.addEventListener('touchcancel', () => { interacting = false; activity(); });
    document.addEventListener('mousedown', () => { interacting = true; activity(); });
    document.addEventListener('mouseup', () => { interacting = false; activity(); });
    controls.addEventListener('focusin', activity);
    controls.addEventListener('focusout', activity);
    window.addEventListener('resize', layout);
    window.addEventListener('orientationchange', layout);
    if (window.visualViewport) window.visualViewport.addEventListener('resize', layout);
    layout();
    return { configure, setImage, closeMenu, activity,
        pause: () => { clearTimeout(inactivity); inactivity = null; stopPip(); },
        accessDenied: () => { closeMenu(false); controls.hidden = caption.hidden = true; stopPip(); layout(); } };
}
