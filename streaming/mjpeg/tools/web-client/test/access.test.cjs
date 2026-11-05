const assert = require('node:assert/strict');
const { test } = require('node:test');
const { runtime } = require('./runtime.cjs');

function access(t) {
    const elements = new Map(), documentListeners = new Map(), windowListeners = new Map();
    let pageFocused = true;
    const submissions = [];
    let retries = 0;
    const document = {
        hidden: false,
        hasFocus: () => pageFocused,
        addEventListener: (name, callback) => documentListeners.set(name, callback)
    };
    function element(id, parent = null) {
        const classes = new Set(), listeners = new Map();
        const node = {
            parent, style: {}, attributes: {}, focusCalls: 0, writes: [],
            classList: { add: name => classes.add(name), remove: name => classes.delete(name), contains: name => classes.has(name) },
            setAttribute(name, value) { this.attributes[name] = value; },
            addEventListener: (name, callback) => listeners.set(name, callback),
            emit: (name, event) => listeners.get(name)?.(event),
            setSelectionRange(start, end) { this.selectionStart = start; this.selectionEnd = end; },
            contains(other) {
                for (let current = other; current; current = current.parent) if (current === this) return true;
                return false;
            },
            focus() {
                this.focusCalls += 1;
                if (this.disabled) return;
                for (let current = this; current; current = current.parent) if (current.hidden) return;
                document.activeElement = this;
                documentListeners.get('focusin')?.({ target: this });
            }
        };
        let hidden = false;
        Object.defineProperty(node, 'hidden', {
            get: () => hidden,
            set(value) {
                hidden = value;
                // Hiding the focused entry removes its descendants from the focus chain.
                if (value && node.contains(document.activeElement)) document.activeElement = document.body;
            }
        });
        elements.set(id, node);
        return node;
    }
    document.body = element('body');
    document.activeElement = document.body;
    const panel = element('pin-panel', document.body);
    panel.hidden = true;
    const entry = element('pin-entry', panel);
    const input = element('pin', entry), unlock = element('unlock', entry);
    for (const id of ['pin-title', 'pin-message', 'pin-error', 'pin-block-copy', 'pin-countdown', 'pin-logo', 'pin-retry', 'pin-countdown-value', 'pin-form']) element(id, panel);
    const elsewhere = element('elsewhere', document.body), message = elements.get('pin-message');
    const words = { pinTitle: 'Enter PIN', pinBlockedTitle: 'PIN entry temporarily blocked', pinBlockedAnnouncementSecond: 'Wait 1 second.', pinSending: 'Sending PIN', pinNetwork: 'Connection failed' };
    const { createAccessPanel } = runtime(t, 'access', {
        document,
        window: { addEventListener: (name, callback) => windowListeners.set(name, callback) },
        byId: id => elements.get(id),
        text: (node, value) => { node.textContent = value; node.writes.push(value); },
        words, locale: { dir: 'ltr' }, secondsText: (key, seconds) => `${key}: ${seconds}`
    });
    const owner = createAccessPanel({
        bounds: () => ({ left: 0, top: 0, width: 800, height: 600 }),
        keepFocusVisible() {}, watch() {}
    }, { submit: value => submissions.push(value), retry: () => { retries += 1; } });
    owner.start();
    return {
        owner, document, panel, entry, input, unlock, elsewhere, message, words, submissions,
        form: elements.get('pin-form'), retryButton: elements.get('pin-retry'), retries: () => retries,
        render: (mode = 'pin_required', seconds = 0) => owner.render({ visible: true, mode, seconds, result: null }),
        hide: () => owner.render({ visible: false, mode: null, seconds: 0, result: null }),
        event: name => documentListeners.get(name)?.({ target: document.body }),
        blur: () => { pageFocused = false; windowListeners.get('blur')(); },
        focusPage: () => { pageFocused = true; },
        setPageFocused: value => { pageFocused = value; }
    };
}

for (const focusedControl of ['input', 'unlock']) test(`block expiry restores the hidden ${focusedControl} task once and announces readiness`, t => {
    const h = access(t);
    h.render();
    h[focusedControl].focus();
    const before = h.input.focusCalls;
    h.render('blocked', 2);
    assert.equal(h.document.activeElement, h.document.body);
    assert.equal(h.entry.hidden, true);
    const announcements = h.message.writes.length;
    h.render('blocked', 1);
    assert.equal(h.input.focusCalls, before);
    assert.equal(h.message.writes.length, announcements);
    h.render();
    assert.equal(h.document.activeElement, h.input);
    assert.equal(h.input.focusCalls, before + 1);
    assert.equal(h.input.disabled, false);
    assert.equal(h.entry.hidden, false);
    assert.equal(h.message.textContent, h.words.pinTitle);
    assert.equal(h.message.hidden, false);
    assert.equal(h.message.classList.contains('sr-only'), true);
    const readyAnnouncements = h.message.writes.length;
    h.render();
    assert.equal(h.input.focusCalls, before + 1);
    assert.equal(h.message.writes.length, readyAnnouncements);
});

