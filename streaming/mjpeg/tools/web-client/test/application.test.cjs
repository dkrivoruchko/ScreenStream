const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createViewerTestContext, createWebSnapshot } = require('./viewer-test-support.cjs');

function createApplicationHarness(t) {
    const { createApplication } = createViewerTestContext(t, 'application');
    const calls = [], models = [], pins = [];
    let healthy = true;
    const record = name => (...args) => calls.push({ name, args });
    const ports = {
        clock: { now: () => Date.now(), setTimeout, clearTimeout },
        connection: { retry: record('connect'), pause: record('connectionPause'), submitPin: record('submit'), healthy: () => healthy },
        media: { apply: record('apply'), pause: record('mediaPause'), resume: record('mediaResume') },
        access: { render: value => pins.push(value), resume: record('accessResume') },
        presentation: {
            render: value => models.push(value), configure: record('configure'), revokeFrame: record('revoke'),
            setFrame: record('frame'), accessDenied: record('denied'), closeMenu: record('closeMenu'),
            pause: record('presentationPause'), resume: record('presentationResume'), activity: record('activity')
        }
    };
    const app = createApplication(ports);
    return {
        app, ports, calls, models, pins,
        last: name => calls.filter(call => call.name === name).at(-1),
        count: name => calls.filter(call => call.name === name).length,
        model: () => models.at(-1), pin: () => pins.at(-1),
        unhealthy: () => { healthy = false; }
    };
}

test('admission requires a fresh authorized snapshot after PIN success or block expiry', t => {
    const h = createApplicationHarness(t);
    h.app.start();
    assert.equal(h.model().connection, 'connecting');
    assert.equal(h.count('apply'), 0);
    h.app.snapshot({ access: 'pin_required' });
    assert.equal(h.pin().visible, true);
    assert.equal(h.last('apply').args[0].eligible, false);
    h.app.submitPin('012345');
    assert.equal(h.last('submit').args[0], '012345');
    h.app.pinResult('pinSending');
    h.app.submitPin('012345');
    assert.equal(h.count('submit'), 1);
    h.app.pinResult('success');
    h.app.frame({}, 640, 360);
    assert.equal(h.count('frame'), 0);
    assert.equal(h.last('apply').args[0].eligible, false);
    h.app.snapshot(createWebSnapshot({ access: 'authorized' }));
    assert.equal(h.last('apply').args[0].eligible, true);
    assert.equal(h.pin().visible, false);

    h.app.snapshot({ access: 'blocked', retryAfterMs: 1500 });
    h.app.submitPin('012345');
    assert.equal(h.count('submit'), 1);
    assert.equal(h.pin().seconds, 2);
    t.mock.timers.tick(1000);
    assert.equal(h.pin().seconds, 1);
    t.mock.timers.tick(1000);
    assert.equal(h.pin().mode, 'pin_required');
    assert.equal(h.last('apply').args[0].eligible, false);
    h.app.frame({}, 640, 360);
    assert.equal(h.count('frame'), 0);
    h.app.snapshot({ access: 'blocked' });
    assert.equal(h.pin().seconds, 1);
    t.mock.timers.tick(1000);
    assert.equal(h.pin().mode, 'pin_required');
    assert.equal(h.last('apply').args[0].eligible, false);
});

test('reconnect keeps the frame and refreshes identical authorized media exactly once', t => {
    const h = createApplicationHarness(t), value = createWebSnapshot(), image = {};
    h.app.start();
    h.app.snapshot(value);
    h.app.frame(image, 640, 360);
    h.app.mediaState('viewing');
    h.app.selectMethod('jpeg');
    const revoked = h.count('revoke');
    h.app.connectionState('reconnecting');
    assert.equal(h.count('revoke'), revoked);
    assert.equal(h.model().status.empty, false);
    assert.equal(h.model().status.key, 'reconnecting');
    h.app.snapshot(value);
    assert.equal(h.last('apply').args[1], true);
    assert.equal(h.last('apply').args[0].method, 'jpeg');
    h.app.snapshot(value);
    assert.equal(h.last('apply').args[1], false);
    h.app.retry();
    assert.equal(h.count('revoke'), revoked);
    assert.equal(h.last('closeMenu').args[0], false);
    h.app.snapshot(value);
    assert.equal(h.last('apply').args[1], true);
    assert.equal(h.last('frame').args[0], image);
});

test('retained capture notices permit media while logo, none and denial revoke it', t => {
    const h = createApplicationHarness(t);
    h.app.start();
    for (const notice of ['stopped', 'permission_required', 'paused', 'failed']) {
        h.app.snapshot(createWebSnapshot({ imageKind: 'retained', notice }));
        assert.equal(h.last('apply').args[0].eligible, true);
        assert.equal(h.last('apply').args[0].imageKind, 'retained');
        assert.equal(h.model().notice, notice);
    }
    for (const imageKind of ['logo', 'none']) {
        const before = h.count('revoke');
        h.app.snapshot(createWebSnapshot({ imageKind, hasImage: imageKind !== 'none' }));
        assert.equal(h.last('apply').args[0].eligible, false);
        assert.equal(h.count('revoke'), before + 1);
    }
    h.app.snapshot({ access: 'pin_required' });
    assert.equal(h.count('denied'), 1);
    assert.equal(h.last('apply').args[0].eligible, false);
    assert.equal(h.model().methodEnabled, false);
});

