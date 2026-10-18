function createPresentation(viewportLayout, callbacks) {
    const shell = byId('shell'), viewport = byId('viewport'), stage = byId('image-stage');
    const caption = byId('caption'), controls = byId('controls'), menu = byId('menu');
    const fullscreenButton = byId('fullscreen'), more = byId('more');
    const topBar = byId('top-bar'), bottomBar = byId('bottom-bar');
    const status = byId('status'), recovery = byId('recovery'), placeholder = byId('placeholder');
    const stateLogo = byId('state-logo'), captionText = byId('caption-text');
    const tooltip = byId('control-tooltip'), captureNotice = byId('capture-notice');
    let tooltipButton = null, hoveredControl = null, touchUntil = 0;
    let display = null, mode = 'fit', image = null, width = 0, height = 0;
    let inactivity = null, interacting = false, menuOpen = false;
    let captureMessage = '', captureFailed = false, fullscreenAttempt = null;
    const fullRequest = shell.requestFullscreen || shell.webkitRequestFullscreen || shell.mozRequestFullScreen || shell.msRequestFullscreen;
    const fullExit = document.exitFullscreen || document.webkitExitFullscreen || document.mozCancelFullScreen || document.msExitFullscreen;
    fullscreenButton.hidden = !fullRequest || !fullExit;
    const pictureInPicture = createPictureInPicture(byId('pip'), byId('pip-video'), callbacks);
    function fullscreenElement() {
        return document.fullscreenElement || document.webkitFullscreenElement || document.mozFullScreenElement || document.msFullscreenElement;
    }
    function showBlocks() {
        caption.classList.remove('autohidden'); controls.classList.remove('autohidden');
    }
    function positionCaptureNotice() {
        captureNotice.style.left = viewport.offsetLeft + viewport.clientWidth / 2 + 'px';
        captureNotice.style.maxWidth = Math.max(1, viewport.clientWidth - 24) + 'px';
    }
    function hideTooltip() { tooltip.hidden = true; tooltipButton = null; }
    function positionTooltip() {
        if (!tooltipButton) return;
        const visible = viewportLayout.bounds();
        const bounds = tooltipButton.getBoundingClientRect(), origin = shell.getBoundingClientRect();
        const left = bounds.left - origin.left + (bounds.width - tooltip.offsetWidth) / 2;
        tooltip.style.left = Math.max(visible.left + 8, Math.min(visible.right - tooltip.offsetWidth - 8, left)) + 'px';
        const above = bounds.top - origin.top - tooltip.offsetHeight - 8;
        const top = above >= visible.top + 8 ? above : bounds.bottom - origin.top + 8;
        tooltip.style.top = Math.max(visible.top, Math.min(visible.bottom - tooltip.offsetHeight, top)) + 'px';
    }
    function showTooltip(button) {
        if (!button || button.disabled || button.hidden || menuOpen || Date.now() < touchUntil) return;
        const label = button.getAttribute('data-tooltip');
        if (!label) return;
        tooltipButton = button; text(tooltip, label); tooltip.hidden = false; positionTooltip();
    }
    function holdsFocus() {
        return !shell.classList.contains('pointer-input') &&
            (controls.contains(document.activeElement) || menu.contains(document.activeElement));
    }
    function activity() {
        showBlocks();
        clearTimeout(inactivity);
        inactivity = setTimeout(() => {
            inactivity = null;
            if (!display || interacting || menuOpen || hoveredControl || holdsFocus()) return;
            if (display.titleLayout.presentation === 'overlay' && display.titleLayout.autoHide) caption.classList.add('autohidden');
            if (display.controlsLayout.presentation === 'overlay' && display.controlsLayout.autoHide) {
                controls.classList.add('autohidden'); hideTooltip();
            }
        }, 4000);
    }
    function sizeImage() {
        viewport.classList.toggle('view-fit', mode === 'fit');
        viewport.classList.toggle('view-width', mode === 'width');
        // The stage owns only natural extent; CSS follows the final viewport after scrollbars.
        stage.style.width = image && mode === 'original' ? width + 'px' : '100%';
        stage.style.height = image && mode === 'original' ? height + 'px' : mode === 'width' ? 'auto' : '100%';
        if (!image || !width || !height) { positionCaptureNotice(); return; }
        if (mode === 'fit') {
            // Fit removes scrollbars before measuring, so a previous mode cannot narrow it.
            const scale = Math.min(viewport.clientWidth / width, viewport.clientHeight / height);
            image.style.width = Math.max(1, width * scale) + 'px';
            image.style.height = Math.max(1, height * scale) + 'px';
            viewport.scrollLeft = viewport.scrollTop = 0;
        } else if (mode === 'original') {
            image.style.width = width + 'px'; image.style.height = height + 'px';
        } else {
            // Native width/auto-height layout resolves vertical overflow without a JS feedback loop.
            image.style.width = image.style.height = '';
        }
        positionCaptureNotice();
    }
    function layout(value) {
        const visible = value || viewportLayout.bounds();
        const bottom = shell.clientHeight - visible.bottom, center = visible.left + visible.width / 2;
        const blocks = display ? [{ node: controls, settings: display.controlsLayout }, { node: caption, settings: display.titleLayout }] : [];
        const totals = { top: visible.top, bottom }, reserved = { top: visible.top, bottom };
        // Controls are outermost; the caption sits nearer the image.
        blocks.forEach(block => {
            const node = block.node, settings = block.settings;
            if (node.hidden) return;
            node.classList.toggle('separate-bar', settings.presentation === 'bar');
            node.style.left = center + 'px';
            const inset = node === caption && settings.presentation !== 'bar' ? (visible.width <= 600 ? 16 : visible.width * .05) : 0;
            node.style.maxWidth = Math.max(0, visible.width - inset) + 'px';
            node.style.top = node.style.bottom = '';
            const edge = settings.position === 'top' ? 'top' : 'bottom';
            node.style[edge] = totals[edge] + 'px';
            totals[edge] += node.offsetHeight;
            if (settings.presentation === 'bar') reserved[edge] = totals[edge];
        });
        topBar.style.height = reserved.top + 'px'; bottomBar.style.height = reserved.bottom + 'px';
        captureNotice.style.bottom = totals.bottom + 8 + 'px';
        viewport.style.top = reserved.top + 'px'; viewport.style.bottom = reserved.bottom + 'px';
        viewport.style.left = visible.left + 'px'; viewport.style.right = shell.clientWidth - visible.right + 'px';
        status.style.top = totals.top + 'px';
        status.style.left = visible.left + visible.width * .1 + 'px'; status.style.width = visible.width * .8 + 'px';
        [menu, recovery].forEach(node => {
            node.style.left = center + 'px'; node.style.marginLeft = '0';
            node.style.webkitTransform = node.style.transform = 'translateX(-50%)';
            node.style.maxWidth = Math.max(0, visible.width - 16) + 'px';
        });
        recovery.style.top = visible.top + visible.height * .35 + 'px';
        recovery.style.maxHeight = Math.max(0, visible.height * .65 - 16) + 'px';
        menu.style.top = menu.style.bottom = '';
        const edge = display && display.controlsLayout.position === 'top' ? 'top' : 'bottom';
        menu.style[edge] = totals[edge] + 4 + 'px';
        const menuSpace = shell.clientHeight - totals.top - totals.bottom - 12;
        if (menuSpace < 48) {
            // In a very short window the scrollable panel may cover the HUD, but stays reachable.
            menu.style.bottom = ''; menu.style.top = visible.top + 4 + 'px';
        }
        menu.style.maxHeight = Math.max(0, menuSpace < 48 ? visible.height - 8 : menuSpace) + 'px';
        viewportLayout.keepFocusVisible(menu);
        stateLogo.style.width = Math.max(40, viewport.clientHeight - 264) + 'px';
        sizeImage(); positionTooltip();
    }
    function sameLayout(previous, next) {
        return previous.position === next.position && previous.presentation === next.presentation &&
            previous.autoHide === next.autoHide;
    }
    function sameDisplay(previous, next) {
        return !!previous && previous.background === next.background && previous.title === next.title &&
            previous.titleEnabled === next.titleEnabled &&
            sameLayout(previous.titleLayout, next.titleLayout) && sameLayout(previous.controlsLayout, next.controlsLayout);
    }
    function applyBackground() {
        const background = display && /^#[0-9a-fA-F]{6}$/.test(display.background) ? display.background : '#101418';
        shell.style.backgroundColor = shell.classList.contains('empty-screen') || shell.classList.contains('access-screen') ? '' : background;
        return background;
    }
    function setCaptureNotice(message, failed) {
        if (captureMessage === message && captureFailed === failed) return;
        captureMessage = message; captureFailed = failed;
        text(captureNotice, message);
        captureNotice.hidden = !message;
        captureNotice.classList.toggle('capture-failed', failed);
        layout();
    }
    function setEmptyState(value) {
        shell.classList.toggle('empty-screen', value);
        applyBackground();
    }
    function configure(value, force) {
        // Unchanged snapshots must not reset the viewer's inactivity timer.
        if (!force && sameDisplay(display, value)) return;
        display = value;
        shell.classList.remove('access-screen');
        const background = applyBackground();
        const channels = [1, 3, 5].map(offset => {
            const channel = parseInt(background.substr(offset, 2), 16) / 255;
            return channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
        });
        const luminance = .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
        // Choose whichever of black or white has stronger contrast against the shared background.
        shell.classList.toggle('dark-bar-text', (luminance + .05) / .05 > 1.05 / (luminance + .05));
        text(captionText, value.title);
        caption.title = value.title;
        document.title = value.title || words.app;
        caption.hidden = !value.titleEnabled;
        controls.hidden = false;
        activity(); layout();
    }
    function setImage(value, w, h) {
        image = value;
        width = w; height = h;
        pictureInPicture.setImage(value);
        placeholder.hidden = !!image;
        sizeImage();
    }
    function closeMenu(focus) {
        menuOpen = false; menu.hidden = true; more.setAttribute('aria-expanded', 'false');
        if (focus) more.focus();
        hideTooltip(); activity();
    }
    function toggleMenu() {
        hideTooltip();
        if (menuOpen) { closeMenu(true); return; }
        menuOpen = true; menu.hidden = false; more.setAttribute('aria-expanded', 'true');
        activity(); layout();
        const method = byId('method-mjpeg').getAttribute('aria-pressed') === 'true' ? byId('method-mjpeg') : byId('method-jpeg');
        (method.disabled ? menu : method).focus();
    }
    fullscreenButton.addEventListener('click', () => {
        const attempt = { fail: null };
        fullscreenAttempt = attempt;
        const failed = () => {
            if (fullscreenAttempt !== attempt) return;
            fullscreenAttempt = null;
            callbacks.notice('fullscreenError');
        };
        try {
            const result = fullscreenElement() ? fullExit.call(document) : fullRequest.call(shell);
            // Promise APIs also dispatch error events; only legacy void APIs need the event fallback.
            if (result && result.catch) result.catch(failed);
            else attempt.fail = failed;
        } catch (_) { failed(); }
    });
    ['fullscreenerror', 'webkitfullscreenerror', 'mozfullscreenerror', 'MSFullscreenError'].forEach(name => {
        document.addEventListener(name, () => { if (fullscreenAttempt && fullscreenAttempt.fail) fullscreenAttempt.fail(); });
    });
    ['fullscreenchange', 'webkitfullscreenchange', 'mozfullscreenchange', 'MSFullscreenChange'].forEach(name => {
        document.addEventListener(name, () => {
            const active = !!fullscreenElement();
            fullscreenButton.setAttribute('aria-pressed', active ? 'true' : 'false');
            labelControl(fullscreenButton, active ? words.leaveFullscreen : words.fullscreen);
            if (tooltipButton === fullscreenButton) showTooltip(fullscreenButton);
        });
    });
    const modes = ['fit', 'original', 'width'];
    modes.forEach(value => byId('view-' + value).addEventListener('click', () => {
        mode = value;
        modes.forEach(name => byId('view-' + name).setAttribute('aria-pressed', name === mode ? 'true' : 'false'));
        viewport.scrollLeft = viewport.scrollTop = 0;
        sizeImage(); activity();
    }));
    more.addEventListener('click', toggleMenu);
    document.addEventListener('click', event => {
        if (menuOpen && !menu.contains(event.target) && !more.contains(event.target)) closeMenu(false);
    });
    document.addEventListener('keydown', event => {
        touchUntil = 0;
        shell.classList.remove('pointer-input');
        activity();
        if (event.key === 'Escape' || event.key === 'BrowserBack' || event.keyCode === 27) {
            hideTooltip();
            if (menuOpen) { event.preventDefault(); closeMenu(true); }
            return;
        }
        if (controls.contains(document.activeElement)) showTooltip(document.activeElement);
    });
    document.addEventListener('mousemove', activity);
    document.addEventListener('touchstart', () => {
        touchUntil = Date.now() + 1000;
        shell.classList.add('pointer-input'); hoveredControl = null; hideTooltip(); interacting = true; activity();
    });
    ['touchend', 'touchcancel'].forEach(name => document.addEventListener(name, () => {
        touchUntil = Date.now() + 1000; interacting = false; activity();
    }));
    document.addEventListener('mousedown', () => { shell.classList.add('pointer-input'); hideTooltip(); interacting = true; activity(); });
    document.addEventListener('mouseup', () => { interacting = false; activity(); });
    const buttons = controls.querySelectorAll('button');
    for (let i = 0; i < buttons.length; i += 1) {
        const button = buttons[i];
        button.addEventListener('mouseenter', () => {
            if (Date.now() < touchUntil) return;
            hoveredControl = button; showTooltip(button); activity();
        });
        button.addEventListener('mouseleave', () => { hoveredControl = null; hideTooltip(); activity(); });
        button.addEventListener('focus', () => {
            activity();
            if (!shell.classList.contains('pointer-input')) showTooltip(button);
        });
        button.addEventListener('blur', () => { hideTooltip(); activity(); });
    }
    viewportLayout.watch(layout);
    return { configure, setImage, setEmptyState, setCaptureNotice, closeMenu, activity,
        pause: () => { clearTimeout(inactivity); inactivity = null; fullscreenAttempt = null; interacting = false; hoveredControl = null; hideTooltip(); pictureInPicture.stop(); },
        accessDenied: () => {
            hoveredControl = null;
            shell.classList.add('access-screen');
            shell.style.backgroundColor = '';
            closeMenu(false); controls.hidden = caption.hidden = true; pictureInPicture.stop(); layout();
        } };
}