test('focus moved elsewhere during a block is not reclaimed, even if it later falls back to body', t => {
    const h = access(t);
    h.render();
    h.render('blocked', 2);
    h.elsewhere.focus();
    h.document.activeElement = h.document.body;
    h.render();
    assert.equal(h.input.focusCalls, 1);
    assert.equal(h.document.activeElement, h.document.body);
    assert.equal(h.message.textContent, h.words.pinTitle);
});

test('an unrelated focused control before the block does not arm PIN restoration', t => {
    const h = access(t);
    h.render();
    h.elsewhere.focus();
    h.render('blocked', 1);
    h.document.activeElement = h.document.body;
    h.render();
    assert.equal(h.input.focusCalls, 1);
    assert.equal(h.document.activeElement, h.document.body);
});

for (const state of ['hidden', 'unfocused']) test(`a delayed block response while ${state} cannot re-arm restoration after return`, t => {
    const h = access(t);
    h.render();
    if (state === 'hidden') {
        h.document.hidden = true;
        h.event('visibilitychange');
    } else h.blur();
    assert.equal(h.document.activeElement, h.input);
    h.render('blocked', 2);
    h.document.hidden = false;
    h.focusPage();
    h.render('blocked', 1);
    h.render();
    assert.equal(h.input.focusCalls, 1);
    assert.equal(h.document.activeElement, h.document.body);
    assert.equal(h.message.textContent, h.words.pinTitle);
});

test('hiding the flow clears restoration before a new blocked panel is shown', t => {
    const h = access(t);
    h.render();
    h.render('blocked', 2);
    h.hide();
    h.render('blocked', 1);
    h.render();
    assert.equal(h.input.focusCalls, 1);
    assert.equal(h.document.activeElement, h.document.body);
});

for (const interaction of ['mousedown', 'touchstart', 'visibilitychange', 'blur', 'resume']) test(`${interaction} cancels deferred focus restoration`, t => {
    const h = access(t);
    h.render();
    h.render('blocked', 2);
    if (interaction === 'blur') { h.blur(); h.focusPage(); }
    else if (interaction === 'resume') h.owner.resume();
    else {
        if (interaction === 'visibilitychange') h.document.hidden = true;
        h.event(interaction);
        h.document.hidden = false;
    }
    h.render();
    assert.equal(h.input.focusCalls, 1);
    assert.equal(h.document.activeElement, h.document.body);
});

for (const state of ['hidden', 'unfocused']) test(`a page that is ${state} is never focused when the block expires`, t => {
    const h = access(t);
    h.render();
    h.render('blocked', 1);
    if (state === 'hidden') h.document.hidden = true;
    else h.setPageFocused(false);
    h.render();
    assert.equal(h.input.focusCalls, 1);
    h.document.hidden = false;
    h.focusPage();
    h.render();
    assert.equal(h.input.focusCalls, 1);
});

test('a block can recover when the browser reports no active element', t => {
    const h = access(t);
    h.render();
    h.render('blocked', 1);
    h.document.activeElement = null;
    h.render();
    assert.equal(h.document.activeElement, h.input);
    assert.equal(h.input.focusCalls, 2);
});

test('PIN input and selected paste preserve six ASCII digits and the selection', t => {
    const h = access(t);
    h.render();
    h.input.value = '0a12b3456';
    h.input.setSelectionRange(5, 8);
    h.input.emit('input');
    assert.equal(h.input.value, '012345');
    assert.equal(h.input.selectionStart, 3);
    assert.equal(h.input.selectionEnd, 6);
    h.input.setSelectionRange(2, 4);
    let prevented = false;
    h.input.emit('paste', {
        preventDefault() { prevented = true; },
        clipboardData: { getData: () => '9a876' }
    });
    assert.equal(prevented, true);
    assert.equal(h.input.value, '019845');
    assert.equal(h.input.selectionStart, 4);
    assert.equal(h.input.selectionEnd, 4);
});

test('PIN form submits only while visible and ready, and retry invokes its action', t => {
    const h = access(t);
    const submit = () => {
        let prevented = false;
        h.form.emit('submit', { preventDefault() { prevented = true; } });
        assert.equal(prevented, true);
    };
    h.render();
    h.input.value = '012345';
    submit();
    assert.deepEqual(h.submissions, ['012345']);
    h.render('blocked', 1);
    submit();
    h.render();
    h.owner.render({ visible: true, mode: 'pin_required', seconds: 0, result: { key: 'pinSending' } });
    assert.equal(h.unlock.disabled, true);
    submit();
    h.hide();
    submit();
    assert.deepEqual(h.submissions, ['012345']);
    h.owner.render({ visible: true, mode: 'pin_required', seconds: 0, result: { key: 'pinNetwork' } });
    assert.equal(h.retryButton.hidden, false);
    h.retryButton.emit('click');
    assert.equal(h.retries(), 1);
});
