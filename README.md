![](docs/images/hero.png)
# ScreenStream

ScreenStream is an open-source Android application for streaming your device screen and audio. Use Local mode (MJPEG) or Global mode (WebRTC) to view the stream in a web browser, or use RTSP mode with a compatible RTSP client, server, or player.

The Google Play version supports all modes: **Global mode (WebRTC)**, **Local mode (MJPEG)**, and **RTSP mode**, with ads included.<br>
F-Droid versions are ad-free and support only **Local mode (MJPEG)** and **RTSP mode**.

<a href='https://play.google.com/store/apps/details?id=info.dvkr.screenstream'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png' height="100"/></a> <a href="https://f-droid.org/packages/info.dvkr.screenstream/" target="_blank"><img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="100"/></a>

## Project support

If **ScreenStream** is useful to you, you can support its continued development using any of the options below.

**Preferred (Tether Wallet / Tether.me):** `dmkr0@tether.me`

This human-readable Tether.me address is the simplest option for Tether Wallet users.

For direct on-chain transfers:

**USD₮ / USA₮ (Ethereum / ERC-20):** `0x5bb898c72dd3dD5E7f7A0Ab12854a60374B7c1dA`

**BTC (on-chain):** `bc1q8wuv4esej0rdyn2jwkd93v7wvyep7uutzsmlh9`

## Stream modes

