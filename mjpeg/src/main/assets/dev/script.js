const clientId = RandomString(16);
window.DD_LOGS && DD_LOGS.setGlobalContextProperty('clientId', clientId);

const buttonsDiv = document.getElementById('buttonsDiv')
const streamDiv = document.getElementById("streamDiv");
const stream = document.getElementById("stream");
const connectDiv = document.getElementById("connectDiv");
const pinDiv = document.getElementById("pinDiv");
const pin = document.getElementById("pin");
const sendPin = document.getElementById("sendPin");
const pinWrongMsg = document.getElementById("pinWrongMsg");
const blockedDiv = document.getElementById("blockedDiv");
const errorDiv = document.getElementById("errorDiv");

var enableButtons = false;
const buttonsHideFunction = () => { buttonsDiv.style.visibility = 'hidden'; }
var hideTimeout = setTimeout(buttonsHideFunction, 1500);
function configureButtons(enable) {
    if (enableButtons != enable) {
        enableButtons = enable;
        if (enableButtons) {
            buttonsDiv.style.visibility = 'visible';
            hideTimeout = setTimeout(buttonsHideFunction, 1500);
        } else {
            clearTimeout(hideTimeout);
            buttonsDiv.style.visibility = 'hidden';
        }
    }
}

window.onmousemove = () => {
    if (!enableButtons) return
    buttonsDiv.style.visibility = 'visible';
    clearTimeout(hideTimeout)
    hideTimeout = setTimeout(buttonsHideFunction, 1000);
}

function isFullscreen() {
    return document.webkitIsFullScreen || document.mozFullScreen || false;
}

const fullscreenInput = document.getElementById("fullscreen");
function fullScreenHandler() {
    if (isFullscreen()) fullscreenInput.src = "data:image/webp;base64,UklGRkoAAABXRUJQVlA4TD4AAAAvI8AIEBcw//M//wKCoudMD/gICtq2kTwQR2IoDsSBnIb0PgAR/Z8AHxwWkOrY7FQdW56dnapjD6k4NiwfBA==";
    else fullscreenInput.src = "data:image/webp;base64,UklGRmIAAABXRUJQVlA4TFUAAAAvI8AIECcw//M//wKBFG52gQ4Ag1UkSa2WIAAJ1OIABWn9qyK+8B/Rf6Nt2ySorN2+wP8NJC0QSZqRKMkDRZK9IY0hL/hCgV/5lVudfeGZ3x19/PcCAA==";
}

document.addEventListener("fullscreenchange", fullScreenHandler);
document.addEventListener("webkitfullscreenchange", fullScreenHandler);
document.addEventListener("mozfullscreenchange", fullScreenHandler);
document.addEventListener("MSFullscreenChange", fullScreenHandler);

function fullScreen(element) {
    if (element.requestFullscreen) element.requestFullscreen(); else if (element.webkitrequestFullscreen) element.webkitRequestFullscreen(); else if (element.mozRequestFullscreen) element.mozRequestFullScreen();
}

function fullScreenCancel() {
    if (document.requestFullscreen) document.requestFullscreen(); else if (document.webkitRequestFullscreen) document.webkitRequestFullscreen(); else if (document.mozRequestFullscreen) document.mozRequestFullScreen();
}

function toggleFullscreen() {
    isFullscreen() ? fullScreenCancel() : fullScreen(document.documentElement);
}

function toggleStartStop() {
    const xmlHttp = new XMLHttpRequest();
    xmlHttp.open("GET", window.location.origin + "/start-stop", true);
    xmlHttp.send(null);
}

sendPin.addEventListener('click', (e) => {
    e.preventDefault();
    const pinHash = SHA256(clientId + pin.value);
    if (websocket) websocket.send(JSON.stringify({ type: "PIN", data: pinHash }));
});

var websocket = null;
var fallbackMJPEGCounter = 0;
var fallbackMJPEG = null;
var showStreamTimeoutId = null;

