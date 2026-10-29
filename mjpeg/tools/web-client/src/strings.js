const localeScripts = {
    en: 'latn', af: 'latn', am: 'ethi', ar: 'arab', bn: 'beng', de: 'latn',
    es: 'latn', eu: 'latn', fr: 'latn', hi: 'deva', id: 'latn', it: 'latn',
    ja: 'jpan', jv: 'latn', ka: 'geor', nl: 'latn', pl: 'latn', pt: 'latn',
    ru: 'cyrl', tr: 'latn', uk: 'cyrl', ur: 'arab', uz: 'latn'
};
function chooseLocale(preferences) {
    for (let i = 0; i < preferences.length; i += 1) {
        if (typeof preferences[i] !== 'string') continue;
        const tag = preferences[i].toLowerCase().replace(/_/g, '-');
        if (!/^[a-z]{2,3}(?:-[a-z0-9]{1,8})*$/.test(tag)) continue;
        const parts = tag.split('-');
        let language = parts[0];
        if (language === 'in') language = 'id';
        if (language === 'jw') language = 'jv';
        const script = /^[a-z]{4}$/.test(parts[1] || '') ? parts[1] : '';
        const region = parts[script ? 2 : 1] || '';
        if (language === 'zh') {
            if (script && script !== 'hans' && script !== 'hant') continue;
            return script === 'hant' || !script && (region === 'tw' || region === 'hk' || region === 'mo') ? 'zh-TW' : 'zh';
        }
        if (Object.prototype.hasOwnProperty.call(localeScripts, language) &&
            (!script || script === localeScripts[language])) return language;
    }
    return 'en';
}
const preferences = navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language];
const locale = catalogs[chooseLocale(preferences)] || catalogs.en;
const words = {};
for (const key in catalogs.en.messages) {
    if (Object.prototype.hasOwnProperty.call(catalogs.en.messages, key)) {
        words[key] = typeof locale.messages[key] === 'string' ? locale.messages[key] : catalogs.en.messages[key];
    }
}
document.documentElement.lang = locale.lang;
document.documentElement.dir = locale.dir;
function text(node, value) { node.textContent = value; }
function byId(id) { return document.getElementById(id); }
function secondsText(key, seconds) {
    return words[key].replace('{seconds}', () => String(seconds));
}
function labelControl(node, value) {
    node.setAttribute('aria-label', value);
    node.setAttribute('data-tooltip', value);
}