ScreenStream offers three stream modes: **Global mode (WebRTC)** (available only in the [Google Play Store](https://play.google.com/store/apps/details?id=info.dvkr.screenstream) version), **Local mode (MJPEG)**, and **RTSP mode**. All modes stream the Android device screen, but they function differently. Audio support is available in **Global mode (WebRTC)** and **RTSP mode** only. The modes are independent of each other and have different capabilities, restrictions, and customization options.

| Mode                     | Delivery                     | Video                     | Audio                 | Internet required              | Connection model                                                       | Security                                     |
|--------------------------|------------------------------|---------------------------|--------------------|--------------------------------|------------------------------------------------------------------------|----------------------------------------------|
| **Local mode (MJPEG)**   | MJPEG over HTTP              | JPEG images               | No                 | No                             | Built-in HTTP server                                                   | Optional 4–6 digit PIN                       |
| **Global mode (WebRTC)** | WebRTC                       | WebRTC video              | Microphone / internal audio | Yes                            | Public signaling service at [screenstream.io](https://screenstream.io) | End-to-end encryption + password             |
| **RTSP mode**            | RTSP/RTP over TCP or UDP     | H.264 / H.265             | OPUS / AAC / G.711 | No (server) / Depends (client) | Built-in server (server mode) / External server (client mode)          | Client mode supports RTSP auth + RTSPS (TLS) |

In **Global mode (WebRTC)** and **Local mode (MJPEG)**, the number of clients is not directly limited, but each client uses CPU resources and separate bandwidth.

ScreenStream uses Android's [MediaProjection](https://developer.android.com/reference/android/media/projection/MediaProjection) API and requires Android 7.0 or higher.

> [!WARNING]
>
> - **High traffic on mobile networks:** Streaming over 3G/4G/5G/LTE can consume a large amount of data.
>
> - **Streaming delay:** Latency depends on the selected mode, device performance, and network conditions. RTSP and WebRTC are generally better suited for lower-latency streaming, while Local mode prioritizes broad browser compatibility.
>
> - **Video playback:** Streaming quality depends on the selected mode, resolution, device encoders, available bandwidth, and CPU load.

### Local mode (MJPEG)

Local mode uses MJPEG and a built-in HTTP server. It works on a local network without internet, such as Wi-Fi, a device hotspot, USB tethering, or any other network connection between the Android device and the client's browser.

For optimal performance, a fast and stable network connection is recommended due to high traffic and low network delay requirements.

In Local mode, the app processes each frame independently, one by one, enabling additional image transformations such as cropping, resizing, rotating, and more before sending the image to the client's web browser.

The Local mode offers the following functionality:

- Uses MJPEG.
- PIN protection, no encryption.
- Video only, sent as separate images.
- Works on your local network, no internet required.
- Built-in HTTP server.
- Allows resizing by percentage or exact resolution.
- Supports Wi-Fi, mobile networks, IPv4, and IPv6.
- Clients connect in a browser using the app address.
- Highly configurable.
- Each client uses separate bandwidth.

> [!NOTE]
>
> - Some mobile carriers may block incoming connections to your device. Even if the device has a mobile network IP address, clients may not be able to connect to it directly.
>
> - Some Wi-Fi networks, particularly public or guest networks, isolate connected devices from each other. In this case, a phone and a client device on the same Wi-Fi network may still be unable to connect directly.

**Screenshots**

<p>
  <img src="docs/images/mjpeg-1.png" alt="Local mode main screen" width="270" />&nbsp;
  <img src="docs/images/mjpeg-7.png" alt="Local mode activity and connected clients" width="270" />&nbsp;
  <img src="docs/images/mjpeg-2.png" alt="Local mode information dialog" width="270" />
</p>
<p>
  <img src="docs/images/mjpeg-3.png" alt="Local mode general settings" width="270" />&nbsp;
  <img src="docs/images/mjpeg-4.png" alt="Local mode image settings" width="270" />&nbsp;
  <img src="docs/images/mjpeg-5.png" alt="Local mode security settings" width="270" />
</p>
<p align="center">
  <img src="docs/images/mjpeg-6.png" alt="Local mode advanced settings" width="290" />
</p>

### Global mode (WebRTC)

Global mode uses WebRTC and the public ScreenStream signaling service to connect the streaming host (the app) with viewers using the ScreenStream [Web Client](https://screenstream.io).

Both the signaling server and web client are open-source and available in the [ScreenStreamWeb](https://github.com/dkrivoruchko/ScreenStreamWeb) repository. The hosted web client is available at [screenstream.io](https://screenstream.io) and works with modern desktop and mobile browsers that support WebRTC.

Global mode offers the following functionality:

- Uses WebRTC technology.
- End-to-end encrypted communication.
- Stream protection with password.
- Supports video, microphone audio, and internal device audio.
- Connect using a unique stream ID and password.
- Requires an internet connection for streaming.
- Each client uses separate internet bandwidth.

> [!NOTE]
> Global mode (WebRTC) is available only in the Google Play version.

**Screenshots**

<p>
  <img src="docs/images/webrtc-1.png" alt="Global mode main screen" width="270" />&nbsp;
  <img src="docs/images/webrtc-2.png" alt="Global mode information dialog" width="270" />&nbsp;
  <img src="docs/images/webrtc-3.png" alt="Global mode audio settings" width="270" />
</p>

### RTSP mode

RTSP mode supports two sub-modes. Server mode (default) hosts the Android device as an RTSP server so compatible players can connect directly. Client mode publishes the stream to an external RTSP media server, such as MediaMTX.

For optimal performance, a fast and stable network connection is recommended due to high traffic and low network delay requirements.

- Uses RTSP/RTP over TCP or UDP.
- Server mode hosts the device as an RTSP server.
- Client mode publishes to a remote RTSP or RTSPS server.
- Supports H.264/H.265 video and OPUS/AAC/G.711 audio, with codec configuration based on device support.
- Server mode adds protocol, interface/address filters, IPv4/IPv6, and port settings.
- Optional ONVIF discovery lets compatible clients find RTSP server streams; H.264 only.
- Requires an RTSP client or player for viewing.
- Tested with [VLC media player](https://www.videolan.org/vlc/), [FFplay](https://ffmpeg.org/ffplay.html), [mpv](https://mpv.io/), and [gst-play-1.0](https://gstreamer.freedesktop.org/) (GStreamer) players.

> [!NOTE]
> Client mode requires an RTSP‑capable media server (e.g., [MediaMTX](https://github.com/bluenviron/mediamtx)).

**Screenshots**

<p>
  <img src="docs/images/rtsp-1.png" alt="RTSP mode main screen" width="270" />&nbsp;
  <img src="docs/images/rtsp-2.png" alt="RTSP mode information dialog" width="270" />&nbsp;
  <img src="docs/images/rtsp-3.png" alt="RTSP mode server settings" width="270" />
</p>
<p align="center">
  <img src="docs/images/rtsp-4.png" alt="RTSP mode video settings" width="290" />&nbsp;
  <img src="docs/images/rtsp-5.png" alt="RTSP mode audio settings" width="290" />
</p>

## Contribution

To contribute a translation, translate the following five files:

1. [app/src/main/res/values/strings.xml](app/src/main/res/values/strings.xml)
1. [common/src/main/res/values/strings.xml](common/src/main/res/values/strings.xml)
1. [mjpeg/src/main/res/values/strings.xml](mjpeg/src/main/res/values/strings.xml)
1. [webrtc/src/main/res/values/strings.xml](webrtc/src/main/res/values/strings.xml)
1. [rtsp/src/main/res/values/strings.xml](rtsp/src/main/res/values/strings.xml)

Keep the same key order and blank-line placement as the English source files, and preserve all formatting placeholders such as `%1$s` and `%1$d`.

Please submit a [pull request](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/proposing-changes-to-your-work-with-pull-requests/creating-a-pull-request). If that is not possible, you can send the translated files to the developer via e-mail at <dkrivoruchko@gmail.com>.

Your contribution helps make the application more accessible. Thank you for your efforts.

## Developer

Developed by [Dmytro Kryvoruchko](mailto:dkrivoruchko@gmail.com). If you have any issues or ideas, feel free to contact me.

## Privacy and Terms

App [Privacy Policy](PrivacyPolicy.md) and [Terms & Conditions](TermsConditions.md)

## License

```
The MIT License (MIT)

Copyright (c) 2016 Dmytro Kryvoruchko

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
