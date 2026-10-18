'use strict';

const DEFAULT_APPEARANCE = Object.freeze({
    title: 'ScreenStream',
    titleEnabled: true,
    background: '#101418',
    titleLayout: Object.freeze({ position: 'top', presentation: 'overlay', autoHide: true }),
    controlsLayout: Object.freeze({ position: 'bottom', presentation: 'overlay', autoHide: true })
});

const DEFAULT_OPTIONS = Object.freeze({ postStopImage: 'logo', imageAvailable: true });

const SCENARIOS = Object.freeze([
    { id: 'normal', label: 'Normal stream' },
    { id: 'pin', label: 'PIN required' },
    { id: 'blocked', label: 'PIN blocked' },
    { id: 'starting', label: 'Capture starting' },
    { id: 'permission_required', label: 'Screen-sharing permission' },
    { id: 'paused', label: 'Stream paused' },
    { id: 'stopped', label: 'Stream stopped' },
    { id: 'failed', label: 'Capture failed' }
].map(Object.freeze));

module.exports = { DEFAULT_APPEARANCE, DEFAULT_OPTIONS, SCENARIOS };
