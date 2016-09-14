# ScreenStream
**Screen Stream over HTTP** - an Android mobile app: [Screen Stream over HTTP](https://play.google.com/store/apps/details?id=info.dvkr.screenstream)

The application allows viewing the device screen in your web browser.
The main idea is to show your device screen during presentations and demos.
No need of any additional software except for this app and a web browser.

It uses MJPEG to encode screen images and send them through network socket. So it works with any desktop or mobile browser which supports MJPEG (Chrome, Safari, EDGE, Firefox).

The program works only via WiFi connection, so your device has to be connected to WiFi. No Internet required, however, there must be a network connection between the client and the device.
The number of client connections is unlimited, but be aware that each of them requires some CPU resources to send data.  

It uses Android Cast feature and requires at least Android 5.0 to operate.

**Known problems**
1. On some devices system return image in unknown format. Mostly on devices with no official Android 5.0 or above. Possible Android bug. App will show an error message. Trying to find some workaround.
2. On some devices no notification icon showing but notification is present. Android bug: 213309.

If there are any issues or ideas feel free to contact me.
