'use strict';

const byId = id => document.getElementById(id);
const viewer = byId('viewer');
const deliveryForm = byId('delivery-form');
const appearanceForm = byId('appearance-form');
let state = null;
let catalog = null;
let online = false;
let mutating = false;
let mutationVersion = 0;
let pollPending = false;
let pollTimer;
let savedAppearance = null;
let appearanceTimer;
let pendingAppearance = null;
let viewerStarted = false;

// API and shared device state.
async function request(path, body) {
    const response = await fetch('/__preview/api/' + path, body === undefined ? { cache: 'no-store' } : {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    });
    let value;
    try { value = await response.json(); } catch (_) { throw new Error('The preview server returned an unreadable response.'); }
    if (!response.ok) throw new Error(value.error || 'Preview request failed (' + response.status + ').');
    return value;
}

function showError(message) {
    const node = byId('notification');
    node.textContent = message;
    node.hidden = !message;
}

function setOnline(value) {
    if (value && !online) showError('');
    online = value;
    byId('server-status').textContent = value ? 'Connected' : 'Server unavailable';
    byId('server-status').className = 'connection-badge ' + (value ? 'connected' : 'disconnected');
    updateControls();
}

function updateControls() {
    for (const control of deliveryForm.elements) control.disabled = mutating || !online;
    for (const control of appearanceForm.elements) control.disabled = !state;
    byId('fail-image-requests').disabled = mutating || !online;
    updateAutoHide();
}

async function change(path, body) {
    if (mutating) return null;
    mutating = true;
    mutationVersion++;
    showError('');
    updateControls();
    try {
        const value = await request(path, body);
        renderState(value);
        return value;
    } catch (error) {
        const target = path === 'appearance' ? byId('appearance-message') : byId('notification');
        target.textContent = error.message;
        target.hidden = false;
        return null;
    } finally {
        mutating = false;
        updateControls();
        if (pendingAppearance) setTimeout(saveAppearance, 0);
    }
}

async function pollState() {
    clearTimeout(pollTimer);
    if (pollPending) return;
    pollPending = true;
    const version = mutationVersion;
    try {
        if (!mutating) {
            if (!catalog) {
                catalog = await request('catalog');
                const select = byId('scenario');
                select.textContent = '';
                for (const item of catalog.scenarios) {
                    const option = document.createElement('option');
                    option.value = item.id;
                    option.textContent = item.label;
                    select.appendChild(option);
                }
            }
            // A full panel load resets the device once; later polls only read it.
            const value = await request(state ? 'state' : 'reset', state ? undefined : {});
            if (version === mutationVersion) renderState(value);
        }
    } catch (error) {
        if (version === mutationVersion) { setOnline(false); showError(error.message); }
    } finally {
        pollPending = false;
        pollTimer = setTimeout(pollState, document.hidden ? 5000 : 1000);
    }
}

// Appearance is a local draft: polling never replaces text being edited.
function updateAutoHide() {
    for (const kind of ['title', 'controls']) {
        const overlay = byId(kind + '-presentation').value === 'overlay';
        const checkbox = byId(kind + '-autohide');
        checkbox.disabled = !state || !overlay;
        checkbox.parentElement.classList.toggle('disabled', !overlay);
        byId(kind + '-autohide-note').hidden = overlay;
    }
}

function readAppearance() {
    return {
        title: byId('stream-title').value,
        titleEnabled: byId('title-enabled').checked,
        background: byId('background-hex').value.trim(),
        titleLayout: { position: byId('title-position').value, presentation: byId('title-presentation').value, autoHide: byId('title-autohide').checked },
        controlsLayout: { position: byId('controls-position').value, presentation: byId('controls-presentation').value, autoHide: byId('controls-autohide').checked }
    };
}

function fillAppearance(value) {
    byId('stream-title').value = value.title;
    byId('title-enabled').checked = value.titleEnabled;
    byId('background-hex').value = value.background;
    byId('background-swatch').value = value.background;
    for (const kind of ['title', 'controls']) {
        const layout = value[kind + 'Layout'];
        byId(kind + '-position').value = layout.position;
        byId(kind + '-presentation').value = layout.presentation;
        byId(kind + '-autohide').checked = layout.autoHide;
    }
    savedAppearance = JSON.stringify(readAppearance());
    updateAutoHide();
}

