const clientId = RandomString(16);
window.DD_LOGS && DD_LOGS.setGlobalContextProperty("clientId", clientId);

const buttonsDiv = document.getElementById("buttonsDiv")
const buttonPiP = document.getElementById("PiP")
const streamDiv = document.getElementById("streamDiv");
const stream = document.getElementById("stream");
const connectDiv = document.getElementById("connectDiv");
const pinDiv = document.getElementById("pinDiv");
const pin = document.getElementById("pin");
const sendPin = document.getElementById("sendPin");
const pinWrongMsg = document.getElementById("pinWrongMsg");
const blockedDiv = document.getElementById("blockedDiv");
const errorDiv = document.getElementById("errorDiv");
const pipStreamDiv = document.getElementById("pipStreamDiv");

var enableButtons = false;
const buttonsHideFunction = () => { buttonsDiv.style.visibility = "hidden"; }
var hideTimeout = setTimeout(buttonsHideFunction, 1500);
function configureButtons(enable) {
    if (enableButtons != enable) {
        enableButtons = enable;
        if (enableButtons) {
            buttonsDiv.style.visibility = "visible";
            hideTimeout = setTimeout(buttonsHideFunction, 1500);
        } else {
            clearTimeout(hideTimeout);
            buttonsDiv.style.visibility = "hidden";
        }
    }
}
function configureFitWindow(enable) {
    if (enable) {
        stream.style.width = "100%";
        stream.style.objectFit = "contain";
    } else {
        stream.style.width = null;
        stream.style.objectFit = null;
    }
}
if (!document.pictureInPictureEnabled) buttonPiP.style.display = "none";

window.onmousemove = () => {
    if (!enableButtons) return
    buttonsDiv.style.visibility = "visible";
    clearTimeout(hideTimeout)
    hideTimeout = setTimeout(buttonsHideFunction, 1000);
}

const fullscreenInput = document.getElementById("fullscreen");

function isFullscreen() {
    return document.fullscreenElement || document.webkitFullscreenElement || document.mozFullScreenElement || document.msFullscreenElement || null;
}

function fullScreenHandler() {
    if (isFullscreen()) fullscreenInput.src = "data:image/svg+xml;charset=UTF-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath fill='%23FFF' d='M14,14H19V16H16V19H14V14M5,14H10V19H8V16H5V14M8,5H10V10H5V8H8V5M19,8V10H14V5H16V8H19Z' /%3E%3C/svg%3E";
    else fullscreenInput.src = "data:image/svg+xml;charset=UTF-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath fill='%23FFF' d='M5,5H10V7H7V10H5V5M14,5H19V10H17V7H14V5M17,14H19V19H14V17H17V14M10,17V19H5V14H7V17H10Z' /%3E%3C/svg%3E";
}

function isFullscreenEnabled() {
    return document.fullscreenEnabled || document.webkitFullscreenEnabled || document.mozFullScreenEnabled || document.msFullscreenEnabled || false;
}

if (isFullscreenEnabled()) {
    document.addEventListener("fullscreenchange", fullScreenHandler);
    document.addEventListener("webkitfullscreenchange", fullScreenHandler);
    document.addEventListener("mozfullscreenchange", fullScreenHandler);
    document.addEventListener("MSFullscreenChange", fullScreenHandler);
} else {
    fullscreenInput.style.visibility = "hidden";
}

function fullScreen(element) {
    if (element.requestFullscreen) element.requestFullscreen(); else if (element.webkitRequestFullscreen) element.webkitRequestFullscreen(); else if (element.mozRequestFullScreen) element.mozRequestFullScreen(); else if (element.msRequestFullscreen) element.msRequestFullscreen();
}

function fullScreenExit() {
    if (document.exitFullscreen) document.exitFullscreen(); else if (document.webkitExitFullscreen) document.webkitExitFullscreen(); else if (document.mozCancelFullScreen) document.mozCancelFullScreen(); else if (document.msExitFullscreen) document.msExitFullscreen();
}

function toggleFullscreen() {
    isFullscreen() ? fullScreenExit() : fullScreen(document.documentElement);
}

function toggleStartStop() {
    const xmlHttp = new XMLHttpRequest();
    xmlHttp.open("GET", window.location.origin + "/start-stop", true);
    xmlHttp.send(null);
}

sendPin.addEventListener("click", (e) => {
    e.preventDefault();
    const pinHash = SHA256(clientId + pin.value);
    if (websocket) websocket.send(JSON.stringify({ type: "PIN", data: pinHash }));
});

var websocket = null;
var showStreamTimeoutId = null;
var MJPEGErrorCounter = 0;

