# Using MJPEG mode

[Overview](../README.md) · Usage · [Architecture](architecture.md) · [HTTP server](server.md)

The [overview](../README.md) explains what MJPEG mode offers and gives a quick start. This guide follows the order in which you'll usually need it: get the network and access ready, share your screen, help viewers, then fine-tune the settings.

## Contents

- [Make sure viewers can reach you](#make-sure-viewers-can-reach-you)
- [Decide who can watch](#decide-who-can-watch)
- [Share your screen](#share-your-screen)
  - [Stop sharing](#stop-sharing)
- [Watching in the browser](#watching-in-the-browser)
- [Using the stream in other apps](#using-the-stream-in-other-apps)
- [Choosing settings](#choosing-settings)
  - [Image](#image)
  - [Automation](#automation)
  - [Connection](#connection)
  - [Web page](#web-page)
  - [Access](#access)
- [Reading the statistics](#reading-the-statistics)
- [Troubleshooting](#troubleshooting)

## Make sure viewers can reach you

Viewers connect straight to your phone, so first check that their device can reach one of the addresses under **Stream addresses**. Most connection problems come from the network, not the app.

Each address shows the network it belongs to, such as Wi-Fi with a private IPv4 address. Give viewers an address on the network their own device is using.

- **Use the same network.** Put the viewer's device on the same Wi-Fi or wired network as your phone. Guest and public networks often stop devices from reaching each other, and a page that never loads is the usual sign.
- **No shared network?** Turn on your phone's hotspot and have the viewer join it. Some phones list the hotspot or USB tethering under network type **Other**. If no address appears, add **Other** to the [Network filter](#connection).
- **Special setups**, such as two devices on the same VPN, need the matching choice in the Network filter. An address in the list isn't proof that it can be reached, so try it from the viewer's device.
- **"This device only"** addresses work only in a browser on your phone itself. They're handy for a quick preview, not for viewers.

An address is ready once it no longer shows **Preparing…** or a problem. If it shows **Request permission** or **Open settings**, follow it to allow local network access. **Start stream** stays unavailable until at least one address is ready. If an address shows **Address in use**, another app is using the port. Tap **Retry**, or stop the stream and choose a different **HTTP port** in [Connection](#connection). Changing the port changes every address, so send new links afterwards.

Before an important session, open the link once on the viewer's device. If the page loads and shows the app logo or asks for the PIN, the connection works.

## Decide who can watch

By default there's no PIN, so anyone who can reach one of your addresses can watch. That's often fine at home. On a shared or public network, turn on **PIN protection** in [Access](#access) before you start, because access settings can't be changed while you're sharing.

With protection on, the PIN appears under Stream addresses, hidden until you tap **Show**. There are two ways to let someone in:

- **Send a link or QR code.** When an address has a protected link ready, its copy, share and QR code buttons give that out; it lets the viewer straight in, so keep it as private as the PIN itself. Until then, and always for some IPv6 addresses, the buttons give out the plain address instead, and the viewer is asked for the PIN.
- **Give out the PIN.** Anyone who opens the plain address, for example by typing it, is asked for the six-digit PIN. This suits a screen in a room, where you can simply read the PIN out.

Once a browser is in, it stays signed in to that address. A viewer who switches to another of your addresses is asked again.

A PIN decides who can open the stream, but it doesn't encrypt anything. It doesn't replace a network you trust.

**Which PIN option?** **New PIN on open** suits occasional sharing with different people, because a PIN you gave out stops working the next time you open MJPEG mode. **Permanent PIN** suits regular viewers, such as a family TV or a meeting-room screen. It keeps the same PIN and lets you type your own. Either way, access links still change each time MJPEG mode opens.

**What viewers notice when access changes:**

| What you do | What happens to viewers |
| --- | --- |
| Stop and start the stream | Links, the PIN and signed-in browsers keep working. |
| Leave MJPEG mode and open it again | Protected links stop working, so send new ones. With **New PIN on open**, the PIN changes too. |
| Change the PIN, or turn protection on | Everyone is disconnected and needs a new link or the PIN to come back. |
| Turn protection off | Viewers drop for a moment. Open pages reconnect by themselves, and from then on anyone who can reach an address can watch. |

**To remove someone,** stop the stream, choose **Generate new PIN** or type a new Permanent PIN, then start again. Send fresh links only to the people you still want watching.

**Wrong PINs.** With **Limit PIN attempts** on, as it is by default, a device that enters a wrong PIN five times has to wait before it can try again, even with the right PIN. The wait is 3 minutes by default, and the viewer page shows a countdown. People who are already watching aren't affected.

**Keep the PIN off your shared screen.** If you reveal the PIN to read it out while sharing the whole screen, tap **Hide** afterwards, because viewers can see it too.

## Share your screen

You can send the link before you start. Viewers who open it early see the inactive stream image, normally the app logo, or are asked for the PIN. The live picture appears by itself once you start.

1. **Send the link.** Pick an address on the network your viewers use and give it out with the copy, share or QR code button next to it. Viewers who type it need all of it, including `http://` at the start and the number after the colon.
2. **Tap Start stream** and approve Android's screen-sharing prompt. The button shows progress while the prompt is open, and viewers see a notice that your device is waiting for permission.
3. **Choose what to share** if Android offers a choice. Sharing a single app keeps your notifications, the status bar and other apps out of the picture, and the [Image](#image) settings then apply to that app's window. Sharing the whole screen shows everything, including notifications that pop up.
4. **Check the result** on a viewer's screen. **Statistics** under Stream addresses shows how many viewers are connected and how much data is going out (see [Reading the statistics](#reading-the-statistics)).

While you share, most settings take effect straight away, so you can adjust the picture and watch the result on a viewer.

### Stop sharing

Tap **Stop stream**, or use Stop in ScreenStream's notification. Android can also end sharing. Recent versions offer their own status-bar control and stop sharing when you lock the device. The [Automation](#automation) settings can stop it too, when the screen turns off or when your addresses disappear.

Stopping doesn't send viewers away. Their page stays open with a notice that the device isn't streaming, and the live picture returns by itself when you tap **Start stream** again. In between, they see the **Inactive stream image**, which is the app logo by default. With **Last frame**, your final screen stays visible after you stop, until a new picture replaces it after you start again, or until you leave MJPEG mode. Choose it only when that picture is safe to leave on display.

When you leave MJPEG mode, viewers are disconnected.

## Watching in the browser

Viewers can start watching without instructions. These tips help when someone asks:

- **To read small text,** choose **Original size**. **Fit to window** shows the whole picture, and **Fit to width** fills the width.
- **To keep watching while using other apps,** choose **Picture in picture** where the browser offers it. The floating window updates less often than the page. Some browsers ask the viewer to select Picture in picture a second time to open it.
- **If the picture won't load or keeps freezing,** open **More** and switch **Streaming method** from **MJPEG** to **JPEG**, which loads separate pictures one after another. **More** also shows the connection status, any notice from your phone and the size of the picture being received.
- **Notices explain pauses.** The page tells viewers when you're confirming the sharing prompt, when you've stopped or paused, or when the stream failed, so they know their own connection isn't the problem.
- **Reconnecting is automatic** after a brief network drop or when the viewer returns to the tab. If the picture can't recover by itself, the page offers **Retry connection**.

These choices apply only to the viewer's own browser. Your [Web page](#web-page) settings decide what the page looks like for everyone.

## Using the stream in other apps

Software that can show an MJPEG or JPEG web address, such as a media player or your own web page, doesn't need the viewer page. Open the menu next to an address's copy button and choose **Copy stream link (MJPEG)** for a continuous stream or **Copy image link (JPEG)** for a single picture.

A media player or similar app may have no way to enter the PIN, so with PIN protection on it needs a protected link from the app; share that as carefully as a page link. If the player is turned away, that address may only offer its plain link for now, which works in a browser with the PIN: wait a moment and copy again, or try another listed address. Copy fresh ones after the PIN changes or you reopen MJPEG mode. The [HTTP server guide](server.md#external-media-clients-and-cors) covers what such clients need.

## Choosing settings

Settings sit in five groups below Stream addresses: **Image**, **Automation**, **Connection**, **Web page** and **Access**. **Search settings** jumps straight to any of them. Each table's **Default** column shows what a fresh install uses.

Most settings take effect while you're sharing, so you can change something and watch the result on a viewer. A few can be changed only after the stream has fully stopped: the JPEG encoder, the HTTP port, and the Access settings other than hiding the PIN. The app tells you when a setting is unavailable.

### Image

These settings decide what part of the screen viewers see, how sharp it looks and how much data it costs. They apply within whatever you approved in Android's screen-sharing prompt.

| Setting | What it does and choices | Default |
| --- | --- | --- |
| Capture area | Shares the **Full area**, the **Left half** or the **Right half**. A half suits split-screen layouts. | Full area |
| Crop | Trims pixels from the left, top, right and bottom edges of the capture area, measured before rotation. The switch turns trimming on or off and keeps your numbers. | Off, nothing trimmed |
| Image size | **Scale:** a percentage of the captured size, up to 200%.<br>**Size:** a width and height of up to 8192 px. The picture fits inside and keeps its proportions.<br>**Stretch image** (with Size): fills the exact width and height, which may distort the picture. | Scale 50% |
| Rotation | Turns the picture clockwise by 0°, 90°, 180° or 270°. | 0° |
| Mirror | Flips the picture **Horizontal** or **Vertical**. The flip is applied after rotation. | None |
| Grayscale | Shows the picture in shades of gray. | Off |
| JPEG quality | A scale from 0 to 100. Higher gives crisper detail but larger pictures; lower saves data. | 80 |
| Frame rate | **Auto:** keeps updates as frequent as the phone can manage.<br>**FPS limit:** at most 1–120 new pictures per second.<br>**Interval:** at most one new picture every 1–3600 seconds, for screens that change slowly. | Auto |
| JPEG encoder | **Auto:** optimized encoding when the device supports it.<br>**Standard:** Android's built-in encoder, worth trying if you suspect the optimized one causes problems. | Auto |

- To make small text readable, raise **Image size** or **JPEG quality**. To save data or help a weak network, lower them or set an **FPS limit**.
- The Image size editor shows an estimate of the result. A shared app can turn out a different size. Going above 100% adds no detail and can slow streaming down.
- Frame rate choices are limits. The actual rate can be lower.
- When cropping, always leave part of the image visible, or no new pictures can be captured. Crop is for framing, not a way to hide private information.

### Automation

These settings decide what MJPEG mode does on its own while you share.

| Setting | What it does and choices | Default |
| --- | --- | --- |
| Stop stream after address loss | Stops the stream when none of the offered addresses remain for the chosen delay, from 10 seconds to 30 minutes. An address that comes back cancels the countdown. A shorter delay stops capture sooner; a longer one rides out brief Wi-Fi drops. This is always on; only the delay can change. | 60 seconds |
| Inactive stream image | What viewers see when there's no live picture: before Start, after Stop or while paused.<br>**App logo**, or **Last frame**, the final captured picture. Until a picture exists, Last frame shows the logo. | App logo |
| Keep screen awake | Asks Android to keep the display on while you stream. The screen may still dim, and it uses more battery. | Off |
| Stop when screen turns off | Ends the stream when the screen turns off. Turning the screen back on doesn't restart sharing. | Off |
| Warn about skipped frames | Shows "Frames skipped" on your phone when a viewer can't keep up. It controls only the message; streaming works the same either way. | On |

**Keep screen awake** helps when you present without touching the phone. On recent Android versions, locking the device ends screen sharing whatever you choose here.

### Connection

These settings decide which addresses the app offers to viewers. [Make sure viewers can reach you](#make-sure-viewers-can-reach-you) explains how to choose a network and what to do when an address isn't ready.

| Setting | What it does and choices | Default |
| --- | --- | --- |
| Network filter | Offers only addresses that match one selected choice in each group:<br>**IP version:** IPv4, IPv6<br>**Network type:** Wi-Fi, Ethernet, Mobile data, VPN, Other<br>**Address type:** Private, Non-private, Loopback, IPv4 link-local | IPv4<br>Wi-Fi, Ethernet<br>Private |
| HTTP port | The number after the colon in every address, from 1024 to 65535. Change it if an address reports **Address in use**. | 8080 |

- **Address types:**
  - **Private** covers ordinary home and office networks.
  - **Non-private** addresses can appear on some VPNs and mobile networks.
  - **Loopback** works only in a browser on the streaming phone.
  - **IPv4 link-local** addresses are self-assigned, for example when devices connect without a router.
- **Adding and removing choices:** more choices offer more addresses, but an offered address isn't guaranteed to be reachable. Removing a choice can disconnect viewers using an address that disappears.

### Web page

These settings change how the viewer page looks for everyone watching, and open pages update straight away. Each viewer's own choices, such as fit, full screen or the streaming method, stay in their own browser (see [Watching in the browser](#watching-in-the-browser)).

| Setting | What it does and choices | Default |
| --- | --- | --- |
| Background | The color around the picture and behind any bars: choose a preset, a hex color, or a red/green/blue mix. Bar text switches between dark and light to stay readable. | Very dark gray (`#101418`) |
| Stream name | The browser tab title and the label on the page, up to 64 characters. **Show stream name** hides only the page label; the tab still uses the name. | Your device's maker and model, shown |
| Stream name placement | **Position:** Top or Bottom.<br>**Display as:** Over image, which covers a little of the picture, or Separate bar, which never covers it but leaves slightly less room.<br>**Auto-hide** (Over image only): hides after a few seconds without interaction, or choose Always visible. | Top · Over image · Auto-hide |
| Controls placement | The same choices, for the viewing controls. | Bottom · Over image · Auto-hide |

- Rename the stream if you'd rather not reveal your device model. To show no name at all, clear it and turn off **Show stream name**. The tab then reads "ScreenStream".
- Use a separate bar when the edges of your screen matter, such as a toolbar or subtitles near the bottom.

### Access

These settings decide who can watch. [Decide who can watch](#decide-who-can-watch) explains how to let viewers in, which PIN option to choose and what viewers notice when access changes. Except for **Hide PIN when starting a stream**, you can change them only while the stream is stopped.

| Setting | What it does and choices | Default |
| --- | --- | --- |
| PIN protection | Requires the PIN or an access link from the app. Turning it on creates a PIN if none exists.<br>**New PIN on open:** a fresh PIN each time MJPEG mode opens.<br>**Permanent PIN:** keeps the PIN until you change it. | Off; New PIN on open |
| PIN | The six-digit code viewers enter. **Generate new PIN** works with either option; typing your own needs Permanent PIN. Changing the PIN signs everyone out. | None until protection is turned on |
| Limit PIN attempts | After five wrong attempts, pauses PIN entry from that network address for 1–60 minutes, even with the correct PIN. Viewers already watching stay connected. | On, 3 minutes |
| Hide PIN when starting a stream | Masks the PIN under Stream addresses again when sharing starts. **Show** still reveals it. | On |

Leave **Limit PIN attempts** on unless you have a reason not to. A longer block slows down guessing but also makes a viewer who mistyped wait longer.

## Reading the statistics

Tap **Statistics** under Stream addresses for a live view. While it's collapsed, its header still shows the frame rate, the number of viewer connections and the outgoing data rate. Use it to answer practical questions:

- **Is anyone watching, and who?** The **Viewer connections** list shows each connection's network address, how much the app has sent to it and its current data rate. An address you don't recognize is a reason to [change the PIN](#decide-who-can-watch). The list counts picture connections, not people. A browser still waiting at the PIN screen isn't listed, and one viewer can briefly appear twice after reconnecting. Recently disconnected viewers stay listed below for a short while.
- **How much data am I using?** **Outgoing traffic** shows how fast pictures are leaving your phone right now, for all viewers together, in Mbps. Its chart covers the last minute. Use it to judge whether to lower the image size or quality, especially on metered networks.
- **Is it smooth?** The **Frame rate** chart shows how often your phone produces a new picture. **Falling behind** next to a viewer means that viewer is missing pictures.
- **Which settings cost the most?** **Engine statistics** shows details such as average JPEG size and encoding time. Compare them before and after a change.

These figures are measured on your phone. They show what was sent, not what each viewer actually saw. To confirm the picture, look at a viewer's screen.

## Troubleshooting

| What you see | What to try |
| --- | --- |
| **Start stream** is greyed out, or "No stream addresses available" | Connect the phone to Wi-Fi or another network, then complete any permission button under Stream addresses. Check that the Network filter includes your connection type (hotspots sometimes count as **Other**). Retry a failed address, or stop and pick another port. |
| The viewer's browser can't open the page | Check that both devices are on the same network and that it isn't a guest or public network. Type the address exactly as shown, starting with `http://` (not `https://`) and including the port. |
| The page opens but stays on "Waiting for an image…" or shows "Image unavailable" | Use **Retry connection**, or open **More** and switch to **JPEG**. The switch affects only that browser. |
| Viewers see that the device isn't streaming, or is waiting for screen sharing permission | If it isn't streaming, tap **Start stream**. If it's waiting for permission, finish the screen-sharing prompt on your device. |
| The page says this browser can't connect | Try another up-to-date browser. |
| The viewer is asked for a PIN unexpectedly, or a link stopped working | Access details change when MJPEG mode reopens or the PIN changes. A browser also signs in separately for each address, so switching to another listed address asks again. Send a fresh link or the current PIN. If PIN entry is blocked, wait for the countdown to end. |
| "Streaming is paused…" appears in the app | If it says the image settings don't fit, adjust the crop, capture area or image size. If it mentions device resources, choose a smaller image size. If screen capture is unavailable, wait a moment, or stop and start the stream again. |
| Black or missing areas in the picture | Android may hide content from some apps, such as protected video or banking apps. Also check whether you shared one app or the whole screen. Colors can also differ from your screen, especially with HDR video. |
| The stream stopped by itself | Check **Stop when screen turns off** and **Stop stream after address loss**. On recent Android versions, locking the device or using Android's status-bar control also ends sharing. Tap **Start stream** again. |
| Choppy picture or "Frames skipped" | Lower the image size, JPEG quality or frame rate, or move the viewer closer to the Wi-Fi router. |
| Viewers still see your screen after you stopped | **Inactive stream image** is set to Last frame. Switch it to the app logo. |
| "Still stopping the stream…" doesn't go away | Wait a little longer. If the app offers to restart the mode, do so, then start the stream again. |
