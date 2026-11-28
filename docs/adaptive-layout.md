# Adaptive layout on Android

ScreenStream adapts to the current app window in portrait and landscape. Layout follows the window dimensions and the first eligible separating or occluding fold. Content respects system bars, display cutouts and desktop window controls.

| Window or transition | ScreenStream behavior |
| --- | --- |
| Window with no separating or occluding fold dividing its usable area | The main screen uses one scroll area for the overview and settings below 840 dp of app window width, as on most phones and foldable cover displays. At 840 dp or wider, it uses side-by-side overview and settings panes regardless of height. Tablets and unfolded foldables follow the same rule. |
| Book posture or vertical hinge | When both regions are usable, the overview and settings sit on opposite sides of the hinge. The hinge takes priority over the width rule. |
| Tabletop posture or horizontal hinge | When both regions are usable, the header sits above the hinge and the main scroll area and capture action sit below it. A banner can appear above the hinge if at least half of the upper region remains available for the header. |
| Hinge leaves only one usable region | The main screen uses that region for either hinge orientation, including on a flat dual-screen device. |
| Desktop, freeform or split-screen window | The layout follows the current app window as it is resized, maximized or restored. The same width, height and hinge rules apply. |
| Short or very tight window | When the header and body share a region below 480 dp of usable height, the banner is hidden and the header can scroll with the content. Essential controls should stay reachable in tight windows. Visual polish is best effort at extreme sizes. |
| Opening an adaptive overlay | A fold that defines a physical pane places the overlay in a dialog within that pane. Otherwise, a window at least 600 dp wide and 480 dp tall uses a centered dialog. If either dimension falls below its threshold, the overlay uses a bottom sheet. In windows at least 600 dp wide, the sheet is capped at 480 dp. |
| Resizing or folding with an adaptive overlay open | The open overlay applies the same rules to the current window and can switch between a dialog and a bottom sheet. |
| QR previews, exit and update confirmation | These overlays always use dialogs. |
| Pressing Escape with an adaptive overlay open in a desktop window | Escape dismisses the overlay or goes back one page if the overlay has its own Back action. Editors that save on dismissal can apply valid changes when closed with Escape. |
| Rotating, folding, unfolding or resizing | The goal is to preserve the current task, draft input and scroll position through reflow and Android screen recreation. When unfolding switches to the split layout, the settings pane uses its own scroll position. When a view returns, it reuses its saved scroll position within the available scroll range. |
| Showing the on-screen keyboard or changing window insets | Keep the active input and its controls reachable. The goal is to restore editing focus and the keyboard when a transition interrupts typing. A posture change can preserve an editor’s draft while the field loses focus and the keyboard closes. |
| Changing size or posture during active capture | Capture should continue through supported rotation, fold and unfold transitions and window resizing while the Android projection stays active. The output should follow the orientation and proportions of the captured region, accounting for the user’s framing, rotation and sizing choices. |

The combined, overview and settings scroll areas show a transient scrollbar while their content scrolls. The capture action, when available, stays fixed over a fade at the bottom of the primary scroll area, and the last item can scroll clear of it. The app update bar appears at the top of the main screen header when needed.

## Android and Google Play baseline

Beginning in April 2027, new and updated standard apps on Google Play must meet Tier 3 adaptive quality on foldables. [Google Play announcement](https://developer.android.com/blog/posts/google-play-adaptive-quality-standards)

Relevant Tier 3 criteria include filling the app window, preserving state including the keyboard through combined configuration changes, keeping visible UI updated when unfocused in multi-window mode, basic external input support, and correctly oriented and proportioned screen capture. The breakpoints and pane arrangements above are ScreenStream product choices. [Android Tier 3 criteria](https://developer.android.com/develop/adaptive-apps/quality-guidelines/adaptive-app-quality/tier-3)