function connect() {
    connectDiv.style.visibility = "visible";
    pinDiv.style.visibility = "hidden";
    blockedDiv.style.visibility = "hidden";
    pinWrongMsg.style.visibility = "inherit";
    streamDiv.style.visibility = "hidden";
    errorDiv.style.visibility = "hidden";
    stream.src = "";

    websocket = new WebsocketHeartbeat(`ws://${window.location.host}/socket?clientId=${clientId}`);

    websocket.onopen = () => {
        websocket.send(JSON.stringify({ type: "CONNECT" }));
        connectDiv.style.visibility = "hidden";
    };

    websocket.onreconnect = () => {
        connectDiv.style.visibility = "visible";
        pinDiv.style.visibility = "hidden";
        blockedDiv.style.visibility = "hidden";
        pinWrongMsg.style.visibility = "inherit";
        streamDiv.style.visibility = "hidden";
        errorDiv.style.visibility = "hidden";
        stream.src = "";
        MJPEGErrorCounter = 0;

        clearTimeout(showStreamTimeoutId);

        if (document.pictureInPictureElement) {
            document.exitPictureInPicture();
        }
    };

    websocket.onmessage = (msg) => {
        const message = JSON.parse(msg.data);
        if (message.type === "HEARTBEAT") return;

        window.DD_LOGS && DD_LOGS.logger.debug("websocket.onmessage", { data: msg.data });

        if (message.type === "STREAM_ADDRESS") {
            pinDiv.style.visibility = "hidden";
            blockedDiv.style.visibility = "hidden";
            pinWrongMsg.style.visibility = "inherit";
            showStream(message.data.streamAddress + `?clientId=${clientId}`);
            configureButtons(message.data.enableButtons);
            return;
        }

        if (message.type === "UNAUTHORIZED") {
            if (message.data === "ADDRESS_BLOCKED") {
                pinDiv.style.visibility = "hidden";
                blockedDiv.style.visibility = "visible";
                pinWrongMsg.style.visibility = "inherit";
                return;
            }

            pinDiv.style.visibility = "visible";
            blockedDiv.style.visibility = "hidden";

            if (message.data === "WRONG_PIN") {
                pin.value = "";
                pinWrongMsg.style.visibility = "visible";
            } else {
                pinWrongMsg.style.visibility = "hidden";
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
            configureFitWindow(message.data.fitWindow);
            return;
        }

        window.DD_LOGS && DD_LOGS.logger.error("websocket.onmessage. Unknown data:", { message: e.data });
    };
}

function showStream(url) {
    streamDiv.style.visibility = "hidden";
    stream.src = "";
    errorDiv.style.visibility = "hidden";

    clearTimeout(showStreamTimeoutId);

    new Promise((resolve, reject) => {
        window.DD_LOGS && DD_LOGS.logger.debug("showStream", { mode: "default", streamAddress: url });
        stream.onload = () => { stream.onload = null; stream.onerror = null; resolve(); }
        stream.onerror = (e) => { stream.onerror = null; stream.onload = null; reject(e); }
        stream.src = url;
    }).then(() => {
        MJPEGErrorCounter = 0;
        streamDiv.style.visibility = "visible";
        window.DD_LOGS && DD_LOGS.logger.debug("showStream", { mode: "default", result: "ok" });
    }).catch((error) => {
        window.DD_LOGS && DD_LOGS.logger.debug("showStream", { mode: "default", result: "error" });
        MJPEGErrorCounter++;
        if (MJPEGErrorCounter > 5) {
            streamDiv.style.visibility = "visible";
            var splitUrl = url.split(".mjpeg");
            var baseUrl = splitUrl[0] + ".jpeg" + splitUrl[1] + "&t=";
            setInterval(() => { stream.src = baseUrl + Math.random() }, 500);
        } else {
            showStreamTimeoutId = setTimeout(() => showStream(url), 200);
        }
    });
}

var drawTimeoutId = null;

function togglePiP() {
    if (document.pictureInPictureElement) {
        document.exitPictureInPicture();
    } else {
        var canvas = document.createElement("canvas");
        canvas.style.display = "none";
        pipStreamDiv.appendChild(canvas);

        var videoElement = document.createElement("video");
        videoElement.controls = false;
        videoElement.muted = true;
        videoElement.autoplay = true;
        videoElement.srcObject = canvas.captureStream();

        videoElement.addEventListener("leavepictureinpicture", () => {
            clearTimeout(drawTimeoutId);
            while (pipStreamDiv.firstChild) pipStreamDiv.removeChild(pipStreamDiv.lastChild);
            videoElement.srcObject = null;
            videoElement = null;
            canvas = null;
            context = null;
        });

        videoElement.addEventListener("loadedmetadata", () => {
            videoElement.requestPictureInPicture()
                .catch(error => {
                    clearTimeout(drawTimeoutId);
                    window.DD_LOGS && DD_LOGS.logger.error("PiP.requestPictureInPicture:", { message: error });
                    while (pipStreamDiv.firstChild) pipStreamDiv.removeChild(pipStreamDiv.lastChild);
                    buttonPiP.style.display = "none";
                    videoElement.srcObject = null;
                    videoElement = null;
                    canvas = null;
                    context = null;
                });
        });

        pipStreamDiv.appendChild(videoElement);

        var context = canvas.getContext("2d");

        function drawMJPEGStream() {
            const { naturalWidth, naturalHeight } = stream;
            if (canvas.width != naturalWidth || canvas.height != naturalHeight) {
                canvas.width = naturalWidth;
                canvas.height = naturalHeight;
            }
            context.drawImage(stream, 0, 0);
            drawTimeoutId = setTimeout(drawMJPEGStream, 32);
        }

        drawMJPEGStream();
    }
}

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", connect);
} else {
    connect();
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
    var result = "";
    var asciiBitLength = ascii["length"] * 8;
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

    ascii += "\x80";
    while (ascii["length"] % 64 - 56) ascii += "\x00";
    for (i = 0; i < ascii["length"]; i++) {
        j = ascii.charCodeAt(i);
        if (j >> 8) return;
        words[i >> 2] |= j << ((3 - i) % 4) * 8;
    }
    words[words["length"]] = ((asciiBitLength / maxWord) | 0);
    words[words["length"]] = (asciiBitLength)

    for (j = 0; j < words["length"];) {
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
            result += ((b < 16) ? 0 : "") + b.toString(16);
        }
    }
    return result;
}

function RandomString(length) {
    var result = "";
    const characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    const charactersLength = characters.length;
    for (var i = 0; i < length; i++) {
        result += characters.charAt(Math.floor(Math.random() * charactersLength));
    }
    return result;
}