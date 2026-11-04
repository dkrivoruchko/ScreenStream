function createAccessPanel(viewportLayout, callbacks) {
    const panel = byId('pin-panel'), input = byId('pin'), submitButton = byId('unlock');
    const title = byId('pin-title'), message = byId('pin-message'), entry = byId('pin-entry');
    const fieldError = byId('pin-error');
    const blockCopy = byId('pin-block-copy'), countdown = byId('pin-countdown');
    const logo = byId('pin-logo'), retryButton = byId('pin-retry'), countdownValue = byId('pin-countdown-value');
    let current = null, started = false;

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
    function showBlocked(seconds, repeated) {
        if (!repeated) showFieldError('');
        text(title, words.pinBlockedTitle);
        entry.hidden = true;
        blockCopy.hidden = false;
        // Announce the block once; keep per-second updates out of the live region.
        message.classList.add('sr-only');
        message.hidden = false;
        countdown.hidden = false;
        if (!repeated) text(message, formatSpokenRemaining(seconds));
        input.disabled = submitButton.disabled = seconds > 0;
        countdownValue.dir = seconds < 60 ? locale.dir : 'ltr';
        text(countdownValue, formatRemaining(seconds));
    }
    function hide() {
        showFieldError('');
        panel.hidden = true;
        input.value = '';
    }
    function result(value) {
        submitButton.disabled = value === 'pinSending';
        showFieldError('');
        const invalid = value === 'pinWrong' || value === 'pinInvalid';
        showMessage(invalid ? '' : words[value]);
        if (invalid) {
            showFieldError(words[value]);
            if (value === 'pinWrong') input.select();
            input.focus();
        }
    }
    function render(value) {
        const previous = current;
        current = value;
        if (!value.visible) {
            if (!previous || previous.visible) hide();
            retryButton.hidden = true;
            return;
        }
        const repeated = previous && previous.visible && previous.mode === value.mode;
        if (!repeated) {
            showFieldError('');
            layout();
            const wasHidden = panel.hidden;
            panel.hidden = false;
            input.disabled = submitButton.disabled = false;
            if (value.mode !== 'blocked') {
                showMessage('');
                if (wasHidden) input.focus();
            }
        }
        if (value.mode === 'blocked') showBlocked(value.seconds, repeated);
        else if (previous && previous.result && !value.result) {
            const key = previous.result.key;
            if (key === 'pinNetwork' || key === 'pinSending') {
                showMessage('');
                submitButton.disabled = false;
                if (key === 'pinNetwork' && document.activeElement === retryButton) input.focus();
            }
        }
        if (value.result && (!previous || value.result !== previous.result)) result(value.result.key);
        retryButton.hidden = !value.result || value.result.key !== 'pinNetwork';
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
    function start() {
        if (started) return;
        started = true;
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
        byId('pin-form').addEventListener('submit', event => {
            event.preventDefault();
            if (current && current.visible && current.mode !== 'blocked' && !submitButton.disabled) callbacks.submit(input.value);
        });
        retryButton.addEventListener('click', callbacks.retry);
        viewportLayout.watch(layout);
    }
    return { start, render, resume: layout };
}
