![](screenshots/about_image_full.png)
# Screen Stream over HTTP
一個用來在任何裝置上觀看你的安卓螢幕畫面應用。

[![xscode](https://img.shields.io/badge/Available%20on-xs%3Acode-blue?style=?style=plastic&logo=appveyor&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAMAAACdt4HsAAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAAZQTFRF////////VXz1bAAAAAJ0Uk5T/wDltzBKAAAAlUlEQVR42uzXSwqAMAwE0Mn9L+3Ggtgkk35QwcnSJo9S+yGwM9DCooCbgn4YrJ4CIPUcQF7/XSBbx2TEz4sAZ2q1RAECBAiYBlCtvwN+KiYAlG7UDGj59MViT9hOwEqAhYCtAsUZvL6I6W8c2wcbd+LIWSCHSTeSAAECngN4xxIDSK9f4B9t377Wd7H5Nt7/Xz8eAgwAvesLRjYYPuUAAAAASUVORK5CYII=)](https://xscode.com/dkrivoruchko/ScreenStream) &nbsp; ![GitHub](https://img.shields.io/github/license/dkrivoruchko/ScreenStream) &nbsp; ![GitHub release (latest by date)](https://img.shields.io/github/v/release/dkrivoruchko/ScreenStream)

[![Foo](https://xscode.com/assets/promo-banner.svg)](https://xscode.com/dkrivoruchko/ScreenStream)

> *由 [Dmitriy Krivoruchko](dkrivoruchko@gmail.com) 開發* &middot; 有任何問題或想法，請與開發者聯繫。

這個 app 讓你可以從任何瀏覽器中觀看裝置的螢幕串流畫面。
簡單來說讓你的裝置可以在簡報或是 demo 時投射出畫面。
除了瀏覽器外，你不需要安裝任何額外軟體來使用它。
谷歌市場版本含有廣告，但是 F-Droid、AAPKS 和 FirebaseFree 是沒有廣告的。

<a href='https://play.google.com/store/apps/details?id=info.dvkr.screenstream'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png' height="100"/></a> <a href="https://f-droid.org/packages/info.dvkr.screenstream/" target="_blank"><img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="100"/></a> <a href="https://aapks.com/apk/screen-stream/"><img src="https://aapks.com/get.png" alt="Get it on AAPKs" height="100"/></a>

Read this in other languages: [English](README.md)

它使用 MJPEG 編碼並透過網路傳輸傳輸畫面。如此一來在任何支援 MJPEG 編碼的瀏覽器上皆可觀看(Chrome, Safari, EDGE, Firefox).

這個 app 透過 WiFi 或 3G/LTE 網路傳輸。<br>
支援 IPv4 和 IPv6。<br>
請使用穩定的高速 WiFi 以達到順暢的收看體驗。
無須連接到外部網際網路，但至少要有一個能連接裝置與用戶端的區域網路。

用戶端數量是沒有限制的，但請注意每個用戶端都會佔用一定量的 cpu 資源及頻寬。

本程式使用 Android Cast功能，因此需 Android 5.0以上版本以執行。

**警示:** 這不是實時串流 app。依裝置性能及網路環境不同，約有 0.5 至 1 秒甚至更多的延遲。<br>
**警示:** 這個 app 不是設計來串流影片的，特別是高畫質影片。如有這些需求請使用 Chromecast。<br>
**警示:** 這個 app 不支援聲音串流，因為 MJPEG 僅為影像編碼。<br>
**警示:** 某些電信商可能阻擋到您裝置的連入連線，因此即使裝置取得了一個 IP 位置，也不見得能夠連入。<br>
**警示:** 某些 WiFi 網路(大部分是公用/免費網路)為了安全性的理由，阻擋連上它的裝置們彼此間的通訊。在這樣的網路環境上不一定能連入。例如在此網路環境中的筆電和手機將無法直接彼此通訊。

### 已知問題

1. 某些裝置上不會顯示通知圖示，但通知區域訊息實際存在。 Android bug: 213309。

### 畫面截圖

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
![](screenshots/screenshot_12.png)

## 函式庫

版本 3.x 基於  Clean Architecture, Single Activity and MVI patterns and use:
* [Kotlin](https://kotlinlang.org)
* [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines)
* [Android Jetpack libraries](https://developer.android.com/jetpack/)
* [Koin](https://github.com/Ekito/koin)
* [Material Dialogs](https://github.com/afollestad/material-dialogs)
* [Binary Preferences](https://github.com/iamironz/binaryprefs)
* [Ktor](https://ktor.io/)
* [Firebase Crashlytics](https://firebase.google.com/docs/crashlytics)
* [xLog](https://github.com/elvishew/xLog)
* [LeakCanary](https://github.com/square/leakcanary)

## 貢獻

如果你願意幫忙翻譯請將翻譯如下兩個文件：

1. https://github.com/dkrivoruchko/ScreenStream/blob/master/app/src/main/res/values/strings.xml 和
2. https://github.com/dkrivoruchko/ScreenStream/blob/master/data/src/main/res/values/strings.xml

然後請，[提交一個 request](https://help.github.com/en/articles/creating-a-pull-request) 或者把這兩個文件通過郵件 <dkrivoruchko@gmail.com> 附件發給我。

非常感謝你的貢獻。

## 開發者

Dmitriy Krivoruchko - <dkrivoruchko@gmail.com>

如果有任何問題或點子歡迎來信談談。

## 授權

```
The MIT License (MIT)

Copyright (c) 2016 Dmitriy Krivoruchko

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
