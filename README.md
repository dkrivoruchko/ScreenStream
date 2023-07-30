![](screenshots/about_image_full.png)
# ScreenStream

The Android mobile application enables users to view their device screen directly in their web browser.<br>To access this feature, no additional software is required other than the mobile application itself, a web browser, and an internet connection (for Global mode).

Google Play versions supports both **Global mode (WebRTC)** and **Local mode (MJPEG)** with ads included.<br>
Versions from F-Droid, AAPKS, or FirebaseFree builds in GitHub Releases are Ad-free and support only **Local mode (MJPEG)**.

<a href='https://play.google.com/store/apps/details?id=info.dvkr.screenstream'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png' height="100"/></a> <a href="https://f-droid.org/packages/info.dvkr.screenstream/" target="_blank"><img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="100"/></a> <a href="https://aapks.com/apk/screen-stream/"><img src="https://aapks.com/get.png" alt="Get it on AAPKs" height="100"/></a>

## Description

ScreenStream offers two work modes: **Global mode** (in Google Play Store version only) and **Local mode**. Both modes aim to stream the Android device screen but function differently. They are independent of each other, with unique functionalities, restrictions, and customization options. The application uses Android [MediaProjection](https://developer.android.com/reference/android/media/projection/MediaProjection) feature and requires Android 6.0 or higher.

Important Warnings:

- **High Traffic on Mobile Networks: Be cautious when streaming via mobile 3G/4G/5G/LTE networks, as it may result in excessive data usage**.

- **Delay in Streaming**: This app is not a real-time streaming solution. Expect a delay of at least 0.5-1 second or more on slow devices, poor internet or network connection, or when the device is under heavy CPU load due to other applications.

- **Video Streaming Limitation**: The app is not designed for streaming video, particularly HD video. While it will function, the stream quality may not meet your expectations.

### Global mode (WebRTC) / in Google Play version only /

Global mode in the ScreenStream application is built on WebRTC technology and relies on an external signaling server to facilitate communication between the streaming host (the app) and the streaming client, which is a web browser equipped with the ScreenStream [Web Client](https://screenstream.io).

Both the signaling server and the web client for ScreenStream are open-source and available in the [ScreenStreamWeb](https://github.com/dkrivoruchko/ScreenStreamWeb) repository. These components can be accessed publicly at https://screenstream.io. The system is designed to function seamlessly with any desktop or mobile browser that supports WebRTC, such as Chrome, Safari, EDGE, Firefox, and others.

The Global mode was introduced in app version 4 and offers the following functionality:
- Powered by WebRTC technology.
- End-to-end encrypted communication.
- Stream protection with password.
- Supports both video and audio streaming.
- Connect to stream using its unique ID and password.
- Requires an internet connection to send and receive stream.
- Individual data transmission for each client, with more clients requiring increased internet bandwidth to maintain optimal performance.

The number of clients is not directly limited, but it's important to keep in mind that each client consumes CPU resources and bandwidth for data transmission.

### Local mode (MJPEG)

Local mode in the ScreenStream application is built on the MJPEG standard and utilizes an embedded HTTP server within the app. As a result, an internet connection is not required; instead, it can function on a local network, such as Wi-Fi, device HotSpot, Network-over-USB, or any other network between the client's web browser and the Android device with the ScreenStream app.

For optimal performance, a fast and stable network connection is recommended due to high traffic and low network delay requirements.

In Local mode, the app processes each frame independently, one-by-one, enabling additional image transformations such as cropping, resizing, rotating, and more before sending the image to the client's web browser. 

The Local mode offers the following functionality:
- Powered by MJPEG standard.
- Utilizes PIN for security (no encryption).
- Sends video as a stream of independent images (no audio).
- Functions within a local network without requiring an internet connection.
- Embedded HTTP server.
- Works with WiFi and/or mobile networks, supporting IPv4 and IPv6.
- Clients connect via web browser using the app's provided IP address.
- Highly customizable.
- Individual data transmission for each client, with more clients requiring increased internet bandwidth to maintain optimal performance.

The number of clients is not directly limited, but it's important to keep in mind that each client consumes CPU resources and bandwidth for data transmission.

**WARNING:** Please be aware that certain cell operators may block incoming connections to your device for security reasons. Consequently, even if your device has an IP address from a cell operator, connecting to the device using this IP address may not be possible.

**WARNING:** Some WiFi networks, particularly public or guest networks, may block connections between its clients for security reasons. In such cases, connecting to the device via WiFi might not be feasible. For instance, a laptop and a phone within such a WiFi network will not be able to connect to each other.

## Screenshots

![](screenshots/screenshot_1.png)&nbsp;
![](screenshots/screenshot_2.png)<br>
![](screenshots/screenshot_3.png)&nbsp;
![](screenshots/screenshot_4.png)<br>
![](screenshots/screenshot_5.png)&nbsp;
![](screenshots/screenshot_6.png)<br>
![](screenshots/screenshot_7.png)&nbsp;
![](screenshots/screenshot_8.png)<br>
![](screenshots/screenshot_9.png)&nbsp;
![](screenshots/screenshot_10.png)<br>
![](screenshots/screenshot_11.png)&nbsp;
![](screenshots/screenshot_12.png)<br>
![](screenshots/screenshot_13.png)&nbsp;
![](screenshots/screenshot_14.png)


## Contribution

To contribute with translation, kindly translate the following three files:

1. https://github.com/dkrivoruchko/ScreenStream/blob/master/app/src/main/res/values/strings.xml
1. https://github.com/dkrivoruchko/ScreenStream/blob/master/mjpeg/src/main/res/values/strings.xml
1. https://github.com/dkrivoruchko/ScreenStream/blob/master/webrtc/src/main/res/values/strings.xml

Then, please, [make a pull request](https://help.github.com/en/articles/creating-a-pull-request) or send those translated files to the developer via e-mail <dkrivoruchko@gmail.com> as an attachment.

Your contribution is valuable and will help improve the accessibility of the application. Thank you for your efforts!

## Developed By

Developed by [Dmytro Kryvoruchko](dkrivoruchko@gmail.com). If there are any issues or ideas, feel free to contact me.

## Privacy Policy and Terms & Conditions

App [Privacy Policy](https://github.com/dkrivoruchko/ScreenStream/blob/master/PrivacyPolicy.md) and [Terms & Conditions](https://github.com/dkrivoruchko/ScreenStream/blob/master/TermsConditions.md)

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