function validBackground() { return /^#[0-9a-fA-F]{6}$/.test(byId('background-hex').value.trim()); }

function scheduleAppearance(delay) {
    if (savedAppearance === null) return;
    clearTimeout(appearanceTimer);
    appearanceTimer = null;
    pendingAppearance = null;
    updateAutoHide();
    const valid = validBackground();
    byId('background-hex').setAttribute('aria-invalid', String(!valid));
    byId('background-error').hidden = valid;
    byId('appearance-message').hidden = true;
    if (valid) appearanceTimer = setTimeout(() => {
        appearanceTimer = null;
        pendingAppearance = readAppearance();
        saveAppearance();
    }, delay);
}

async function saveAppearance() {
    if (!pendingAppearance || mutating || !state) return;
    const appearance = pendingAppearance;
    pendingAppearance = null;
    const snapshot = JSON.stringify(appearance);
    if (snapshot === savedAppearance) return;
    if (await change('appearance', appearance)) {
        savedAppearance = snapshot;
        byId('appearance-message').hidden = true;
    }
}

// Rendering never reloads the viewer: state and media update through its real protocol.
function renderState(value) {
    // Accept shared changes only while the form still matches its last saved draft.
    const cleanAppearance = savedAppearance === null || (!appearanceTimer && !pendingAppearance && JSON.stringify(readAppearance()) === savedAppearance);
    state = value;
    if (cleanAppearance) fillAppearance(value.appearance);
    setOnline(true);
    renderDelivery(value);
    renderDiagnostics(value);
    renderBuild();
    if (!viewerStarted) reloadViewer();
}

function renderDelivery(value) {
    byId('scenario').value = value.scenario.id;
    byId('after-stop').value = value.options.postStopImage;
    byId('image-available').checked = value.options.imageAvailable;
    byId('image-available-text').textContent = ['normal', 'pin', 'blocked'].includes(value.scenario.id) ?
        'Current frame available' : 'Last frame available';
    const { delivery } = value;
    byId('fail-image-requests').checked = value.media.failRequests;
    const pin = delivery.credentials.pin;
    byId('test-pin').hidden = delivery.access !== 'protected' || !pin;
    byId('test-pin').textContent = pin ? 'Test PIN: ' + pin : '';
    for (const orientation of ['portrait', 'landscape']) byId(orientation).setAttribute('aria-pressed', String(delivery.orientation === orientation));
}

function renderDiagnostics(value) {
    const { delivery, connection, media } = value;
    byId('fact-scenario').textContent = value.scenario.label;
    byId('fact-connection').textContent = connection.clients + ' connected client(s)';
    byId('fact-image').textContent = delivery.imageKind;
    byId('fact-notice').textContent = delivery.notice || 'None';
    byId('fact-media').textContent = media.activeRequests + ' active request(s)';
    byId('diagnostics-summary').textContent = connection.clients + ' client(s) · image ' + delivery.imageKind;
    const log = byId('event-log');
    const nearBottom = log.scrollTop + log.clientHeight >= log.scrollHeight - 30;
    const content = value.logs.map(item => item.time + '  ' + item.event).join('\n') || 'No events yet.';
    if (log.textContent !== content) { log.textContent = content; if (nearBottom) log.scrollTop = log.scrollHeight; }
}

function renderBuild() {
    if (!state) return;
    const build = state.build;
    const alert = byId('build-alert');
    const message = build.error ? 'Viewer build failed. See Diagnostics for details.' :
        build.generatedCurrent ? '' : 'Packaged HTML is out of date. Run npm run build before delivery.';
    if (alert.textContent !== message) alert.textContent = message;
    alert.hidden = !message;
    alert.classList.toggle('error', !!build.error);
    byId('fact-build').textContent = 'Current revision ' + build.revision + ' · ' + (build.hash || 'unavailable');
    byId('build-error').textContent = build.error || '';
    byId('build-error').hidden = !build.error;
}

// Page-local viewport affects only the actual iframe, never server state.
function fitViewer() {
    const [width, height] = byId('viewport-size').value.split(',').map(Number);
    const stage = byId('stage');
    const style = getComputedStyle(stage);
    const availableWidth = stage.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
    const availableHeight = stage.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom);
    const scale = Math.max(.1, Math.min(1, availableWidth / (width + 14), availableHeight / (height + 14)));
    const frame = byId('device-frame');
    frame.style.width = width + 'px';
    frame.style.height = height + 'px';
    frame.style.transform = 'scale(' + scale + ')';
    frame.classList.toggle('phone', width <= 740 && height <= 740);
    byId('device-space').style.width = (width + 14) * scale + 'px';
    byId('device-space').style.height = (height + 14) * scale + 'px';
    byId('viewport-description').textContent = width + ' × ' + height + ' CSS px · ' + Math.round(scale * 100) + '%';
}

function reloadViewer() {
    viewerStarted = true;
    viewer.src = '/viewer';
}

// Native controls: each user change has one API path.
deliveryForm.addEventListener('submit', event => event.preventDefault());
byId('scenario').addEventListener('change', async () => {
    const id = byId('scenario').value;
    if (!state || id === state.scenario.id) return;
    const accessBoundary = ['pin', 'blocked'].includes(state.scenario.id) || ['pin', 'blocked'].includes(id);
    if (!await change('scenario', { id })) renderDelivery(state);
    else if (accessBoundary) reloadViewer();
});
deliveryForm.addEventListener('change', async event => {
    const control = event.target;
    if (control.name && !await change('options', { [control.name]: control.type === 'checkbox' ? control.checked : control.value })) {
        renderDelivery(state);
    }
});
for (const orientation of ['portrait', 'landscape']) byId(orientation).addEventListener('click', () => change('action', { id: 'orientation', orientation }));
appearanceForm.addEventListener('submit', event => event.preventDefault());
appearanceForm.addEventListener('input', event => {
    if (event.target.id === 'background-swatch') {
        byId('background-hex').value = event.target.value;
        scheduleAppearance(0);
    } else if (event.target.id === 'background-hex') {
        if (validBackground()) byId('background-swatch').value = event.target.value.trim();
        scheduleAppearance(450);
    } else if (event.target.id === 'stream-title') scheduleAppearance(450);
});
appearanceForm.addEventListener('change', async event => {
    if (event.target.id === 'fail-image-requests') {
        if (!await change('action', { id: 'fail-image-requests', enabled: event.target.checked })) {
            event.target.checked = state.media.failRequests;
        }
    } else if (event.target.matches('select, input[type=checkbox]')) scheduleAppearance(0);
});
byId('reload-viewer').addEventListener('click', reloadViewer);
byId('viewport-size').addEventListener('change', fitViewer);
window.addEventListener('resize', fitViewer);
byId('diagnostics').addEventListener('toggle', fitViewer);
new ResizeObserver(fitViewer).observe(byId('stage'));
document.addEventListener('visibilitychange', () => { if (!document.hidden) pollState(); });

byId('viewport-size').value = '390,740';
byId('diagnostics').open = false;
requestAnimationFrame(fitViewer);
updateControls();
pollState();
