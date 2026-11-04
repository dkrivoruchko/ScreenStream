// At most one displayed image and one replacement candidate.
function createMedia(stage, imageAlt, callbacks) {
    const delays = [0, 1000, 2000, 4000, 8000];
    let displayed = null, candidate = null, generation = 0;
    let firstTimer = null, nextTimer = null, sizeTimer = null;
    let mediaEligible = false, method = 'mjpeg', imageKind = 'none';
    let retainedWait = false;
    let retries = 0, width = 0, height = 0;
    function discard(image) {
        if (!image) return;
        image.onload = image.onerror = null;
        image.removeAttribute('src');
        if (image.parentNode) image.parentNode.removeChild(image);
    }
    function cancelCandidate() {
        generation += 1;
        clearTimeout(firstTimer); clearTimeout(nextTimer);
        firstTimer = nextTimer = null;
        retainedWait = false;
        discard(candidate); candidate = null;
    }
    function dimensions() {
        // Intrinsic size loss means unavailable image data, not a known transport cause.
        if (displayed && (!displayed.naturalWidth || !displayed.naturalHeight)) {
            displayedUnavailable(displayed);
            return;
        }
        if (displayed && (width !== displayed.naturalWidth || height !== displayed.naturalHeight)) {
            width = displayed.naturalWidth; height = displayed.naturalHeight;
            callbacks.image(displayed, width, height);
        }
    }
    function clearDisplayed() {
        clearTimeout(sizeTimer); sizeTimer = null;
        discard(displayed); displayed = null;
        width = height = 0;
        callbacks.image(null, 0, 0);
    }
    function observeSize() {
        clearTimeout(sizeTimer);
        dimensions();
        if (displayed) sizeTimer = setTimeout(observeSize, 500);
    }
    function retryLater() {
        if (!mediaEligible) return;
        if (retries >= delays.length) { callbacks.state('failed'); return; }
        callbacks.state('retrying');
        nextTimer = setTimeout(start, delays[retries++]);
    }
    function failed(id, image) {
        if (id !== generation || image !== candidate) return;
        cancelCandidate();
        retryLater();
    }
    function displayedUnavailable(image) {
        if (image !== displayed) return;
        clearDisplayed();
        // A replacement already loading or scheduled keeps its own attempt and deadline.
        if (candidate || nextTimer !== null) { callbacks.state('retrying'); return; }
        retryLater();
    }
    function promote(id, image) {
        if (id !== generation || image !== candidate || !image.naturalWidth || !image.naturalHeight) return;
        clearTimeout(firstTimer); firstTimer = null;
        // Give the replacement its final layout before removing the old flow image.
        image.className = 'stream-image';
        discard(displayed);
        displayed = image; candidate = null;
        image.style.visibility = 'visible';
        image.removeAttribute('aria-hidden');
        image.alt = imageAlt;
        image.onload = () => { if (image === displayed) dimensions(); };
        image.onerror = () => displayedUnavailable(image);
        retries = 0;
        width = image.naturalWidth; height = image.naturalHeight;
        callbacks.image(displayed, width, height);
        observeSize();
        callbacks.state('viewing');
        // Live JPEGs remain single-flight; retained frames do not need maximum-speed polling.
        if (method === 'jpeg') {
            if (imageKind === 'live') start();
            else {
                retainedWait = true;
                nextTimer = setTimeout(start, 500);
            }
        }
    }
    function start() {
        cancelCandidate();
        if (!mediaEligible) return;
        const id = generation;
        const image = new Image();
        candidate = image;
        image.className = 'stream-candidate';
        image.alt = '';
        image.setAttribute('aria-hidden', 'true');
        image.style.visibility = 'hidden';
        stage.appendChild(image);
        if (!displayed) callbacks.state('starting');
        let decoding = false;
        const ready = () => {
            if (id !== generation || image !== candidate || !image.naturalWidth || !image.naturalHeight ||
                (method === 'jpeg' && !image.complete)) return;
            // Load admits the initial image; it does not track subsequent multipart frames.
            if (method === 'jpeg') {
                if (decoding) return;
                decoding = true;
                image.decode().then(() => promote(id, image), () => failed(id, image));
            } else promote(id, image);
        };
        image.onload = ready;
        image.onerror = () => failed(id, image);
        // This bounds waiting for an initial usable image, not ongoing stream freshness.
        firstTimer = setTimeout(() => failed(id, image), 10000);
        image.src = '/' + method + '?requestSequence=' + id;
    }
    function apply(value, refresh) {
        const previousMediaEligible = mediaEligible;
        const previousImageKind = imageKind;
        const methodChanged = method !== value.method;
        imageKind = value.imageKind;
        method = value.method;
        mediaEligible = value.eligible;
        if (!mediaEligible) {
            if (previousMediaEligible) { cancelCandidate(); clearDisplayed(); retries = 0; callbacks.state('idle'); }
            return;
        }
        if (refresh || !previousMediaEligible) { retries = 0; start(); }
        else if (methodChanged) retry();
        else if (method === 'jpeg' && previousImageKind === 'retained' && imageKind === 'live' &&
            retainedWait && !candidate) start();
        // Other state updates leave healthy, retrying and exhausted attempts alone.
    }
    function retry() {
        if (!mediaEligible) return;
        retries = 0;
        callbacks.state('retrying');
        start();
    }
    function pause() { cancelCandidate(); clearDisplayed(); }
    function resume() {
        if (mediaEligible && !candidate && (!displayed || !displayed.naturalWidth)) retry();
    }
    return { apply, pause, resume };
}
