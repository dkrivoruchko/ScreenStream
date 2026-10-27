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
    let template = null, build = { revision: 0, bytes: 0, hash: null, error: null };
    const watchers = [];
    let polling = null, buildTimer = null, closed = false;
    let sourceFingerprint = null, watchMode = 'off', watchWarning = null;

    function compile() {
        try {
            const next = buildHtml();
            template = next;
            build = { revision: build.revision + 1, bytes: Buffer.byteLength(next),
                hash: crypto.createHash('sha256').update(next).digest('hex').slice(0, 12), error: null };
            log('Viewer build ' + build.revision + ' ready; reload explicitly to apply it.');
        } catch (error) {
            build = { ...build, error: error.message };
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
        if (closed || polling) return;
        closeWatchers();
        watchMode = 'polling';
        watchWarning = 'Native source watch unavailable (' + (error.code || error.message) + '); polling every second.';
        log(watchWarning);
        polling = setInterval(() => {
            if (fingerprintSources() !== sourceFingerprint) scheduleBuild();
        }, 1000);
    }
    function start() {
        if (closed || !watch || watchers.length || polling) return;
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
        try { generatedCurrent = template !== null && fs.readFileSync(output, 'utf8') === template; } catch (_) { /* Not built yet. */ }
        return { ...build, generatedCurrent, watchMode, watchWarning };
    }
    function close() {
        closed = true;
        closeWatchers(); clearInterval(polling); clearTimeout(buildTimer);
    }

    if (watch) sourceFingerprint = fingerprintSources();
    compile();
    return { start, state, close, get template() { return template; } };
}

module.exports = { createViewerBuild };
