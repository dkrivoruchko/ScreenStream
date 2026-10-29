const fs = require('node:fs');
const path = require('node:path');
const babel = require('@babel/core');
const acorn = require('acorn');

const sourceDirectory = path.join(__dirname, 'src');
const output = path.join(__dirname, '../../src/main/assets/mjpeg/index.html');
const scriptFiles = ['strings.js', 'connection.js', 'media.js', 'picture-in-picture.js', 'viewport.js', 'access.js', 'presentation.js', 'page.js'];
const localeIds = ['en', 'af', 'am', 'ar', 'bn', 'de', 'es', 'eu', 'fr', 'hi', 'id', 'it', 'ja', 'jv', 'ka', 'nl', 'pl', 'pt', 'ru', 'tr', 'uk', 'ur', 'uz', 'zh', 'zh-TW'];
const localeFiles = localeIds.map(id => 'locales/' + id + '.json');
const sourceFiles = Object.freeze([...scriptFiles, 'style.css', 'index.html', ...localeFiles]);
const logoPath = path.join(__dirname, '../../src/main/assets/mjpeg/screenstream-logo.jpg');
const inputPaths = Object.freeze([...sourceFiles.map(name => path.join(sourceDirectory, name)), logoPath]);

function buildHtml() {
    const read = name => fs.readFileSync(path.join(sourceDirectory, name), 'utf8');
    const catalogs = {};
    let messageKeys;
    for (const id of localeIds) {
        const catalog = JSON.parse(read('locales/' + id + '.json'));
        const expectedLanguage = id === 'zh' ? 'zh-Hans' : id === 'zh-TW' ? 'zh-Hant' : id;
        if (!catalog || Object.keys(catalog).sort().join(',') !== 'dir,lang,messages' ||
            catalog.lang !== expectedLanguage || catalog.dir !== (id === 'ar' || id === 'ur' ? 'rtl' : 'ltr') ||
            !catalog.messages || typeof catalog.messages !== 'object' || Array.isArray(catalog.messages)) {
            throw new Error('Invalid locale catalog: ' + id);
        }
        const keys = Object.keys(catalog.messages);
        if (id === 'en') messageKeys = keys;
        if (keys.join(',') !== messageKeys.join(',')) throw new Error('Locale keys/order differ from English: ' + id);
        for (const key of keys) {
            const value = catalog.messages[key];
            const expected = key === 'countdownSeconds' || key === 'pinBlockedAnnouncementSeconds' ? '{seconds}' : '';
            if (typeof value !== 'string' || !value.trim() ||
                (value.match(/\{[^{}]*\}/g) || []).join(',') !== expected ||
                /[{}]/.test(value.replace(/\{seconds\}/g, ''))) {
                throw new Error('Invalid locale message or placeholders: ' + id + '.' + key);
            }
        }
        for (const [key, value] of Object.entries({ app: 'ScreenStream', mjpeg: 'MJPEG', jpeg: 'JPEG' })) {
            if (catalog.messages[key] !== value) throw new Error('Changed product/format name: ' + id + '.' + key);
        }
        catalogs[id] = catalog;
    }
    const source = 'const catalogs = ' + JSON.stringify(catalogs) + ';\n' + scriptFiles.map(read).join('\n');
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
    for (const marker of ['/* INLINE_STYLE */', '/* INLINE_SCRIPT */', '/* INLINE_LOGO */']) {
        const count = template.split(marker).length - 1;
        if (count !== 1) throw new Error('Expected exactly one ' + marker + ' template marker; found ' + count + '.');
    }
    const html = template.replace('/* INLINE_STYLE */', () => read('style.css'))
        .replace('/* INLINE_SCRIPT */', () => script.replace(/<\/script/gi, match => '<\\/' + match.slice(2))
            .replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029'))
        .replace('/* INLINE_LOGO */', () => 'data:image/jpeg;base64,' +
            fs.readFileSync(logoPath).toString('base64'));
    if (/\/\* INLINE_(STYLE|SCRIPT|LOGO) \*\//.test(html)) throw new Error('Unresolved template marker');
    return html;
}

module.exports = { buildHtml, inputPaths, output };

if (require.main === module) {
    const html = buildHtml();
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
}
