const viewportLayout = createViewportLayout();
let application;
const access = createAccessPanel(viewportLayout, {
    submit: pin => application.submitPin(pin),
    retry: () => application.retry()
});
const presentation = createPresentation(viewportLayout, {
    notice: key => application.notice(key),
    retry: () => application.retry(),
    switchMethod: () => application.switchMethod(),
    selectMethod: value => application.selectMethod(value)
});
const media = createMedia(byId('image-stage'), words.streamImageAlt, {
    state: value => application.mediaState(value),
    image: (value, width, height) => application.frame(value, width, height)
});
const connection = createConnection({
    state: value => application.connectionState(value),
    snapshot: value => application.snapshot(value),
    pin: value => application.pinResult(value)
});
application = createApplication({
    connection, media, access, presentation,
    clock: { now: () => Date.now(), setTimeout: (callback, delay) => setTimeout(callback, delay), clearTimeout: timer => clearTimeout(timer) }
});
window.addEventListener('pagehide', application.pause);
window.addEventListener('pageshow', application.resume);
document.addEventListener('freeze', application.pause);
document.addEventListener('resume', application.resume);
document.addEventListener('visibilitychange', () => application.visibility(document.hidden));
access.start();
presentation.start();
viewportLayout.start();
application.start();
