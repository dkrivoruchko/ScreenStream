const words = {
    app: 'ScreenStream', connecting: 'Connecting…', connected: 'Connected',
    reconnecting: 'Reconnecting…', unavailable: 'Server unavailable. Retry the connection.',
    mediaStarting: 'Waiting for an image…', mediaRetrying: 'Restoring image…',
    mediaFailed: 'Image unavailable. Retry or choose another viewing method.', viewing: 'Viewing',
    pinTitle: 'Enter PIN',
    pinRequired: 'Enter the six-digit PIN shown on the streaming device:',
    pinBlockedTitle: 'Access temporarily blocked',
    pinBlocked: 'Too many incorrect PIN attempts.\nPlease wait before trying again.',
    tryAgainIn: 'Try again in', secondsShort: ' s',
    pinWrong: 'Incorrect PIN. Try again.', pinInvalid: 'Enter six digits (0–9).',
    pinSending: 'Checking PIN…', pinNetwork: 'Could not check PIN. Retry the connection.',
    retry: 'Retry connection', useJpeg: 'Use JPEG', useMjpeg: 'Use MJPEG',
    mjpeg: 'MJPEG', jpeg: 'JPEG',
    mjpegDescription: 'Recommended',
    jpegDescription: 'Use if MJPEG won’t load.',
    fit: 'Fit', original: 'Original size', width: 'Fit to width',
    fullscreen: 'Full screen', leaveFullscreen: 'Exit full screen', pip: 'Picture in picture',
    pipReady: 'Picture in picture is ready. Press Picture in picture again to open it.',
    pipError: 'Picture in picture could not open. Normal viewing continues.',
    fullscreenError: 'Full screen could not open. Normal viewing continues.',
    more: 'More',
    privacy: 'Privacy Policy', terms: 'Terms & Conditions',
    unlock: 'Connect',
    connection: 'Connection', status: 'Status', image: 'Image',
    size: 'Dimensions', unknownSize: 'No image', notice: 'Notice',
    permission_required: 'Confirm screen sharing on the streaming device.',
    stopped: 'Stream stopped on device.', paused: 'Screen sharing is paused on the streaming device.',
    failed: 'Stream failed. Check the streaming device.',
    unsupported: 'This browser cannot connect to the stream. Use a browser with WebSocket support.'
};
function text(node, value) { node.textContent = value; }
function byId(id) { return document.getElementById(id); }
function labelControl(node, value) {
    node.setAttribute('aria-label', value);
    node.setAttribute('data-tooltip', value);
}