function connect() {
    connectDiv.style.visibility = 'visible';
    pinDiv.style.visibility = 'hidden';
    blockedDiv.style.visibility = 'hidden';
    pinWrongMsg.style.visibility = "inherit";
    streamDiv.style.visibility = 'hidden';
    errorDiv.style.visibility = 'hidden';
    stream.src = '';

    websocket = new WebsocketHeartbeat(`ws://${window.location.host}/socket?clientId=${clientId}`);

    websocket.onopen = () => {
        websocket.send(JSON.stringify({ type: "CONNECT" }));
        connectDiv.style.visibility = 'hidden';
    };

    websocket.onreconnect = () => {
        connectDiv.style.visibility = 'visible';
        pinDiv.style.visibility = 'hidden';
        blockedDiv.style.visibility = 'hidden';
        pinWrongMsg.style.visibility = "inherit";
        streamDiv.style.visibility = 'hidden';
        errorDiv.style.visibility = 'hidden';
        stream.src = '';

        fallbackMJPEGCounter = 0;
        clearTimeout(showStreamTimeoutId);
    };

    websocket.onmessage = (msg) => {
        const message = JSON.parse(msg.data);
        if (message.type === "HEARTBEAT") return;

        window.DD_LOGS && DD_LOGS.logger.debug("websocket.onmessage", { data: msg.data });

        if (message.type === "STREAM_ADDRESS") {
            pinDiv.style.visibility = 'hidden';
            blockedDiv.style.visibility = 'hidden';
            pinWrongMsg.style.visibility = "inherit";
            showStream(message.data.streamAddress + `?clientId=${clientId}`);
            configureButtons(message.data.enableButtons);
            return;
        }

        if (message.type === "UNAUTHORIZED") {
            if (message.data === "ADDRESS_BLOCKED") {
                pinDiv.style.visibility = 'hidden';
                blockedDiv.style.visibility = 'visible';
                pinWrongMsg.style.visibility = "inherit";
                return;
            }

            pinDiv.style.visibility = 'visible';
            blockedDiv.style.visibility = 'hidden';

            if (message.data === "WRONG_PIN") {
                pin.value = "";
                pinWrongMsg.style.visibility = 'visible';
            } else {
                pinWrongMsg.style.visibility = 'hidden';
                if (pin.value) sendPin.click();
            }
            return;
        }

        if (message.type === "RELOAD") {
            location.reload();
            return;
        }

        if (message.type === "SETTINGS") {
            document.body.style.backgroundColor = message.data.backColor;
            configureButtons(message.data.enableButtons);
            return;
        }

        window.DD_LOGS && DD_LOGS.logger.error("websocket.onmessage. Unknown data:", { message: e.data });
    };
}

function showStream(url) {
    streamDiv.style.visibility = 'hidden';
    stream.src = '';
    errorDiv.style.visibility = 'hidden';

    clearTimeout(showStreamTimeoutId);
    if (fallbackMJPEG) fallbackMJPEG.stop();
    fallbackMJPEG = null;

    if (fallbackMJPEGCounter > 2) {
        window.DD_LOGS && DD_LOGS.logger.debug("showStream", { mode: "fallback", streamAddress: url });

        streamDiv.style.visibility = 'visible';
        fallbackMJPEG = new MJPEG_JS(stream, url);
        fallbackMJPEG.onerror = (error) => {
            window.DD_LOGS && DD_LOGS.logger.debug("showStream", { mode: "fallback", result: "error", errorMessage: error.message });
            fallbackMJPEG = null;
            if (error.message == "network error") {
                return;
            }
            streamDiv.style.visibility = 'hidden';
            errorDiv.style.visibility = 'visible';
        };
        fallbackMJPEG.onload = () => {
            window.DD_LOGS && DD_LOGS.logger.debug("showStream", { mode: "fallback", result: "ok" });
        };
        fallbackMJPEG.load();
    } else {
        new Promise((resolve, reject) => {
            window.DD_LOGS && DD_LOGS.logger.debug("showStream", { mode: "default", streamAddress: url });
            stream.onload = () => { stream.onload = null; stream.onerror = null; resolve(); }
            stream.onerror = (e) => { stream.onerror = null; stream.onload = null; reject(e); }
            stream.src = url;
        }).then(() => {
            fallbackMJPEGCounter = 0;
            streamDiv.style.visibility = 'visible';
            window.DD_LOGS && DD_LOGS.logger.debug("showStream", { mode: "default", result: "ok" });
        }).catch((error) => {
            window.DD_LOGS && DD_LOGS.logger.debug("showStream", { mode: "default", result: "error" });
            fallbackMJPEGCounter++;
            showStreamTimeoutId = setTimeout(() => showStream(url), 150);
        });
    }
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', connect);
} else {
    connect();
}