test('pause releases owners, ignores stale reports and revalidates before restoring media', t => {
    const h = createApplicationHarness(t);
    h.app.start();
    h.app.snapshot(createWebSnapshot());
    h.app.selectMethod('jpeg');
    h.app.pause();
    h.app.pause();
    for (const name of ['revoke', 'connectionPause', 'mediaPause', 'presentationPause']) assert.equal(h.count(name), 1);
    const applied = h.count('apply'), frames = h.count('frame');
    h.app.snapshot(createWebSnapshot());
    h.app.connectionState('connected');
    h.app.pinResult('success');
    h.app.frame({}, 640, 360);
    h.app.submitPin('012345');
    assert.equal(h.count('apply'), applied);
    assert.equal(h.count('frame'), frames);
    assert.equal(h.count('submit'), 0);
    h.app.resume();
    h.app.resume();
    assert.equal(h.count('connect'), 2);
    assert.equal(h.count('accessResume'), 1);
    assert.equal(h.count('presentationResume'), 1);
    h.app.frame({}, 640, 360);
    assert.equal(h.count('frame'), frames);
    h.app.snapshot(createWebSnapshot());
    assert.equal(h.last('apply').args[0].eligible, true);
    assert.equal(h.last('apply').args[0].method, 'jpeg');
    assert.equal(h.last('apply').args[1], true);
});

test('PIN feedback survives repeated denial and a paused block keeps its deadline', t => {
    const h = createApplicationHarness(t);
    h.app.start();
    h.app.snapshot({ access: 'pin_required' });
    h.app.pinResult('pinWrong');
    h.app.snapshot({ access: 'pin_required' });
    assert.equal(h.pin().result.key, 'pinWrong');
    h.app.pinResult('pinNetwork');
    h.app.snapshot({ access: 'pin_required' });
    assert.equal(h.pin().result, null);
    h.app.pinResult('pinSending');
    h.app.pause();
    assert.equal(h.pin().result, null);
    h.app.resume();
    h.app.snapshot({ access: 'blocked', retryAfterMs: 5000 });
    t.mock.timers.tick(1000);
    h.app.pause();
    const renders = h.pins.length;
    t.mock.timers.tick(5000);
    assert.equal(h.pins.length, renders);
    h.app.resume();
    assert.equal(h.pin().mode, 'pin_required');
    assert.equal(h.pin().seconds, 0);
    assert.equal(h.last('apply').args[0].eligible, false);
});

test('visibility return resumes missing media or reconnects without revoking the frame', t => {
    const h = createApplicationHarness(t);
    h.app.start();
    h.app.snapshot(createWebSnapshot());
    h.app.frame({}, 640, 360);
    h.app.visibility(true);
    t.mock.timers.tick(30000);
    h.app.visibility(false);
    assert.equal(h.count('connect'), 1);
    assert.equal(h.count('mediaResume'), 1);
    h.app.visibility(true);
    t.mock.timers.tick(30001);
    h.app.visibility(false);
    assert.equal(h.count('connect'), 2);
    h.unhealthy();
    h.app.visibility(true);
    t.mock.timers.tick(1);
    h.app.visibility(false);
    assert.equal(h.count('connect'), 3);
    assert.equal(h.count('revoke'), 0);
    h.app.pause();
    h.app.visibility(false);
    assert.equal(h.count('connect'), 4);
    assert.equal(h.count('presentationResume'), 1);
});

test('primary messages clear transient notices so recovery cannot resurrect them', t => {
    const h = createApplicationHarness(t);
    h.app.start();
    h.app.connectionState('unsupported');
    assert.equal(h.model().status.key, 'unsupported');
    h.app.snapshot(createWebSnapshot());
    assert.equal(h.model().status.key, 'mediaStarting');
    h.app.frame({}, 640, 360);
    h.app.mediaState('viewing');
    h.app.notice('fullscreenError');
    assert.equal(h.model().status.key, 'fullscreenError');
    t.mock.timers.tick(9999);
    assert.equal(h.model().status.key, 'fullscreenError');
    t.mock.timers.tick(1);
    assert.equal(h.model().status.key, '');
    h.app.notice('pipError');
    h.app.snapshot(createWebSnapshot({ notice: 'stopped', imageKind: 'retained' }));
    assert.equal(h.model().status.key, 'stopped');
    h.app.mediaState('retrying');
    assert.equal(h.model().status.key, 'mediaRetrying');
    h.app.mediaState('viewing');
    assert.equal(h.model().status.key, 'stopped');
    h.app.snapshot(createWebSnapshot());
    assert.equal(h.model().status.key, '');
    h.app.mediaState('failed');
    assert.equal(h.model().status.key, 'mediaFailed');
    assert.equal(h.model().status.retry, true);
    assert.equal(h.model().status.alternate, true);
    h.app.notice('pipError');
    h.app.connectionState('reconnecting');
    assert.equal(h.model().status.key, 'reconnecting');
    h.app.snapshot({ access: 'pin_required' });
    assert.equal(h.model().status.access, true);
    assert.equal(h.model().status.key, '');
    h.app.snapshot(createWebSnapshot());
    h.app.mediaState('viewing');
    assert.equal(h.model().status.key, '');
});

test('synchronous owner reports settle before the application renders the transaction', t => {
    const h = createApplicationHarness(t), image = {};
    h.ports.connection.retry = () => h.app.connectionState('connecting');
    h.ports.media.apply = value => {
        if (value.eligible) {
            h.app.mediaState('viewing');
            h.app.frame(image, 640, 360);
        } else {
            h.app.mediaState('idle');
            h.app.frame(null, 0, 0);
        }
    };
    h.app.start();
    const renders = h.models.length;
    h.app.snapshot(createWebSnapshot());
    assert.equal(h.models.length, renders + 1);
    assert.equal(h.model().image, 'viewing');
    assert.equal(h.model().width, 640);
    assert.equal(h.model().status.key, '');
    assert.equal(h.last('frame').args[0], image);
    h.app.snapshot({ access: 'pin_required' });
    assert.equal(h.model().image, 'unknownSize');
    assert.equal(h.model().width, 0);
    assert.equal(h.model().status.access, true);
});
