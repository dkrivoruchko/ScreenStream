function createAccessPanel(viewportLayout, callbacks) {
    const panel = byId('pin-panel'), input = byId('pin'), submitButton = byId('unlock');
    const title = byId('pin-title'), message = byId('pin-message'), entry = byId('pin-entry');
    const blockCopy = byId('pin-block-copy'), countdown = byId('pin-countdown');
    const logo = byId('pin-logo'), retryButton = byId('pin-retry'), countdownValue = byId('pin-countdown-value');
    let blockedUntil = 0, blockedTimer = null, networkFailure = false;

    function clearBlock() {
        clearTimeout(blockedTimer); blockedTimer = null; blockedUntil = 0;
    }
    function showMessage(value) {
        text(title, words.pinTitle);
        entry.hidden = false;
        blockCopy.hidden = true;
        message.classList.remove('sr-only');
        countdown.hidden = true;
        text(message, value);
        message.hidden = !value;
    }
    function formatRemaining(seconds) {
        return seconds < 60 ? seconds + words.secondsShort : Math.floor(seconds / 60) + ':' + (seconds % 60 < 10 ? '0' : '') + seconds % 60;
    }
    function showBlocked(ms) {
        blockedUntil = Date.now() + Math.max(0, isFinite(ms) ? ms : 1000);
        text(title, words.pinBlockedTitle);
        entry.hidden = true;
        blockCopy.hidden = false;
        // Announce the block once; keep per-second updates out of the live region.
        message.classList.add('sr-only');
        message.hidden = false;
        countdown.hidden = false;
        text(message, words.pinBlocked + ' ' + words.tryAgainIn + ' ' + formatRemaining(Math.ceil((blockedUntil - Date.now()) / 1000)) + '.');
        const tick = () => {
            const seconds = Math.max(0, Math.ceil((blockedUntil - Date.now()) / 1000));
            input.disabled = submitButton.disabled = seconds > 0;
            text(countdownValue, formatRemaining(seconds));
            if (seconds > 0) blockedTimer = setTimeout(tick, 1000);
            else {
                blockedTimer = null; blockedUntil = 0;
                showMessage(''); callbacks.expired();
            }
        };
        tick();
    }
    function show(value) {
        clearBlock(); networkFailure = false;
        layout();
        const wasHidden = panel.hidden;
        panel.hidden = false;
        input.disabled = submitButton.disabled = false;
        if (value.access === 'blocked') showBlocked(value.retryAfterMs);
        else { showMessage(''); if (wasHidden) input.focus(); }
    }
    function hide() {
        clearBlock(); networkFailure = false;
        panel.hidden = true;
        input.value = '';
    }
    function result(value) {
        networkFailure = value === 'pinNetwork';
        submitButton.disabled = false;
        if (value === 'success') {
            input.value = '';
            panel.hidden = true;
        } else {
            submitButton.disabled = value === 'pinSending';
            showMessage(words[value]);
            if (value === 'pinWrong') { input.select(); input.focus(); }
        }
    }
    function layout(value) {
        const visible = value || viewportLayout.bounds();
        panel.style.left = visible.left + visible.width / 2 + 'px';
        panel.style.top = visible.top + visible.height / 2 + 'px';
        panel.style.maxWidth = Math.max(0, visible.width - 32) + 'px';
        panel.style.maxHeight = Math.max(0, visible.height - 32) + 'px';
        logo.style.width = Math.max(40, visible.height - 264) + 'px';
        if (!entry.hidden) viewportLayout.keepFocusVisible(panel);
    }
    byId('pin-form').addEventListener('submit', event => {
        event.preventDefault();
        if (!blockedUntil && !submitButton.disabled) callbacks.submit(input.value);
    });
    retryButton.addEventListener('click', callbacks.retry);
    viewportLayout.watch(layout);
    return {
        show, hide, result,
        visible: () => !panel.hidden,
        updateRetry: state => { retryButton.hidden = panel.hidden || (!networkFailure && state !== 'unavailable'); },
        pause: () => { clearTimeout(blockedTimer); blockedTimer = null; },
        resume: () => { layout(); if (blockedUntil) showBlocked(Math.max(0, blockedUntil - Date.now())); }
    };
}
