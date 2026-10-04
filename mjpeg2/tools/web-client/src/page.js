const labels = document.querySelectorAll('[data-word]');
for (let i = 0; i < labels.length; i += 1) text(labels[i], words[labels[i].getAttribute('data-word')]);
let snapshot = null, connectionState = 'connecting', imageState = 'idle';
let blockedUntil = 0, blockedTimer = null, noticeTimer = null, notice = '';
let hiddenAt = 0, paused = false;
const pinPanel = byId('pin-panel'), pinInput = byId('pin'), unlock = byId('unlock');
const presentation = createPresentation({ notice: showNotice });
const media = createMedia(byId('image-stage'), {
    state: value => { imageState = value; renderState(); },
    image: (value, w, h) => {
        presentation.setImage(value, w, h);
        text(byId('info-size'), w && h ? w + ' × ' + h : words.unknownSize);
    },
    method: value => { text(byId('info-method'), value === 'mjpeg' ? 'MJPEG' : 'JPEG'); updateAlternate(); }
});
const connection = createConnection({
    state: value => {
        connectionState = value;
        renderState();
    },
    snapshot: receiveSnapshot,
    pin: value => {
        unlock.disabled = false;
        if (value === 'success') {
            pinInput.value = '';
            pinPanel.hidden = true;
        } else {
            unlock.disabled = value === 'pinSending';
            text(byId('pin-message'), words[value]);
            if (value === 'pinWrong') { pinInput.select(); pinInput.focus(); }
        }
    }
});
function authorized() { return snapshot && (snapshot.access === 'open' || snapshot.access === 'authorized'); }
function streamNoticeText() { return authorized() && snapshot.notice !== null ? words[snapshot.notice] : ''; }
function renderState() {
    const streamNotice = streamNoticeText();
    let status = '';
    if (connectionState !== 'connected' && connectionState !== 'pin_required' && connectionState !== 'blocked') status = words[connectionState] || words.connecting;
    if (authorized()) {
        if (streamNotice) status += (status ? ' · ' : '') + streamNotice;
        if (imageState === 'starting' || imageState === 'retrying' || imageState === 'failed') {
            const key = imageState === 'starting' ? 'mediaStarting' : imageState === 'retrying' ? 'mediaRetrying' : 'mediaFailed';
            status += (status ? ' · ' : '') + words[key];
        }
    }
    if (notice) status += (status ? ' · ' : '') + notice;
    text(byId('status'), status);
    text(byId('placeholder'), authorized() ? (snapshot.hasImage ? words.mediaStarting : streamNotice || words.mediaStarting) : words[connectionState] || words.connecting);
    const mediaText = { idle: words.mediaStarting, starting: words.mediaStarting, retrying: words.mediaRetrying, failed: words.mediaFailed, viewing: words.viewing };
    text(byId('info-connection'), words[connectionState] || connectionState);
    text(byId('info-image'), mediaText[imageState]);
    text(byId('info-notice'), streamNotice);
    byId('info-notice-label').hidden = byId('info-notice').hidden = !streamNotice;
    byId('recovery').hidden = !pinPanel.hidden || (connectionState !== 'unavailable' && imageState !== 'failed');
    byId('recovery-alternate').hidden = !authorized() || !snapshot.hasImage;
    updateAlternate();
}
function updateAlternate() {
    const label = media.method() === 'mjpeg' ? words.alternate : words.useMjpeg;
    text(byId('alternate'), label); text(byId('recovery-alternate'), label);
    byId('alternate').disabled = !authorized() || !snapshot.hasImage;
}
function showNotice(key) {
    notice = words[key];
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => { notice = ''; renderState(); }, 10000);
    renderState();
}
function showBlocked(ms) {
    blockedUntil = Date.now() + Math.max(0, isFinite(ms) ? ms : 1000);
    const tick = () => {
        const seconds = Math.max(0, Math.ceil((blockedUntil - Date.now()) / 1000));
        pinInput.disabled = unlock.disabled = seconds > 0;
        text(byId('pin-message'), seconds > 0 ? words.blocked + seconds + words.seconds : words.pinRequired);
        if (seconds > 0) blockedTimer = setTimeout(tick, 1000);
        else { blockedTimer = null; blockedUntil = 0; connectionState = 'pin_required'; renderState(); }
    };
    tick();
}
function receiveSnapshot(value) {
    clearTimeout(blockedTimer); blockedTimer = null; blockedUntil = 0;
    snapshot = value;
    if (value.access === 'pin_required' || value.access === 'blocked') {
        connectionState = value.access;
        media.configure(value);
        presentation.accessDenied();
        const wasHidden = pinPanel.hidden;
        pinPanel.hidden = false;
        pinInput.disabled = unlock.disabled = false;
        if (value.access === 'blocked') showBlocked(value.retryAfterMs);
        else { text(byId('pin-message'), words.pinRequired); if (wasHidden) pinInput.focus(); }
    } else {
        // Reconnect must retry media even when the server snapshot is unchanged.
        const firstSnapshot = connectionState !== 'connected';
        connectionState = 'connected';
        pinPanel.hidden = true;
        pinInput.value = '';
        presentation.configure(value.display, firstSnapshot);
        text(byId('info-title'), value.display.title);
        media.configure(value, firstSnapshot);
    }
    renderState();
}
function retry() {
    presentation.closeMenu(false);
    connection.retry();
}
function alternate() { media.alternate(); presentation.closeMenu(true); }
byId('pin-form').addEventListener('submit', event => {
    event.preventDefault();
    if (!blockedUntil && !unlock.disabled) connection.submitPin(pinInput.value);
});
['retry', 'pin-retry', 'recovery-retry'].forEach(id => byId(id).addEventListener('click', retry));
['alternate', 'recovery-alternate'].forEach(id => byId(id).addEventListener('click', alternate));
function pausePage() {
    paused = true;
    connection.pause(); media.pause(); presentation.pause();
    clearTimeout(blockedTimer); blockedTimer = null;
    clearTimeout(noticeTimer); noticeTimer = null; notice = '';
}
function resumePage() {
    if (!paused) return;
    paused = false;
    connection.retry();
    presentation.activity();
    if (blockedUntil) showBlocked(Math.max(0, blockedUntil - Date.now()));
}
window.addEventListener('pagehide', pausePage);
window.addEventListener('pageshow', resumePage);
document.addEventListener('freeze', pausePage);
document.addEventListener('resume', resumePage);
document.addEventListener('visibilitychange', () => {
    if (document.hidden) { hiddenAt = Date.now(); return; }
    const stale = hiddenAt && Date.now() - hiddenAt > 30000;
    hiddenAt = 0;
    if (paused) resumePage();
    else {
        if (stale || !connection.healthy()) connection.retry();
        else media.resume();
        presentation.activity();
    }
});
text(byId('info-method'), 'MJPEG');
text(byId('info-size'), words.unknownSize);
renderState();
connection.retry();
