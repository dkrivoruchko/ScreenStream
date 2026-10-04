// At most one displayed image and one replacement candidate.
function createMedia(stage, callbacks) {
    const delays = [0, 1000, 2000, 4000, 8000];
    let displayed = null, candidate = null, generation = 0;
    let firstTimer = null, probeTimer = null, nextTimer = null, sizeTimer = null;
    let retain = true, hasImage = false, method = 'mjpeg';
    let retries = 0, width = 0, height = 0;
    function discard(image) {
        if (!image) return;
        image.onload = image.onerror = null;
        image.removeAttribute('src');
        if (image.parentNode) image.parentNode.removeChild(image);
    }
    function cancelCandidate() {
        generation += 1;
        clearTimeout(firstTimer); clearTimeout(probeTimer); clearTimeout(nextTimer);
        firstTimer = probeTimer = nextTimer = null;
        discard(candidate); candidate = null;
    }
    function dimensions() {
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
    function failed(id, image) {
        if (id !== generation || (image !== candidate && image !== displayed)) return;
        cancelCandidate();
        if (displayed) displayed.onerror = null;
        if (!retain) clearDisplayed();
        if (!hasImage) return;
        if (retries >= delays.length) { callbacks.state('failed'); return; }
        callbacks.state('retrying');
        nextTimer = setTimeout(start, delays[retries++]);
    }
    function promote(id, image) {
        if (id !== generation || image !== candidate || !image.naturalWidth || !image.naturalHeight) return;
        clearTimeout(firstTimer); clearTimeout(probeTimer);
        firstTimer = probeTimer = null;
        discard(displayed);
        displayed = image; candidate = null;
        image.style.visibility = 'visible';
        image.removeAttribute('aria-hidden');
        image.alt = 'Stream image';
        image.onload = () => { if (id === generation && image === displayed) dimensions(); };
        image.onerror = () => failed(id, image);
        retries = 0;
        width = image.naturalWidth; height = image.naturalHeight;
        callbacks.image(displayed, width, height);
        observeSize();
        callbacks.state('viewing');
        // Poll after promotion so a slow JPEG load delays the next request.
        if (method === 'jpeg') nextTimer = setTimeout(start, 500);
    }
    function start() {
        cancelCandidate();
        if (!hasImage) return;
        const id = generation;
        const image = new Image();
        candidate = image;
        image.className = 'stream-image';
        image.alt = '';
        image.setAttribute('aria-hidden', 'true');
        image.style.visibility = 'hidden';
        stage.appendChild(image);
        if (!displayed) callbacks.state('starting');
        let decoding = false;
        const ready = () => {
            if (id !== generation || image !== candidate || !image.naturalWidth ||
                (method === 'jpeg' && !image.complete)) return;
            // Multipart decode() may never settle. Natural dimensions/load are its first-frame signals.
            if (method === 'jpeg' && typeof image.decode === 'function') {
                if (decoding) return;
                decoding = true;
                image.decode().then(() => promote(id, image), () => failed(id, image));
            } else promote(id, image);
        };
        image.onload = ready;
        image.onerror = () => failed(id, image);
        const probe = () => {
            if (id !== generation || image !== candidate) return;
            ready();
            if (image === candidate) probeTimer = setTimeout(probe, 100);
        };
        firstTimer = setTimeout(() => failed(id, image), 10000);
        image.src = '/' + method + '?requestSequence=' + id;
        probeTimer = setTimeout(probe, 100);
    }
    function configure(snapshot, firstSnapshot) {
        const previousHasImage = hasImage;
        hasImage = (snapshot.access === 'open' || snapshot.access === 'authorized') && snapshot.hasImage;
        retain = !!(snapshot.display && snapshot.display.retainImageOnMediaFailure);
        if (!hasImage) { cancelCandidate(); clearDisplayed(); retries = 0; callbacks.state('idle'); return; }
        if (firstSnapshot || !previousHasImage) { retries = 0; start(); }
        // Ordinary state updates leave healthy and exhausted attempts alone.
    }
    function retry() {
        if (!hasImage) return;
        retries = 0;
        callbacks.state('retrying');
        start();
    }
    function alternate() {
        method = method === 'mjpeg' ? 'jpeg' : 'mjpeg';
        callbacks.method(method);
        retry();
    }
    function pause() { cancelCandidate(); clearDisplayed(); }
    function resume() {
        if (hasImage && !candidate && (!displayed || !displayed.naturalWidth)) retry();
    }
    return { configure, alternate, pause, resume,
        method: () => method };
}
