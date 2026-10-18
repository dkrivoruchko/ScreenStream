const labels = document.querySelectorAll('[data-word]');
for (let i = 0; i < labels.length; i += 1) text(labels[i], words[labels[i].getAttribute('data-word')]);
const controlLabels = document.querySelectorAll('[data-label]');
for (let i = 0; i < controlLabels.length; i += 1) labelControl(controlLabels[i], words[controlLabels[i].getAttribute('data-label')]);
const placeholder = byId('placeholder'), stateLogo = byId('state-logo'), stateMessage = byId('state-message');
const statusMessage = byId('status'), recovery = byId('recovery'), recoveryAlternate = byId('recovery-alternate');
const infoConnection = byId('info-connection'), infoImage = byId('info-image'), infoSize = byId('info-size');
const infoNotice = byId('info-notice'), infoNoticeLabel = byId('info-notice-label');
const methodButtons = ['mjpeg', 'jpeg'].map(method => ({ method, button: byId('method-' + method) }));
let snapshot = null, connectionState = 'connecting', imageState = 'idle';
let noticeTimer = null, transientMessage = '';
let hiddenAt = 0, paused = false;
stateLogo.src = byId('pin-logo').src;
const viewportLayout = createViewportLayout();
const access = createAccessPanel(viewportLayout, {
    submit: pin => connection.submitPin(pin),
    retry,
    expired: () => { connectionState = 'pin_required'; renderState(); }
});
const presentation = createPresentation(viewportLayout, { notice: showNotice });
const media = createMedia(byId('image-stage'), {
    state: value => { imageState = value; renderState(); },
    image: (value, w, h) => {
        presentation.setImage(value, w, h);
        text(infoSize, w && h ? w + ' × ' + h : words.unknownSize);
    },
    method: renderMethod
});
const connection = createConnection({
    state: value => {
        connectionState = value;
        renderState();
    },
    snapshot: receiveSnapshot,
    pin: value => { access.result(value); renderState(); }
});
function authorized() { return snapshot && (snapshot.access === 'open' || snapshot.access === 'authorized'); }
function hasScreenImage() {
    return authorized() && snapshot.notice !== 'permission_required' &&
        (snapshot.imageKind === 'live' || snapshot.imageKind === 'retained');
}
function captureNoticeText() { return authorized() && snapshot.notice !== null ? words[snapshot.notice] : ''; }
function joinMessages(messages) { return messages.filter(Boolean).join(' · '); }
function renderState() {
    const captureNotice = captureNoticeText();
    const connectionMessage = connectionState !== 'connected' && connectionState !== 'pin_required' && connectionState !== 'blocked' ? words[connectionState] || words.connecting : '';
    const imageMessage = imageState === 'starting' ? words.mediaStarting : imageState === 'retrying' ? words.mediaRetrying : imageState === 'failed' ? words.mediaFailed : '';
    const retainedWithImage = !!(authorized() && snapshot.imageKind === 'retained' && placeholder.hidden);
    const failed = authorized() && snapshot.notice === 'failed';
    presentation.setCaptureNotice(retainedWithImage ? captureNotice || words.mediaStarting : '', failed);
    const messages = [connectionMessage];
    if (authorized()) {
        if (!retainedWithImage) messages.push(captureNotice);
        if (!captureNotice || retainedWithImage || imageState !== 'starting') messages.push(imageMessage);
    }
    messages.push(transientMessage);
    const pinVisible = access.visible();
    const emptyState = !pinVisible && !placeholder.hidden;
    stateLogo.hidden = false;
    // The access form and no-image state each own one message; hidden remains the actual-image fact.
    placeholder.style.display = pinVisible ? 'none' : '';
    presentation.setEmptyState(emptyState);
    placeholder.classList.toggle('capture-failed', failed);
    statusMessage.classList.toggle('capture-failed', failed);
    text(statusMessage, placeholder.hidden || pinVisible ? joinMessages(messages) : '');

    if (emptyState) {
        const body = !authorized() ? connectionMessage || words.connecting : captureNotice || imageMessage || words.mediaStarting;
        const details = [body];
        if (authorized() && connectionMessage) details.push(connectionMessage);
        if (captureNotice && imageMessage && imageState !== 'starting') details.push(imageMessage);
        details.push(transientMessage);
        const message = joinMessages(details);
        text(stateMessage, message);
        stateMessage.hidden = !message;
    }
    const mediaText = { idle: words.unknownSize, starting: words.mediaStarting, retrying: words.mediaRetrying, failed: words.mediaFailed, viewing: words.viewing };
    text(infoConnection, words[connectionState] || connectionState);
    text(infoImage, mediaText[imageState]);
    text(infoNotice, captureNotice);
    infoNoticeLabel.hidden = infoNotice.hidden = !captureNotice;
    recovery.hidden = pinVisible || (connectionState !== 'unavailable' && imageState !== 'failed');
    access.updateRetry(connectionState);
    recoveryAlternate.hidden = !hasScreenImage();
    renderMethod();
}
function renderMethod() {
    const method = media.method(), enabled = hasScreenImage();
    text(recoveryAlternate, method === 'mjpeg' ? words.useJpeg : words.useMjpeg);
    methodButtons.forEach(choice => {
        choice.button.setAttribute('aria-pressed', choice.method === method ? 'true' : 'false');
        choice.button.disabled = !enabled;
    });
}
function showNotice(key) {
    transientMessage = words[key];
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => { transientMessage = ''; renderState(); }, 10000);
    renderState();
}
function receiveSnapshot(value) {
    snapshot = value;
    if (value.access === 'pin_required' || value.access === 'blocked') {
        connectionState = value.access;
        media.configure(value);
        presentation.accessDenied();
        access.show(value);
    } else {
        // Reconnect must retry media even when the server snapshot is unchanged.
        const firstSnapshot = connectionState !== 'connected';
        connectionState = 'connected';
        access.hide();
        presentation.configure(value.display, firstSnapshot);
        media.configure(value, firstSnapshot);
    }
    renderState();
}
function retry() {
    presentation.closeMenu(false);
    connection.retry();
}
function switchMethod() {
    media.selectMethod(media.method() === 'mjpeg' ? 'jpeg' : 'mjpeg');
    presentation.closeMenu(true);
}
byId('recovery-retry').addEventListener('click', retry);
recoveryAlternate.addEventListener('click', switchMethod);
methodButtons.forEach(choice => {
    choice.button.addEventListener('click', () => media.selectMethod(choice.method));
});
function pausePage() {
    paused = true;
    connection.pause(); media.pause(); presentation.pause();
    access.pause();
    clearTimeout(noticeTimer); noticeTimer = null; transientMessage = '';
}
function resumePage() {
    if (!paused) return;
    paused = false;
    connection.retry();
    presentation.activity();
    access.resume();
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
text(infoSize, words.unknownSize);
renderState();
connection.retry();
