'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DEFAULT_APPEARANCE, DEFAULT_OPTIONS, SCENARIOS } = require('./scenarios.cjs');

const PIN = '123456';
const BLOCK_DURATION_MS = 20000;

class PreviewDeviceError extends Error {}

// One virtual device. Visual presets are projections; viewing credentials have their own lifetime.
function createPreviewDevice() {
    const fixtures = {};
    const fixtureNames = ['logo', ...['portrait', 'landscape'].flatMap(orientation =>
        Array.from({ length: 8 }, (_, frameIndex) => orientation + '-' + frameIndex))];
    for (const name of fixtureNames) {
        fixtures[name] = fs.readFileSync(path.join(__dirname, 'fixtures', name + '.jpg'));
    }
    let appearance, options, scenarioId, orientation;
    let cookie, token, credentialGeneration = 0;
    let blockedUntil = 0, wrongAttempts = 0;

    function isAccessProtected() { return scenarioId === 'pin' || scenarioId === 'blocked'; }
    function hasActiveCapture() { return scenarioId === 'normal' || isAccessProtected(); }
    function notice() { return hasActiveCapture() || scenarioId === 'starting' ? null : scenarioId; }
    function imageKind() {
        if (hasActiveCapture() && options.imageAvailable) return 'live';
        if (options.postStopImage === 'last' && !hasActiveCapture() && options.imageAvailable) return 'retained';
        return 'logo';
    }
    function selectedJpeg() {
        const kind = imageKind();
        if (kind === 'none') return null;
        if (kind === 'logo') return fixtures.logo;
        const frameIndex = kind === 'retained' ? 0 : Math.floor(performance.now() / 250) % 8;
        return fixtures[orientation + '-' + frameIndex];
    }
    function rotateCredentials() {
        cookie = crypto.randomBytes(32).toString('hex');
        token = crypto.randomBytes(24).toString('base64url');
        credentialGeneration += 1;
        wrongAttempts = 0;
        blockedUntil = 0;
    }
    function reset() {
        appearance = structuredClone(DEFAULT_APPEARANCE);
        options = structuredClone(DEFAULT_OPTIONS);
        scenarioId = 'normal'; orientation = 'portrait';
        rotateCredentials();
    }
    function selectScenario(id) {
        if (!SCENARIOS.some(item => item.id === id)) throw new PreviewDeviceError('Unknown scenario');
        const wasProtected = isAccessProtected();
        scenarioId = id;
        if (wasProtected || isAccessProtected()) {
            rotateCredentials();
            if (id === 'blocked') blockedUntil = performance.now() + BLOCK_DURATION_MS;
            return true;
        }
        return false;
    }
    function updateAppearance(update) {
        const next = structuredClone(appearance);
        for (const [key, value] of Object.entries(update)) {
            if (key === 'title') {
                if (typeof value !== 'string' || value.length > 16384) throw new PreviewDeviceError('Title must be text up to 16384 characters');
            } else if (key === 'background') {
                if (typeof value !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(value)) throw new PreviewDeviceError('Background must be a six-digit RGB color');
            } else if (key === 'titleEnabled') {
                if (typeof value !== 'boolean') throw new PreviewDeviceError(key + ' must be Boolean');
            } else if (key === 'titleLayout' || key === 'controlsLayout') {
                if (!value || typeof value !== 'object' || Array.isArray(value) ||
                    !['top', 'bottom'].includes(value.position) || !['overlay', 'bar'].includes(value.presentation) ||
                    typeof value.autoHide !== 'boolean' || Object.keys(value).some(field => !['position', 'presentation', 'autoHide'].includes(field))) {
                    throw new PreviewDeviceError('Invalid ' + key);
                }
            } else throw new PreviewDeviceError('Unknown appearance field: ' + key);
            next[key] = structuredClone(value);
        }
        appearance = next;
    }
    function updateOptions(update) {
        const next = { ...options };
        for (const [key, value] of Object.entries(update)) {
            if (key === 'postStopImage') {
                if (!['logo', 'last'].includes(value)) throw new PreviewDeviceError('Invalid inactive image');
            } else if (key === 'imageAvailable') {
                if (typeof value !== 'boolean') throw new PreviewDeviceError('Image available must be Boolean');
            } else throw new PreviewDeviceError('Unknown device option: ' + key);
            next[key] = value;
        }
        options = next;
    }
    function setOrientation(value) {
        if (!['portrait', 'landscape'].includes(value)) throw new PreviewDeviceError('Invalid orientation');
        orientation = value;
    }
    function remainingBlockMs() { return Math.max(0, Math.ceil(blockedUntil - performance.now())); }
    function allowsViewing(suppliedCookie, suppliedToken) {
        return !isAccessProtected() || suppliedCookie === cookie || suppliedToken === token;
    }
    function exchangeToken(supplied) { return isAccessProtected() && supplied === token ? cookie : null; }
    function verifyPin(supplied) {
        if (!isAccessProtected()) return { status: 204 };
        const remaining = remainingBlockMs();
        if (remaining > 0) return { status: 429, retryAfterMs: remaining };
        if (blockedUntil) { blockedUntil = 0; wrongAttempts = 0; }
        if (supplied === PIN) {
            wrongAttempts = 0;
            return { status: 204, cookie };
        }
        wrongAttempts += 1;
        if (wrongAttempts >= 5) {
            blockedUntil = performance.now() + BLOCK_DURATION_MS;
            return { status: 429, retryAfterMs: BLOCK_DURATION_MS };
        }
        return { status: 403 };
    }
    function viewerSnapshot() {
        const kind = imageKind();
        return { access: isAccessProtected() ? 'authorized' : 'open', notice: notice(),
            imageKind: kind, hasImage: kind !== 'none', display: structuredClone(appearance) };
    }
    function state() {
        const preset = SCENARIOS.find(item => item.id === scenarioId);
        const kind = imageKind();
        return {
            scenario: { id: scenarioId, label: preset.label },
            appearance: structuredClone(appearance), options: { ...options },
            delivery: { access: isAccessProtected() ? 'protected' : 'open', notice: notice(),
                imageKind: kind, hasImage: kind !== 'none', orientation, credentials: { pin: PIN, token }, blockedRemainingMs: remainingBlockMs() }
        };
    }

    reset();
    return { selectedJpeg, state, viewerSnapshot, reset, selectScenario, updateAppearance, updateOptions, setOrientation,
        allowsViewing, exchangeToken, verifyPin, remainingBlockMs,
        get credentialGeneration() { return credentialGeneration; } };
}

module.exports = { createPreviewDevice, PreviewDeviceError };
