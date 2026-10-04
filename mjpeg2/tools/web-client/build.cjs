const fs = require('node:fs');
const path = require('node:path');
const babel = require('@babel/core');
const acorn = require('acorn');

const read = name => fs.readFileSync(path.join(__dirname, 'src', name), 'utf8');
const source = ['strings.js', 'connection.js', 'media.js', 'presentation.js', 'page.js']
    .map(read).join('\n');
const script = babel.transformSync('(function () {\n"use strict";\n' + source + '\n}());', {
    babelrc: false,
    configFile: false,
    presets: [[require.resolve('@babel/preset-env'), {
        targets: { chrome: '49', firefox: '45', safari: '9.1', ios: '9.3' },
        modules: false,
        forceAllTransforms: true,
        useBuiltIns: false,
        bugfixes: true
    }]]
}).code;
acorn.parse(script, { ecmaVersion: 5 });
const template = read('index.html');
for (const marker of ['/* INLINE_STYLE */', '/* INLINE_SCRIPT */']) {
    const count = template.split(marker).length - 1;
    if (count !== 1) throw new Error('Expected exactly one ' + marker + ' template marker; found ' + count + '.');
}
const html = template.replace('/* INLINE_STYLE */', () => read('style.css'))
    .replace('/* INLINE_SCRIPT */', () => script.replace(/<\/script/gi, '<\\/script'));
if (/\/\* INLINE_(STYLE|SCRIPT) \*\//.test(html)) throw new Error('Unresolved template marker');
const output = path.join(__dirname, '../../src/main/assets/mjpeg/index.html');
if (process.argv.includes('--check')) {
    if (!fs.existsSync(output) || fs.readFileSync(output, 'utf8') !== html) {
        throw new Error('Generated HTML is stale. Run node build.cjs.');
    }
    console.log('Generated HTML is current; JavaScript parsed as ES5.');
} else {
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, html);
    console.log('Built single HTML (' + Buffer.byteLength(html) + ' bytes), JavaScript parsed as ES5.');
}
