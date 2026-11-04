# MJPEG mode

<!-- Optional future screenshot: the Stream tab with "MJPEG: Direct browser viewing" selected and its Stream addresses. -->

MJPEG mode lets other people watch your Android screen in an ordinary web browser. In the app it's called **MJPEG: Direct browser viewing**. While the mode is open, your phone or tablet works as a small web server. Viewers open a link to it, and the page shows your screen as a fast series of JPEG pictures. Viewers don't need an account, an extra app or an internet connection. They only need a network connection that reaches your device.

## What you can do

- **Watch in a web browser**
  - Viewers open a link, scan a QR code or type the address the app shows.
  - Viewers just open the link to start watching. The viewer page reconnects by itself after short interruptions.
  - Viewers can fit the picture to the window, show it at original size or fill the width. Full screen and picture in picture work where the browser supports them.
  - If a picture loads poorly, viewers can switch from the continuous MJPEG stream to separate JPEG pictures.
  - Other software that can show MJPEG or JPEG web addresses can use direct stream and image links.
- **Choose what viewers see**
  - Share your whole screen, or a single app when your Android version offers that choice.
  - Show the full capture area or only its left or right half. You can also trim the edges, rotate, mirror or turn the picture gray.
  - Set the image size, JPEG quality and frame rate to balance sharpness against data use. Most of these changes apply while you're sharing.
  - When there's no live picture, viewers see either the app logo or the last captured picture, whichever you choose.
- **Decide who can watch**
  - Turn on an optional six-digit PIN.
  - Invite viewers with protected links or QR codes from the app, so they don't have to type the PIN.
  - Make a device wait after repeated wrong PINs.
- **Style the viewer page**
  - Set the stream name, the background color and where the name and controls appear.
- **Keep an eye on the stream**
  - Check the frame rate, connected viewers and outgoing data in live statistics.
  - Get an optional warning when a viewer can't keep up.
  - Keep the screen awake while sharing, or stop sharing when the screen turns off.
  - Sharing stops by itself when none of your device's addresses has matched your Network filter for a while, for example after you leave Wi-Fi. You choose how long.

## How sharing works

1. On the **Stream** tab, look at the streaming-method card at the top. If it shows another method or **Choose a streaming method**, tap the card and choose **MJPEG: Direct browser viewing**.
2. Look under **Stream addresses**. The app lists the addresses where viewers can find your device, for example `http://192.168.1.23:8080/`. If an address shows **Request permission** or **Open settings**, follow it to allow local network access.
3. Tap **Start stream** and approve Android's screen-sharing prompt. ScreenStream may show a short explanation first. What happens next depends on your Android version:
   - Recent versions ask every time, and some let you share one app instead of the whole screen.
   - Older versions may reuse your earlier approval after a normal Stop, so the prompt doesn't appear.
4. Give viewers the link. Use the copy, share or QR code button next to an address, or have viewers type the address exactly as shown, including `http://` and the number after the colon.
5. Tap **Stop stream** when you're done. Android can also end sharing, for example from its own status-bar control. The page stays open for viewers and shows the inactive picture until you start again or leave MJPEG mode.

Viewers can open the page before you tap Start. They see the inactive picture until your live screen arrives, so you can send the link first and start when you're ready.

## Who can watch

**By default there's no PIN.** Anyone who can reach one of the listed addresses can watch. On a network you don't fully trust, turn on **PIN protection** in the Access settings.

With PIN protection on, viewers get in one of two ways:

- **A protected link or QR code from the app.** It carries a hidden access key that lets the viewer in without the PIN. Treat it like a house key. It can remain in chat history, clipboard history or a browser's history.
- **The plain address.** Viewers enter the PIN. The app also shares the plain address when an address isn't ready for a protected link yet, and for some kinds of address.

Once a viewer is in, their browser stays signed in to that address. Protected links get a new access key each time you open MJPEG mode. If PIN protection is on and you've chosen **New PIN on open**, the PIN changes then too. To remove someone, change the PIN and send new links only to the people you still want watching.

## Things to know

- **No sound.** Only pictures are sent.
- **The connection isn't encrypted.** The stream uses plain HTTP. Your pictures, links and PIN can be read by anyone who can observe the network between your device and a viewer. Use networks you trust.
- **The network decides who can connect.** Viewers connect directly to an address listed in the app, with no relay or cloud service in between.
  - Usually both devices need to be on the same Wi-Fi. Guest and public networks often block connections between devices.
  - Viewers aren't limited to your local network. If you allow VPN, mobile-data or non-private addresses in the Network filter, devices elsewhere may reach them when the network allows it.
  - A listed address isn't always reachable. Addresses marked "This device only" work only in a browser on your own device.
- **Viewers see exactly what you share.** That includes notifications and a revealed PIN. **Hide PIN when starting a stream** is on by default. If the inactive picture is set to **Last frame**, your final screen stays visible after you stop.
- **Some content may appear black.** Android can hide protected content, such as some video or banking apps.
- **Data use grows with each viewer.** Every viewer receives their own copy of each picture. The frame rate is best effort. A viewer who can't keep up skips pictures instead of falling behind.
- **Statistics come from your device.** They show what was sent, not what each viewer actually saw.

## Learn more

| Guide | Read it to |
| --- | --- |
| [Usage](docs/usage.md) | Get viewers connected, decide who can watch, share and stop, help viewers in the browser, choose settings, read statistics and fix common problems |
| [Architecture](docs/architecture.md) | Understand how the app, screen capture, the web server and viewers fit together |
| [HTTP server](docs/server.md) | Connect your own viewer or image client: links, access, image formats and viewer state |

Developers who maintain this mode can continue with [MJPEG internals](internal/README.md).
