'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DEFAULT_APPEARANCE, DEFAULT_OPTIONS, SCENARIOS } = require('./scenarios.cjs');

const PIN = '123456';
const BLOCK_DURATION_MS = 20000;

class DeviceError extends Error {}

// One virtual device. Visual presets are projections; viewing credentials have their own lifetime.
function createDevice() {
    const fixtures = {};
    const names = ['logo', ...['portrait', 'landscape'].flatMap(orientation =>
        Array.from({ length: 8 }, (_, frame) => orientation + '-' + frame))];
    for (const name of names) {
        fixtures[name] = fs.readFileSync(path.join(__dirname, 'fixtures', name + '.jpg'));
    }
    let appearance, options, scenarioId, orientation;
    let cookie, accessToken, generation = 0;
    let blockedUntil = 0, wrongAttempts = 0;

    function protectedAccess() { return scenarioId === 'pin' || scenarioId === 'blocked'; }
    function currentCapture() { return scenarioId === 'normal' || protectedAccess(); }
    function notice() { return currentCapture() || scenarioId === 'starting' ? null : scenarioId; }
    function imageKind() {
        if (currentCapture() && options.imageAvailable) return 'live';
        if (options.postStopImage === 'none') return 'none';
        if (options.postStopImage === 'last' && !currentCapture() && options.imageAvailable) return 'retained';
        return 'logo';
    }
    function image() {
        const kind = imageKind();
        if (kind === 'none') return null;
        if (kind === 'logo') return fixtures.logo;
        const frame = kind === 'retained' ? 0 : Math.floor(performance.now() / 250) % 8;
        return fixtures[orientation + '-' + frame];
    }
    function newAccessContext() {
        cookie = crypto.randomBytes(32).toString('hex');
        accessToken = crypto.randomBytes(24).toString('base64url');
        generation += 1;
        wrongAttempts = 0;
        blockedUntil = 0;
    }
    function reset() {
        appearance = structuredClone(DEFAULT_APPEARANCE);
        options = structuredClone(DEFAULT_OPTIONS);
        scenarioId = 'normal'; orientation = 'portrait';
        newAccessContext();
    }
    function selectScenario(id) {
        if (!SCENARIOS.some(item => item.id === id)) throw new DeviceError('Unknown scenario');
        const wasProtected = protectedAccess();
        scenarioId = id;
        if (wasProtected || protectedAccess()) {
            newAccessContext();
            if (id === 'blocked') blockedUntil = performance.now() + BLOCK_DURATION_MS;
            return true;
        }
        return false;
    }
    function updateAppearance(update) {
        const next = structuredClone(appearance);
        for (const [key, value] of Object.entries(update)) {
            if (key === 'title') {
                if (typeof value !== 'string' || value.length > 16384) throw new DeviceError('Title must be text up to 16384 characters');
            } else if (key === 'background') {
                if (typeof value !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(value)) throw new DeviceError('Background must be a six-digit RGB color');
            } else if (key === 'titleEnabled') {
                if (typeof value !== 'boolean') throw new DeviceError(key + ' must be Boolean');
            } else if (key === 'titleLayout' || key === 'controlsLayout') {
                if (!value || typeof value !== 'object' || Array.isArray(value) ||
                    !['top', 'bottom'].includes(value.position) || !['overlay', 'bar'].includes(value.presentation) ||
                    typeof value.autoHide !== 'boolean' || Object.keys(value).some(field => !['position', 'presentation', 'autoHide'].includes(field))) {
                    throw new DeviceError('Invalid ' + key);
                }
            } else throw new DeviceError('Unknown appearance field: ' + key);
            next[key] = structuredClone(value);
        }
        appearance = next;
    }
    function updateOptions(update) {
        const next = { ...options };
        for (const [key, value] of Object.entries(update)) {
            if (key === 'postStopImage') {
                if (!['logo', 'last', 'none'].includes(value)) throw new DeviceError('Invalid inactive image');
            } else if (key === 'imageAvailable') {
                if (typeof value !== 'boolean') throw new DeviceError('Image available must be Boolean');
            } else throw new DeviceError('Unknown device option: ' + key);
            next[key] = value;
        }
        options = next;
    }
    function setOrientation(value) {
        if (!['portrait', 'landscape'].includes(value)) throw new DeviceError('Invalid orientation');
        orientation = value;
    }
    function remainingBlock() { return Math.max(0, Math.ceil(blockedUntil - performance.now())); }
    function allowsViewing(suppliedCookie, suppliedToken) {
        return !protectedAccess() || suppliedCookie === cookie || suppliedToken === accessToken;
    }
    function exchangeToken(supplied) { return protectedAccess() && supplied === accessToken ? cookie : null; }
    function verifyPin(supplied) {
        if (!protectedAccess()) return { status: 204 };
        const remaining = remainingBlock();
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
    function snapshot() {
        const kind = imageKind();
        return { access: protectedAccess() ? 'authorized' : 'open', notice: notice(),
            imageKind: kind, hasImage: kind !== 'none', display: structuredClone(appearance) };
    }
    function state() {
        const preset = SCENARIOS.find(item => item.id === scenarioId);
        const kind = imageKind();
        return {
            scenario: { id: scenarioId, label: preset.label },
            appearance: structuredClone(appearance), options: { ...options },
            delivery: { access: protectedAccess() ? 'protected' : 'open', notice: notice(),
                imageKind: kind, hasImage: kind !== 'none', orientation, credentials: { pin: PIN, accessToken }, blockedRemainingMs: remainingBlock() }
        };
    }

    reset();
    return { image, state, snapshot, reset, selectScenario, updateAppearance, updateOptions, setOrientation,
        allowsViewing, exchangeToken, verifyPin, remainingBlock,
        get generation() { return generation; } };
}

module.exports = { createDevice, DeviceError };
