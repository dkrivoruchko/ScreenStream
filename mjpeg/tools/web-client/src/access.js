function createAccessPanel(viewportLayout, callbacks) {
    const panel = byId('pin-panel'), input = byId('pin'), submitButton = byId('unlock');
    const title = byId('pin-title'), message = byId('pin-message'), entry = byId('pin-entry');
    const fieldError = byId('pin-error');
    const blockCopy = byId('pin-block-copy'), countdown = byId('pin-countdown');
    const logo = byId('pin-logo'), retryButton = byId('pin-retry'), countdownValue = byId('pin-countdown-value');
    let blockedUntil = 0, blockedTimer = null, networkFailure = false, accessState = null, sending = false;

    function clearBlock() {
        clearTimeout(blockedTimer); blockedTimer = null; blockedUntil = 0;
    }
    function showFieldError(value) {
        text(fieldError, value);
        fieldError.hidden = !value;
        input.setAttribute('aria-invalid', value ? 'true' : 'false');
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
        return seconds < 60 ? secondsText('countdownSeconds', seconds) : Math.floor(seconds / 60) + ':' + (seconds % 60 < 10 ? '0' : '') + seconds % 60;
    }
    function formatSpokenRemaining(seconds) {
        return seconds === 1 ? words.pinBlockedAnnouncementSecond : secondsText('pinBlockedAnnouncementSeconds', seconds);
    }
    function showBlocked(ms, repeated) {
        sending = false;
        clearTimeout(blockedTimer); blockedTimer = null;
        if (!repeated) showFieldError('');
        blockedUntil = Date.now() + Math.max(0, isFinite(ms) ? ms : 1000);
        text(title, words.pinBlockedTitle);
        entry.hidden = true;
        blockCopy.hidden = false;
        // Announce the block once; keep per-second updates out of the live region.
        message.classList.add('sr-only');
        message.hidden = false;
        countdown.hidden = false;
        if (!repeated) text(message, formatSpokenRemaining(Math.max(0, Math.ceil((blockedUntil - Date.now()) / 1000))));
        const tick = () => {
            const seconds = Math.max(0, Math.ceil((blockedUntil - Date.now()) / 1000));
            input.disabled = submitButton.disabled = seconds > 0;
            countdownValue.dir = seconds < 60 ? locale.dir : 'ltr';
            text(countdownValue, formatRemaining(seconds));
            if (seconds > 0) blockedTimer = setTimeout(tick, 1000);
            else {
                blockedTimer = null; blockedUntil = 0;
                accessState = 'pin_required';
                showMessage(''); callbacks.expired();
            }
        };
        tick();
    }
    function show(value) {
        if (!panel.hidden && accessState === value.access) {
            if (value.access === 'blocked') showBlocked(value.retryAfterMs, true);
            return;
        }
        clearBlock(); networkFailure = false;
        sending = false;
        accessState = value.access;
        showFieldError('');
        layout();
        const wasHidden = panel.hidden;
        panel.hidden = false;
        input.disabled = submitButton.disabled = false;
        if (value.access === 'blocked') showBlocked(value.retryAfterMs);
        else { showMessage(''); if (wasHidden) input.focus(); }
    }
    function hide() {
        clearBlock(); networkFailure = false;
        sending = false;
        accessState = null;
        showFieldError('');
        panel.hidden = true;
        input.value = '';
    }
    function result(value) {
        sending = value === 'pinSending';
        networkFailure = value === 'pinNetwork';
        submitButton.disabled = false;
        showFieldError('');
        if (value === 'success') {
            accessState = null;
            input.value = '';
            panel.hidden = true;
        } else {
            submitButton.disabled = value === 'pinSending';
            const invalid = value === 'pinWrong' || value === 'pinInvalid';
            showMessage(invalid ? '' : words[value]);
            if (invalid) {
                showFieldError(words[value]);
                if (value === 'pinWrong') input.select();
                input.focus();
            }
        }
    }
    input.addEventListener('input', () => {
        const value = input.value, start = input.selectionStart, end = input.selectionEnd;
        const digits = value.replace(/[^0-9]/g, '').slice(0, 6);
        if (value !== digits) {
            input.value = digits;
            input.setSelectionRange(
                Math.min(digits.length, value.slice(0, start).replace(/[^0-9]/g, '').length),
                Math.min(digits.length, value.slice(0, end).replace(/[^0-9]/g, '').length));
        }
        showFieldError('');
    });
    input.addEventListener('paste', event => {
        if (!event.clipboardData) return;
        event.preventDefault();
        const start = input.selectionStart, end = input.selectionEnd;
        const digits = event.clipboardData.getData('text').replace(/[^0-9]/g, '');
        const inserted = digits.slice(0, 6 - input.value.length + end - start);
        if (!inserted) return;
        input.value = input.value.slice(0, start) + inserted + input.value.slice(end);
        input.setSelectionRange(start + inserted.length, start + inserted.length);
        showFieldError('');
    });
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
        updateRetry: () => { retryButton.hidden = panel.hidden || !networkFailure; },
        pause: () => {
            clearTimeout(blockedTimer); blockedTimer = null;
            // The page aborts its PIN request before pausing this panel.
            if (sending) {
                sending = false;
                submitButton.disabled = false;
                showMessage('');
            }
        },
        resume: () => { layout(); if (blockedUntil) showBlocked(Math.max(0, blockedUntil - Date.now()), true); }
    };
}
