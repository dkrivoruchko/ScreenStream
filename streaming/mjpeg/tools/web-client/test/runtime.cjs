const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function runtime(t, files, globals = {}) {
    t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1700000000000 });
    const context = vm.createContext({ Date, setTimeout, clearTimeout, ...globals });
    for (const file of [].concat(files)) {
        const filename = path.join(__dirname, '../src', file + '.js');
        new vm.Script('"use strict";\n' + fs.readFileSync(filename, 'utf8'), { filename }).runInContext(context);
    }
    return context;
}

function snapshot(overrides = {}) {
    return {
        access: 'open', notice: null, hasImage: true, imageKind: 'live',
        display: {
            title: 'Test device', titleEnabled: true, background: '#101418',
            titleLayout: { position: 'top', presentation: 'overlay', autoHide: true },
            controlsLayout: { position: 'bottom', presentation: 'overlay', autoHide: true }
        },
        ...overrides
    };
}

function deferred() {
    let resolve, reject;
    const promise = new Promise((accept, fail) => { resolve = accept; reject = fail; });
    return { promise, resolve, reject };
}

module.exports = { runtime, snapshot, deferred };
