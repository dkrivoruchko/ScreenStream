'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { buildHtml, inputPaths, output } = require('../build.cjs');

const watchedDirectories = new Map();
for (const input of inputPaths) {
    const directory = path.dirname(input);
    if (!watchedDirectories.has(directory)) watchedDirectories.set(directory, new Set());
    watchedDirectories.get(directory).add(path.basename(input));
}

// In-memory preview builds never write the packaged Android asset.
function createViewerBuild({ watch, log }) {
    let html = null, buildState = { revision: 0, bytes: 0, hash: null, error: null };
    const watchers = [];
    let pollingTimer = null, buildTimer = null, closed = false;
    let sourceFingerprint = null, watchMode = 'off', watchWarning = null;

    function compile() {
        try {
            const nextHtml = buildHtml();
            html = nextHtml;
            buildState = { revision: buildState.revision + 1, bytes: Buffer.byteLength(nextHtml),
                hash: crypto.createHash('sha256').update(nextHtml).digest('hex').slice(0, 12), error: null };
            log('Viewer build ' + buildState.revision + ' ready; reload explicitly to apply it.');
        } catch (error) {
            buildState = { ...buildState, error: error.message };
            log('Viewer build failed: ' + error.message);
        }
    }
    function fingerprintSources() {
        const hash = crypto.createHash('sha256');
        for (const input of inputPaths) {
            hash.update(input).update('\0');
            try { hash.update(fs.readFileSync(input)); }
            catch (error) { hash.update('Unreadable source: ' + error.code); }
            hash.update('\0');
        }
        return hash.digest('hex');
    }
    function scheduleBuild() {
        if (closed) return;
        clearTimeout(buildTimer);
        buildTimer = setTimeout(() => {
            buildTimer = null;
            if (closed) return;
            const next = fingerprintSources();
            if (next !== sourceFingerprint) {
                sourceFingerprint = next;
                compile();
            }
        }, 100);
    }
    function closeWatchers() { for (const watcher of watchers.splice(0)) watcher.close(); }
    function usePolling(error) {
        if (closed || pollingTimer) return;
        closeWatchers();
        watchMode = 'polling';
        watchWarning = 'Native source watch unavailable (' + (error.code || error.message) + '); polling every second.';
        log(watchWarning);
        pollingTimer = setInterval(() => {
            if (fingerprintSources() !== sourceFingerprint) scheduleBuild();
        }, 1000);
    }
    function start() {
        if (closed || !watch || watchers.length || pollingTimer) return;
        try {
            for (const [directory, names] of watchedDirectories) {
                const watcher = fs.watch(directory, (_, name) => {
                    if (!name || names.has(String(name))) scheduleBuild();
                });
                watchers.push(watcher);
                watcher.on('error', usePolling);
            }
            watchMode = 'native';
        } catch (error) { usePolling(error); }
        // Catch an input edit made after the initial build but before watching began.
        scheduleBuild();
    }
    function state() {
        let generatedCurrent = false;
        try { generatedCurrent = html !== null && fs.readFileSync(output, 'utf8') === html; } catch (_) { /* Not built yet. */ }
        return { ...buildState, generatedCurrent, watchMode, watchWarning };
    }
    function close() {
        closed = true;
        closeWatchers(); clearInterval(pollingTimer); clearTimeout(buildTimer);
    }

    if (watch) sourceFingerprint = fingerprintSources();
    compile();
    return { start, state, close, get html() { return html; } };
}

module.exports = { createViewerBuild };