// setTimeout(() => { if (!stream.complete) streamFallback() }, 2000);
// function streamFallback() {
//     const baseurl = url.split(".mjpeg")[0];
//     setInterval(() => { stream.src = baseurl + ".jpeg?t=" + Math.random() }, 500);
// }

function MJPEG_JS(img, url) {
    this.opts = { img, url };
    this.terminate = false;
    this.SOI = new Uint8Array([0xFF, 0xD8, 0xFF]);
    this.lengthRegex = /Content-Length:\s*(\d+)/i;

    this.onload = () => { };
    this.onerror = () => { };
}
MJPEG_JS.prototype.stop = function () {
    this.terminate = true;
}
MJPEG_JS.prototype.clean = function () {
    this.opts.img.onload = null;
    this.reader = null;
    this.response = null;
    this.movingBuffer = null;
    this.headers = null;
    this.imageBuffer = null;
}
MJPEG_JS.prototype.read = function (result) {
    if (this.terminate) throw Error("Stopped");
    if (result.done) throw Error("Done");

    for (var i = 0; i < result.value.length; i++) {
        this.movingBuffer.push(result.value[i]);
        this.movingBuffer.shift();

        if (this.movingBuffer[0] === this.SOI[0] && this.movingBuffer[1] === this.SOI[1] && this.movingBuffer[2] === this.SOI[2]) {
            this.contentLength = this.headers.match(this.lengthRegex)[1];
            this.imageBuffer = new Uint8Array(new ArrayBuffer(this.contentLength));
            this.imageBuffer[this.bytesRead++] = this.movingBuffer[0];
            this.imageBuffer[this.bytesRead++] = this.movingBuffer[1];
            this.imageBuffer[this.bytesRead++] = this.movingBuffer[2];
            continue;
        }

        if (this.contentLength <= 0) {
            this.headers += String.fromCharCode(result.value[i]);
        } else if (this.bytesRead < this.contentLength) {
            this.imageBuffer[this.bytesRead++] = result.value[i];
        } else {
            this.opts.img.src = URL.createObjectURL(new Blob([this.imageBuffer], { type: "image/jpeg" }));
            this.contentLength = 0;
            this.imageBuffer = null;
            this.bytesRead = 0;
            this.headers = '';
        }
    }

    this.reader.read()
        .then(result => this.read(result))
        .catch(error => {
            this.onerror(error);
            this.clean();
        });
}
MJPEG_JS.prototype.load = function () {
    fetch(this.opts.url)
        .then(response => {
            if (this.terminate) throw Error("Stopped");
            if (!response.ok) throw Error(`${response.status}: ${response.statusText}`);
            if (response.redirected) throw Error(`Redirected: ${response.status}: ${response.statusText} : ${response.url}`);

            this.onload();

            this.opts.img.onload = () => { URL.revokeObjectURL(this.opts.img.src); };

            this.movingBuffer = [0, 0, 0];
            this.headers = '';
            this.contentLength = 0;
            this.imageBuffer = null;
            this.bytesRead = 0;

            this.reader = response.body.getReader();
            return this.reader.read();
        })
        .then(result => this.read(result))
        .catch(error => {
            this.onerror(error);
            this.clean();
        });
}

