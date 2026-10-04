const words = {
    app: 'ScreenStream', connecting: 'Connecting…', connected: 'Connected',
    reconnecting: 'Reconnecting…', unavailable: 'Server unavailable. Retry the connection.',
    mediaStarting: 'Waiting for an image…', mediaRetrying: 'Restoring image…',
    mediaFailed: 'Image unavailable. Retry or choose another viewing method.', viewing: 'Viewing',
    pinRequired: 'Enter the six-digit PIN shown on the streaming device.',
    pinWrong: 'Incorrect PIN. Try again.', pinInvalid: 'Enter six digits (0–9).',
    pinSending: 'Checking PIN…', pinNetwork: 'Could not check PIN. Retry the connection.',
    blocked: 'PIN entry blocked. Try again in ', seconds: ' seconds.',
    retry: 'Retry connection', alternate: 'Other viewing method', useMjpeg: 'Use MJPEG',
    fit: 'Fit', original: 'Original size', width: 'Fit width', viewMode: 'Image size',
    fullscreen: 'Full screen', leaveFullscreen: 'Exit full screen', pip: 'Picture in picture',
    pipReady: 'Picture in picture is ready. Press Picture in picture again to open it.',
    pipError: 'Picture in picture could not open. Normal viewing continues.',
    fullscreenError: 'Full screen could not open. Normal viewing continues.',
    more: 'More', close: 'Close', infoToggle: 'Show connection information',
    pinLabel: 'PIN', unlock: 'View stream', info: 'Connection information',
    method: 'Viewing method', connection: 'State connection', image: 'Image delivery',
    size: 'JPEG dimensions', unknownSize: 'No image', title: 'Full title', streamNotice: 'Stream notice',
    permission_required: 'Confirm screen sharing on the streaming device.',
    stopped: 'Stream stopped', paused: 'Stream paused',
    failed: 'Stream failed. Check the streaming device.',
    unsupported: 'This browser cannot connect to the stream. Use a browser with WebSocket support.'
};
function text(node, value) { node.textContent = value; }
function byId(id) { return document.getElementById(id); }
