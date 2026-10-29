const labels = document.querySelectorAll('[data-word]');
for (let i = 0; i < labels.length; i += 1) text(labels[i], words[labels[i].getAttribute('data-word')]);
const controlLabels = document.querySelectorAll('[data-label]');
for (let i = 0; i < controlLabels.length; i += 1) labelControl(controlLabels[i], words[controlLabels[i].getAttribute('data-label')]);
const accessibleLabels = document.querySelectorAll('[data-a11y]');
for (let i = 0; i < accessibleLabels.length; i += 1) accessibleLabels[i].setAttribute('aria-label', words[accessibleLabels[i].getAttribute('data-a11y')]);
const directionalSurfaces = document.querySelectorAll('[data-locale-dir]');
for (let i = 0; i < directionalSurfaces.length; i += 1) directionalSurfaces[i].dir = locale.dir;
const stateLogo = byId('state-logo'), recoveryAlternate = byId('recovery-alternate');
const infoConnection = byId('info-connection'), infoImage = byId('info-image'), infoSize = byId('info-size');
const infoNotice = byId('info-notice'), infoNoticeLabel = byId('info-notice-label');
const methodButtons = ['mjpeg', 'jpeg'].map(method => ({ method, button: byId('method-' + method) }));
let snapshot = null, connectionState = 'connecting', imageState = 'idle', displayedImage = null;
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
        displayedImage = value;
        presentation.setImage(value, w, h);
        infoSize.dir = w && h ? 'ltr' : locale.dir;
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
function hasDisplayedImage() { return !!(displayedImage && displayedImage.naturalWidth && displayedImage.naturalHeight); }
function captureNoticeText() { return connectionState === 'connected' && authorized() && snapshot.notice !== null ? words[snapshot.notice] : ''; }
function selectPrimary() {
    const result = (message, retry, alternate, failed) => ({ message, retry: !!retry, alternate: !!alternate, failed: !!failed });
    if (access.visible() || connectionState === 'pin_required' || connectionState === 'blocked') return result('');
    if (connectionState === 'unsupported') return result(words.unsupported);
    if (connectionState !== 'connected') return result(words[connectionState] || words.connecting);
    if (canLoadScreenImage(snapshot)) {
        if (imageState === 'failed') return result(words.mediaFailed, true, true, true);
        if (imageState === 'retrying') return result(words.mediaRetrying);
    }
    const captureNotice = captureNoticeText();
    if (captureNotice) return result(captureNotice, false, false, snapshot.notice === 'failed');
    if (!hasDisplayedImage() && canLoadScreenImage(snapshot)) return result(words.mediaStarting);
    return null;
}
function clearNotice() {
    clearTimeout(noticeTimer); noticeTimer = null; transientMessage = '';
}
function renderState() {
    const primary = selectPrimary();
    if (primary) clearNotice();
    const current = primary || { message: transientMessage, retry: false, alternate: false, failed: false };
    const captureNotice = captureNoticeText();
    const pinVisible = access.visible();
    current.access = pinVisible;
    current.empty = !pinVisible && !hasDisplayedImage();
    current.logo = !pinVisible && (!authorized() || connectionState !== 'connected' || snapshot.imageKind === 'logo');
    presentation.setStatus(current);
    const mediaText = { idle: words.unknownSize, starting: words.mediaStarting, retrying: words.mediaRetrying, failed: words.mediaFailed, viewing: words.viewing };
    text(infoConnection, words[connectionState] || connectionState);
    text(infoImage, mediaText[imageState]);
    text(infoNotice, captureNotice);
    infoNoticeLabel.hidden = infoNotice.hidden = !captureNotice;
    access.updateRetry();
    renderMethod();
}
function renderMethod() {
    const method = media.method(), enabled = canLoadScreenImage(snapshot);
    text(recoveryAlternate, method === 'mjpeg' ? words.useJpeg : words.useMjpeg);
    methodButtons.forEach(choice => {
        choice.button.setAttribute('aria-pressed', choice.method === method ? 'true' : 'false');
        choice.button.disabled = !enabled;
    });
}
function showNotice(key) {
    if (selectPrimary()) { clearNotice(); return; }
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
    clearNotice();
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
