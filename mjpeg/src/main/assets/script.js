function MJPEG_JS({ img, url }) {
    this.opts = { img, url };
    this.stop = false;
    this.onerror = () => { };
};
MJPEG_JS.prototype.stop = function () {
    this.stop = true;
};
MJPEG_JS.prototype.load = async function () {
    try {
        this.response = await fetch(this.opts.url);
        if (!this.response.ok) throw Error(`${this.response.status}: ${this.response.statusText}`);
        if (this.response.redirected) throw Error(`Redirected: ${this.response.status}: ${this.response.statusText} : ${this.response.url}`);
        if (this.stop) return;

        this.reader = this.response.body.getReader();
        this.SOI = new Uint8Array([0xFF, 0xD8, 0xFF]);
        this.EOI = new Uint8Array([0xFF, 0xF9]);
        this.lengthRegex = /Content-Length:\s*(\d+)/i;
        this.movingBuffer = [0, 0, 0];
        this.headers = '';
        this.contentLength = 0;
        this.imageBuffer = null;
        this.bytesRead = 0;

        this.opts.img.onload = () => { URL.revokeObjectURL(this.opts.img.src); };

        while (true) {
            var { done, value } = await this.reader.read();

            for (var i = 0; i < value.length; i++) {
                this.movingBuffer.push(value[i]);
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
                    this.headers += String.fromCharCode(value[i]);
                } else if (this.bytesRead < this.contentLength) {
                    this.imageBuffer[this.bytesRead++] = value[i];
                } else {
                    this.opts.img.src = URL.createObjectURL(new Blob([this.imageBuffer], { type: "image/jpeg" }));
                    this.contentLength = 0;
                    this.imageBuffer = null;
                    this.bytesRead = 0;
                    this.headers = '';
                }
            }

            if (done || this.stop) return;
        }
    } catch (error) {
        this.onerror(error);
    } finally {
        this.opts.img.onload = null;
        this.reader = null;
        this.response = null;
        this.movingBuffer = null;
        this.headers = null;
        this.imageBuffer = null;
    }
};
if (typeof window != 'undefined') window.MJPEG_JS = MJPEG_JS;

// https://github.com/zimv/websocket-heartbeat-js
function WebsocketHeartbeat({
    url,
    pingTimeout = 1000,
    pongTimeout = 1000,
    reconnectTimeout = 2000,
    pingMsg = JSON.stringify({ type: "HEARTBEAT" }),
}) {
    this.opts = { url, pingTimeout, pongTimeout, reconnectTimeout, pingMsg };
    this.ws = null;

    this.onclose = () => { };
    this.onerror = () => { };
    this.onopen = () => { };
    this.onmessage = () => { };
    this.onreconnect = () => { };

    this.createWebSocket();
};
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
};
WebsocketHeartbeat.prototype.reconnect = function () {
    if (this.lockReconnect || this.forbidReconnect) return;
    this.lockReconnect = true;
    this.onreconnect();
    setTimeout(() => {
        this.createWebSocket();
        this.lockReconnect = false;
    }, this.opts.reconnectTimeout);
};
WebsocketHeartbeat.prototype.send = function (msg) {
    this.ws.send(msg);
};
WebsocketHeartbeat.prototype.heartCheck = function () {
    clearTimeout(this.pingTimeoutId);
    clearTimeout(this.pongTimeoutId);

    if (this.forbidReconnect) return;
    this.pingTimeoutId = setTimeout(() => {
        if (this.ws.readyState === WebSocket.OPEN) this.ws.send(this.opts.pingMsg);
        this.pongTimeoutId = setTimeout(() => {
            this.ws.onclose = null;
            try { this.ws.close(); } catch (ignore) { };
            this.reconnect();
        }, this.opts.pongTimeout);
    }, this.opts.pingTimeout);
};
WebsocketHeartbeat.prototype.close = function () {
    this.forbidReconnect = true;
    clearTimeout(this.pingTimeoutId);
    clearTimeout(this.pongTimeoutId);
    this.ws.onclose = null;
    try { this.ws.close(); } catch (ignore) { };
};
if (typeof window != 'undefined') window.WebsocketHeartbeat = WebsocketHeartbeat;

// https://github.com/username1565/sha256
function SHA256(ascii) {
    function rightRotate(value, amount) {
        return (value >>> amount) | (value << (32 - amount));
    };

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
};
if (typeof window != 'undefined') window.SHA256 = SHA256;

function RandomString(length) {
    var result = '';
    const characters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    const charactersLength = characters.length;
    for (var i = 0; i < length; i++) {
        result += characters.charAt(Math.floor(Math.random() * charactersLength));
    }
    return result;
};
if (typeof window != 'undefined') window.RandomString = RandomString;