// https://github.com/zimv/websocket-heartbeat-js
function WebsocketHeartbeat(url) {
    this.opts = { url, pingTimeout: 1000, pongTimeout: 1000, reconnectTimeout: 2000, pingMsg: JSON.stringify({ type: "HEARTBEAT" }) };
    this.ws = null;

    this.onclose = () => { };
    this.onerror = () => { };
    this.onopen = () => { };
    this.onmessage = () => { };
    this.onreconnect = () => { };

    this.createWebSocket();
}
WebsocketHeartbeat.prototype.createWebSocket = function () {
    try {
        this.ws = new WebSocket(this.opts.url);
        this.ws.onclose = (e) => {
            this.onclose(e);
            this.reconnect();
        };
        this.ws.onerror = (e) => {
            this.onerror(e);
            this.reconnect();
        };
        this.ws.onopen = (e) => {
            this.onopen(e);
            this.heartCheck();
        };
        this.ws.onmessage = (event) => {
            this.onmessage(event);
            this.heartCheck();
        };
    } catch (e) {
        this.onerror(e);
        this.reconnect();
    }
}
WebsocketHeartbeat.prototype.reconnect = function () {
    if (this.lockReconnect || this.forbidReconnect) return;
    this.lockReconnect = true;
    this.onreconnect();
    setTimeout(() => {
        this.createWebSocket();
        this.lockReconnect = false;
    }, this.opts.reconnectTimeout);
}
WebsocketHeartbeat.prototype.send = function (msg) {
    if (this.ws.readyState === WebSocket.OPEN) this.ws.send(msg);
}
WebsocketHeartbeat.prototype.heartCheck = function () {
    clearTimeout(this.pingTimeoutId);
    clearTimeout(this.pongTimeoutId);

    if (this.forbidReconnect) return;
    this.pingTimeoutId = setTimeout(() => {
        if (this.ws.readyState === WebSocket.OPEN) this.ws.send(this.opts.pingMsg);
        this.pongTimeoutId = setTimeout(() => {
            this.ws.onclose = null;
            try { this.ws.close(); } catch (ignore) { }
            this.reconnect();
        }, this.opts.pongTimeout);
    }, this.opts.pingTimeout);
}
WebsocketHeartbeat.prototype.close = function () {
    this.forbidReconnect = true;
    clearTimeout(this.pingTimeoutId);
    clearTimeout(this.pongTimeoutId);
    this.ws.onclose = null;
    try { this.ws.close(); } catch (ignore) { }
}

// https://github.com/username1565/sha256
function SHA256(ascii) {
    function rightRotate(value, amount) {
        return (value >>> amount) | (value << (32 - amount));
    }

    var maxWord = Math.pow(2, 32);
    var i, j;
    var result = '';
    var asciiBitLength = ascii['length'] * 8;
    var words = [], hash = [], k = [];
    var primeCounter = 0;

    var isComposite = {};
    for (var candidate = 2; primeCounter < 64; candidate++) {
        if (!isComposite[candidate]) {
            for (i = 0; i < 313; i += candidate) {
                isComposite[i] = candidate;
            }
            hash[primeCounter] = (Math.pow(candidate, .5) * maxWord) | 0;
            k[primeCounter++] = (Math.pow(candidate, 1 / 3) * maxWord) | 0;
        }
    }

    ascii += '\x80';
    while (ascii['length'] % 64 - 56) ascii += '\x00';
    for (i = 0; i < ascii['length']; i++) {
        j = ascii.charCodeAt(i);
        if (j >> 8) return;
        words[i >> 2] |= j << ((3 - i) % 4) * 8;
    }
    words[words['length']] = ((asciiBitLength / maxWord) | 0);
    words[words['length']] = (asciiBitLength)

    for (j = 0; j < words['length'];) {
        var w = words.slice(j, j += 16);
        var oldHash = hash;
        hash = hash.slice(0, 8);

        for (i = 0; i < 64; i++) {
            var w15 = w[i - 15], w2 = w[i - 2];

            var a = hash[0], e = hash[4];
            var temp1 = hash[7]
                + (rightRotate(e, 6) ^ rightRotate(e, 11) ^ rightRotate(e, 25))
                + ((e & hash[5]) ^ ((~e) & hash[6]))
                + k[i]
                + (w[i] = (i < 16) ? w[i] : (
                    w[i - 16]
                    + (rightRotate(w15, 7) ^ rightRotate(w15, 18) ^ (w15 >>> 3))
                    + w[i - 7]
                    + (rightRotate(w2, 17) ^ rightRotate(w2, 19) ^ (w2 >>> 10))
                ) | 0
                );
            var temp2 = (rightRotate(a, 2) ^ rightRotate(a, 13) ^ rightRotate(a, 22)) + ((a & hash[1]) ^ (a & hash[2]) ^ (hash[1] & hash[2]));

            hash = [(temp1 + temp2) | 0].concat(hash);
            hash[4] = (hash[4] + temp1) | 0;
        }

        for (i = 0; i < 8; i++) {
            hash[i] = (hash[i] + oldHash[i]) | 0;
        }
    }

    for (i = 0; i < 8; i++) {
        for (j = 3; j + 1; j--) {
            var b = (hash[i] >> (j * 8)) & 255;
            result += ((b < 16) ? 0 : '') + b.toString(16);
        }
    }
    return result;
}

function RandomString(length) {
    var result = '';
    const characters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    const charactersLength = characters.length;
    for (var i = 0; i < length; i++) {
        result += characters.charAt(Math.floor(Math.random() * charactersLength));
    }
    return result;
}