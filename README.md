# Screen Stream over HTTP
An Android mobile app for viewing device screen in your web browser.

This is a stable branch represented in Google Play Market<br>
<a href='https://play.google.com/store/apps/details?id=info.dvkr.screenstream&pcampaignid=MKT-Other-global-all-co-prtnr-py-PartBadge-Mar2515-1'><img height="100" width="258" alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png'/></a> 

No new features, just bug fixes.<br>
All development goes in [master branch](https://github.com/dkrivoruchko/ScreenStream).

The application allows viewing the device screen in your web browser.
The main idea is to show your device screen during presentations and demos.
No need of any additional software except for this app and a web browser.

It uses MJPEG to encode screen images and send them through network socket. So it works with any desktop or mobile browser which supports MJPEG (Chrome, Safari, EDGE, Firefox).

The program works only via WiFi connection, so your device has to be connected to WiFi. No Internet required, however, there must be a network connection between the client and the device.
The number of client connections is unlimited, but be aware that each of them requires some CPU resources to send data.  

It uses Android Cast feature and requires at least Android 5.0 to operate.

**WARING:** This is not real time streaming app. Expect delay at least 0.5-1 second or more on slow devices, bad WiFi or on heavy CPU load by other apps.<br>
**WARING:** This app is not designed for streaming video, especially HD video. Use Chromecast instead.<br>
**WARING:** This app does NOT support SOUND streaming, because MJPEG does not support sound.

**Known problems**
1. On some devices system return image in unknown format. Mostly on devices with no official Android 5.0 or above. Possible Android bug. App will show an error message. Trying to find some workaround.
2. On some devices no notification icon showing but notification is present. Android bug: 213309.
3. Browser MJPEG support check is inaccurate. You can disable it in application settings.

If there are any issues or ideas feel free to contact me.