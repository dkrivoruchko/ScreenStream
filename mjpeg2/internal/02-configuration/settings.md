# Settings

`MjpegSettings` stores user choices; the controller decides which observed values can become effective. A successful save is not confirmation that capture or HTTP has applied it. This page owns the saved model, validation, compatibility and edit policy. [Coordination](../01-lifecycle/coordination.md) owns controller execution, [Network addresses](network-addresses.md) owns discovery, and [HTTP delivery](../03-delivery/http-delivery.md) owns runtime viewing credentials.

## Contents

- [Saved model and defaults](#saved-model-and-defaults)
- [Validation and image interpretation](#validation-and-image-interpretation)
- [PIN generation and edits](#pin-generation-and-edits)
- [Edit policy and effective settings](#edit-policy-and-effective-settings)
- [Storage, serialization and recovery](#storage-serialization-and-recovery)
- [Legacy import](#legacy-import)
- [Implementation and verification](#implementation-and-verification)

## Saved model and defaults

One `Data` snapshot contains five groups: `image`, `network`, `access`, `web` and `behavior`. The native UI presents them as Image, Connection, Access, Web page and Automation, with search across all five groups. Behavior switches apply live; saving a field does not confirm that the controller has applied it.

### Image

| Field | Values | Default |
| --- | --- | --- |
| `sourceRegion` | `Full`, `LeftHalf`, `RightHalf` | `Full` |
| `cropEnabled` | Boolean | `false` |
| `cropInsets` | Nonnegative left/top/right/bottom pixels | All zero |
| `outputSelection` | `Scale`, `TargetSize` | `Scale` |
| `scalePercent` | Finite positive scale percentage | `50.0` |
| `targetSize` | Width, height and `AspectFit`/`Stretch`; nullable while unselected | `null`; target-size content mode defaults to `AspectFit` |
| `rotation` | Clockwise 0°, 90°, 180°, 270° | `Degrees0` |
| `mirror` | `None`, `Horizontal`, `Vertical` | `None` |
| `colorMode` | `Color`, `Grayscale` | `Color` |
| `jpegQuality` | Integer 0–100 | `80` |
| `frameRate` | `Auto`, `MaxFps(1..120)`, `SamplingInterval(1 second..1 hour)` | `Auto` |
| `jpegBackendPolicy` | `Auto`, `FrameworkOnly` | `Auto` |

Crop and target-size values remain saved when disabled or unselected. The system capture-consent result selects screen/app capture; `sourceRegion` selects within that granted capture area. Frame-rate limits govern fresh capture production, while [HTTP repeats](../03-delivery/jpeg-delivery.md#writing-jpeg-and-mjpeg) reuse already encoded bytes.

### Network

| Field | Values | Default |
| --- | --- | --- |
| `filter.families` | Nonempty set of `Ipv4`, `Ipv6` | `Ipv4` |
| `filter.interfaceTypes` | Nonempty set of `Wifi`, `Ethernet`, `Mobile`, `Vpn`, `Other` | `Wifi`, `Ethernet` |
| `filter.categories` | Nonempty set of `Private`, `NonPrivate`, `Loopback`, `LinkLocal` | `Private` |
| `httpPort` | Integer 1024–65535 | `8080` |

Selecting every option saves the full set. Address support, category meanings and filter matching are defined in [Network addresses](network-addresses.md#classification-and-filtering). Selection proves neither successful bind nor viewer reachability.

### Access

| Field | Values | Default |
| --- | --- | --- |
| `pinEnabled` | Boolean | `false` |
| `pinPolicy` | `Permanent`, `NewOnModuleStart` | `NewOnModuleStart` |
| `pin` | `SecretValue` containing six ASCII digits, or null | `null` |
| `hidePinOnCaptureStart` | Main PIN-row display preference | `true`; capture-state changes hide a manually revealed PIN |
| `limitWrongPins` | Boolean | `true` |
| `blockMinutes` | Integer, at least 1 | `1` |

Leading zeros are valid. `SecretValue.toString()` returns `<redacted>`; serialization preserves its actual value. Reveal `value` only for an explicit user action. The saved PIN differs from the unsaved URL token and viewing cookie described in [HTTP access](../03-delivery/http-delivery.md#access-generations-and-pin-blocks).

### Shared web appearance

| Field | Values | Default |
| --- | --- | --- |
| `background` | `#` plus six hexadecimal RGB digits | `#101418` |
| `title` | Text, including an explicitly empty string | Trimmed manufacturer + model without repeated manufacturer; fallback `Android device` |
| `titleEnabled` | Boolean | `true` |
| `titleLayout` | Position, presentation, auto-hide | Top, Overlay, `true` |
| `controlsLayout` | Position, presentation, auto-hide | Bottom, Overlay, `true` |

Each `BlockLayout` independently stores `Top`/`Bottom`, `Overlay`/`Bar` and `autoHide`. The selected RGB background fills the viewing canvas and separate bars when an image is displayed; access screens and states without a displayed image use neutral system-theme backgrounds. Bars reserve page space and stay visible; `autoHide` applies only to overlays, without changing its saved or wire value. At a shared edge controls are outermost and the title sits inward. An outer controls overlay followed by an inner title bar reserves the complete stack to preserve that order without overlap. Browser-local fit mode, alternate media method, menu and information choices are not saved in this model. [Web client](../03-delivery/web-client.md#presentation-and-browser-local-choices) owns their current behavior.

Native placement editors show the Auto-hide switch only for overlays and no auto-hide row for bars. Bar summaries show Always visible while preserving the saved auto-hide preference.

### Stream behavior

| Field | Values | Default / current effect |
| --- | --- | --- |
| `keepScreenAwake` | Boolean | `false`; best-effort screen-on request during confirmed capture |
| `stopOnScreenOff` | Boolean | `false`; device sleep stops the current capture attempt when enabled |
| `stopAfterNetworkLossSeconds` | Integer 10–1800, with no Never value | `60`; implemented |
| `postStopImage` | `None`, `Placeholder`, `LastAvailableFrame` | `Placeholder`; fallback whenever no current capture image exists |
| `notifySlowViewers` | Boolean | `true`; live device Toast warnings for sampled slow MJPEG responses during active capture |

The implemented timeout watches an empty selected-address list, independently of bind failure. [Coordination](../01-lifecycle/coordination.md#network-loss-deadline) defines network-loss timing, [keep-awake ownership](../01-lifecycle/coordination.md#keep-screen-awake) and the separate [slow-viewer warning policy](../01-lifecycle/coordination.md#slow-viewer-warnings); [JPEG selection](../03-delivery/jpeg-delivery.md#capture-source-and-image-selection) defines the common inactive-image fallback. The historical saved field and enum names remain unchanged; Last frame uses the logo if no real frame exists. Slow statistics continue independently of notification settings, and Android can terminate MediaProjection regardless of saved behavior choices.

## Validation and image interpretation

Model construction validates values before persistence. `ImageSettings` requires a finite `scalePercent` whose division by 100 remains positive, JPEG quality in 0–100, and a non-null target size when `TargetSize` is selected. Engine value types additionally require nonnegative crop insets, positive target dimensions and the frame-rate ranges above.

Value validity does not establish runtime geometry or resource feasibility. Region selection precedes crop; crop precedes rotation; mirror follows rotation; output size describes the transformed image. A half-region needs source width at least two pixels, and crop must leave a nonempty region. The engine can reject a saved request against actual capture geometry. Scaling above 100% is allowed; 100% preserves transformed size. `Auto` follows available source frames and processing capacity. A frame-rate cap or interval never promises that rate of successful production or network delivery.

Network construction requires all three saved filter groups to be nonempty and the saved port in 1024–65535. The lower-level HTTP contract accepts ports 1–65535; that broader range does not expand the saved settings UI policy. Access validates six ASCII digits when a PIN exists and a positive block duration. Web appearance validates RGB background; behavior validates its timeout range.

## PIN generation and edits

Creating or reading `MjpegSettings` does not generate a PIN. `updateData` enables protection with a missing PIN by generating and saving one in the same transaction. Generation uses `SecureRandom` over 0–999999 and pads to six digits.

At actual protected module activation, `NewOnModuleStart` regenerates once before the settings observation stream opens HTTP access. Capture Start/Stop and background return retain that PIN. An open activation also completes initial preparation: enabling protection later generates a PIN only if absent, without repeating startup regeneration. Disabling protection retains the saved PIN.

`Permanent` keeps its PIN until a typed edit or random regeneration. The Access settings group saves both through the same `updateAccess` path as its other edits. Random regeneration works for either policy; typed edits require six ASCII digits and Permanent policy, rechecked against the latest stored access settings inside the transaction. Both follow the current setup edit policy and the shared settings mutex. [Coordination](../01-lifecycle/coordination.md#effective-settings-and-saves) owns runtime application; [HTTP delivery](../03-delivery/http-delivery.md#access-generations-and-pin-blocks) owns credential rotation and wrong-PIN blocks.

## Edit policy and effective settings

`EditPolicy` is runtime-only. `All` applies when the controller is idle after initial settings acceptance or successful capture cleanup, even if no HTTP server is listening. `LiveOnly` covers consent, startup, capture, suspension, stopping and cleanup, including failed cleanup.

| Change | Allowed under `LiveOnly`? |
| --- | --- |
| Image processing and frame rate | Yes |
| `jpegBackendPolicy` | No |
| Address filter | Yes |
| HTTP port | No |
| `hidePinOnCaptureStart` | Yes |
| Other access settings | No |
| Web appearance and behavior | Yes |

`allows(current, proposed)` compares the actual delta, permitting unchanged restricted fields within a live transaction. `effective(current, proposed)` accepts live fields while retaining the current backend, port and access settings except `hidePinOnCaptureStart`. The UI policy and controller application check are separate: a late observation cannot replace busy setup values. [Coordination](../01-lifecycle/coordination.md#effective-settings-and-saves) applies the latest observed setup after successful cleanup.

```mermaid
flowchart LR
    E[Settings editor] --> S[Atomic latest-snapshot save]
    S --> O[Settings observation]
    O --> P{Edit policy}
    P -->|All| A[Accept all values]
    P -->|LiveOnly| L[Keep current setup and accept live values]
    A --> C[Controller applies effective values]
    L --> C
```

The flow separates persistence from application. Live image values survive startup, backend choice is fixed for the capture session, and an image-fallback edit applies immediately whenever there is no current capture image. A current real image remains selected; the latest real frame survives fallback changes until replacement or HTTP disposal.

### Native settings controls

The five groups use compact, vertically centered rows with descriptions and values. Search matches localized group names, setting titles, descriptions and indexed options; all query tokens must match. User values, PINs and URLs are not indexed. Search exposes matching rows without replacing the user's manual group-expansion choices. `keepScreenAwake`, `stopOnScreenOff`, `notifySlowViewers` and `hidePinOnCaptureStart` are live switches; other access changes require setup edit admission.

Capture area, rotation, mirror and encoder open anchored exclusive-selection menus. Encoder options have individual explanations; under `LiveOnly` its row opens a read-only explanation instead. Crop has separate row/open and enable-switch actions; its form edits four pixel insets in two pairs. Size uses Scale/Size segmented choices, a full-width scale field or paired dimension fields, and a Stretch switch (`false` means `AspectFit`). Without a saved target, local dimension drafts start at half the raw screen width and height, rounded upward. Opening the form does not save this seed. The UI accepts scale greater than 0 through 200% and dimensions from 1 through 8192 pixels per side; the model retains its broader bounds. Out-of-range drafts remain editable and are not saved. Scale above 100% shows a nonblocking enlargement warning; a valid target also warns when its estimated output pixel count exceeds the estimated post-region/crop/rotation source at scale 1. This comparison adds no hard area limit. Its source/result dimensions are estimates from current screen metadata; app capture can differ, and the estimate does not confirm applied capture geometry.

Quality opens an anchored slider popup with a value label and an explanation of the quality/data tradeoff and factory default. Slider movement updates only the local integer draft; releasing it does not save. Dismissal submits one changed value. The row displays the saved value and the bubble displays the draft. Frame rate uses Auto/FPS/Interval segments. Only the selected mode's explanation and, for FPS or Interval, its numeric field and neutral help are shown. Missing FPS and Interval drafts start at 30 and 1 second respectively; existing active values are preserved. Mode selection and numeric input remain local until dismissal. Grayscale toggles directly from its row. Factory-default labels use the model's agreed defaults independently of the saved selection; menus mark the default option. These labels do not reset settings or change selection.

Connection provides a filter editor and setup-only HTTP port form. Filter chips preserve at least one selected option per group; a completed edit changes only the filter, retaining the latest port. Automation provides the network-loss timeout form and **Inactive image** menu. Web page provides RGB/HEX background editing, stream-name visibility and text, separate title/control placement forms. Stream-name input accepts at most 64 UTF-16 code units; an older longer value may be shortened without automatic truncation. Invalid HEX stays local and does not replace the preview. Each placement editor saves only changed layout properties against the latest stored layout. Access provides protection and PIN-policy controls, explicit Show/Hide, manual PIN editing and immediate regeneration, and a wrong-PIN limiter with a 1–60 minute UI range. The model accepts longer positive block durations; the form reports them as invalid without automatically rewriting storage. Manual PIN editing requires Permanent policy and setup admission. The secret form follows regenerated saved PINs until the user edits its draft.

Native menu, switch, row, numeric-field and editor primitives are shared in [Components](../../src/main/kotlin/io/screenstream/mjpeg/ui/settings/Components.kt); setting-specific state and validation stay with each row. Forms use a sheet without a drag handle, capped at 480dp, when the native window is narrower than 600dp; otherwise they use a centered dialog capped at 440dp. Each shared form has a growing tonal header and bounded scrolling body beginning with the main-row description. Numeric editors retain their opening locale for formatting, admission and parsing. Shared numeric fields reject complete proposed edits containing nonnumeric characters, signs, grouping or an extra decimal separator instead of stripping characters. Empty and incomplete decimal drafts remain editable; unchanged-text selection/composition updates pass through intact. Integer overflow, nonfinite decimal values and configured input maxima are rejected before accepting text; positive lower bounds, save ranges and complete compounds validate before saving.

Forms and the Quality popup save one changed valid result on dismissal, including the close button, Back or outside dismissal; keyboard Done only clears focus. Invalid, unchanged or reverted drafts do not write. Main-list switches and exclusive dropdown choices save immediately; Generate PIN saves immediately through the Access settings group. Opening a form writes nothing. Crop and Size validate the complete active compound and retain inactive crop/target choices. The separate crop switch changes only its enabled flag using the latest saved insets.

Group edits launch `UNDISPATCHED` to reach the singleton-owned mutex before the first suspension, capture the final draft before the deferred transform and change only their fields in the latest complete settings snapshot. Groups with setup restrictions recheck current edit policy inside the transaction. All callers share the mutex before the dispatcher hop, including edits admitted across composition disposal/recreation. Ordering follows arrival at the mutex, with FIFO suspended waiters; it does not establish a wall-clock order for concurrent caller intentions. Closing a form does not cancel an admitted write. Invalid text remains local, without overwriting saved choices; delayed observations do not replace a newer draft. Group save failures, including PIN edits and regeneration, are logged and sent to the screen-owned Snackbar, separately from input errors. Cancellation is not reported as a save failure, and reopening the screen does not replay earlier failures. Neither saving nor successful completion confirms runtime application.

Nonsecret form/menu visibility, initial values, drafts and group expansion use `retain`; form inputs stay above the sheet/dialog branch. The root retains a settings snapshot with `access.pin` removed so retained editors can compose before the first fresh settings emission. This snapshot is for presentation and is never written back wholesale. PIN value drafts, secret-form visibility and reveal state use only `remember`, without retained or saved state. Retention does not promise restoration after process death, module removal or disposal of the owning lazy item.

The Stream addresses row shows the applied protected PIN, initially masked. Its local Show/Hide choice resets on module/PIN changes and, with hiding enabled, transitions between Start and Busy/Stop. With hiding off, Start and Stop preserve that choice. During consent, startup, capture or pending cleanup, an observed applied change to `hidePinOnCaptureStart` in either direction also resets a manually revealed PIN to hidden. Neither Off nor Stop automatically reveals it, and Show remains available. After processed cleanup failure the preference can still be saved, but there is no active visibility flag to reset and Start remains blocked. Access settings keep independent saved-PIN controls. An attempt or preference transition missed entirely by UI collection produces no later reset; there is no render-before-capture guarantee. The single connection hint explains access links and their QR codes alongside manual PIN entry, regardless of current address availability.

## Storage, serialization and recovery

One `mjpeg.preferences_pb` Preferences DataStore file holds a JSON string under the shared store's `DATA` key. Related edits are atomic across the five groups. URL tokens, viewing cookies, discovered addresses, effective state, edit policy and statistics are runtime-only; the PIN is saved in the access group. A temporary [legacy import](#legacy-import) runs before the first read or update.

The controller collects the settings flow to apply configuration; the module root collects it with the UI lifecycle and passes each group its values. There is no separate permanent settings collector keeping an inactive module running.

The serializer ignores unknown fields and normally omits properties equal to defaults. The `web` group is required; `title` is always encoded so a stored device title survives later launches rather than being recalculated. An explicitly empty title stays empty. The removed `retainImageOnReconnect` property is ignored when reading older saved payloads; it has no current setting or wire field.

Custom serializers preserve crop as four integers, target size as width/height/content mode, the filter as three sets, and frame rate as `Auto`, `MaxFps` or `SamplingInterval` with an ISO duration string. Engine value validation runs during reconstruction.

`updateData` transforms the latest stored model, not a potentially stale `data` observation. A missing or invalid payload supplies defaults as transaction input; a resulting encoded value can replace invalid data even when it equals defaults. The singleton enters `NonCancellable`, acquires one fair [Mutex](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/lock.html), then switches to its dispatcher while holding the lock through the complete storage transaction. Every `updateData` caller uses this path; it has no UI-specific entrypoint or Main-thread requirement. Transform, PIN generation, encoding and storage remain on the configured dispatcher. Failures propagate; cancellation of the original caller does not abandon an admitted write. Observation may arrive after saving returns.

The common store caches the last successfully decoded raw payload/model. Recoverable I/O or serialization failure receives one repair attempt. Successful repair resumes reading the latest file; failed repair emits defaults until a normally completed update or a new collection. Corruption handling can replace the preferences file with an empty value. Unrelated failures and cancellation propagate. Observing defaults after recovery is not proof that a replacement write succeeded.

## Legacy import

Before access, a present `MJPEG_settings.preferences_pb` triggers one migration attempt into `mjpeg.preferences_pb`. These literal file names survive a Gradle module rename. The public Preferences serializer reads the source directly; there is no second source DataStore or dependency on the old module. Any raw-present destination `DATA` wins as a whole, including wrong-typed or malformed data that normal recovery subsequently handles.

Only actual present, correctly typed source values participate. Convert to the new domain, validate, and discard values equal to the new defaults. Missing values use a captured new-default snapshot, never the old settings model or its fallback defaults. If the final snapshot equals the defaults, no `DATA` is written. Otherwise the current serializer omits default properties and retains the required complete representation of nondefault compound values. Migration removes the synthetic device title but keeps required `web:{}` when empty. Normal later writes retain their usual title/default encoding; an unchanged update may leave the migration payload untouched.

Crop enabled state plus all four insets are one compound. Target dimensions, content mode and derived selection are another; both dimensions must be present and positive, and missing stretch uses new AspectFit. The entire address filter, including all family flags, interface/category masks and participating historical keys, is one compound. Any present invalid member rejects its whole compound. Missing members use new defaults; no valid subset of an invalid compound survives. Scale, rotation, port and other scalar settings, including the optional PIN, are independently validated. Migration never generates a PIN.

Image mappings preserve region, crop, scale, color, quality, rotation and mirror. Positive FPS 1–120 becomes MaxFps; zero is default Auto; negative seconds become SamplingInterval within 1 second–1 hour, with overflow-safe magnitude conversion. Target bounds swap for accepted 90°/270° rotation because old bounds preceded rotation. Access and behavior flags map to their corresponding saved preferences; six ASCII PIN digits retain leading zeros. RGB background drops the alpha byte. The removed legacy `HTML_KEEP_IMAGE_ON_RECONNECT` preference is not imported.

Interface mask zero selects all five types; positive known bits select literal types, which can narrow old unknown/loopback admission. Address mask zero selects Private, NonPrivate and Loopback; the old PUBLIC bit becomes NonPrivate. LinkLocal remains excluded as in old discovery. Unknown mask bits reject the whole filter. Actual IP flags modify membership starting with new IPv4-only defaults; an empty result rejects the whole filter. Modern raw mask presence blocks historical fallback even when invalid. Otherwise `USE_WIFI_ONLY`, `ENABLE_LOCAL_HOST` and `LOCAL_HOST_ONLY` use their historical-format conversion; absent historical hostname companions mean false only within that conversion.

`HTML_SHOW_PRESS_START` approximates ordinary Stop as Placeholder/LastAvailableFrame; restart/error behavior is not equivalent. `NEW_PIN_ON_APP_START` maps to NewOnModuleStart/Permanent with the new activation semantics. `AUTO_CHANGE_PIN`, `STOP_ON_CONFIGURATION_CHANGE`, `HTML_ENABLE_BUTTONS` and `HTML_FIT_WINDOW` have no counterpart and are omitted. New title/layout/backend/block-duration/network-loss fields are not imported.

The exact source path is deleted nonrecursively in `migrate`'s `finally`, even for no-op, invalid/corrupt input, an existing destination or ordinary read/conversion failure. Corrupt protobuf gets a nonsecret diagnostic and no import; ordinary failures propagate. A deletion failure remains visible and is suppressed onto a primary migration failure when both occur. `cleanUp` does nothing; no completion marker is saved. Deletion occurs **before DataStore commits**, so a destination write failure or interruption after deletion permanently loses the legacy values and cannot retry import. Termination before deletion, filesystem deletion failure or restoring/recreating the source can permit another attempt. The migration is currently enabled; during coexistence the first attempt consumes the initial snapshot and later legacy use may recreate its file.

## Implementation and verification

- [MjpegSettings](../../src/main/kotlin/io/screenstream/mjpeg/settings/MjpegSettings.kt) owns storage and edit policy; [settings models](../../src/main/kotlin/io/screenstream/mjpeg/settings/MjpegSettingsModels.kt) own defaults, serializers and validation.
- [MjpegSettingsMigrations](../../src/main/kotlin/io/screenstream/mjpeg/settings/MjpegSettingsMigrations.kt) owns temporary legacy conversion and source deletion.
- [JsonPreferencesStore](../../../common/src/main/java/info/dvkr/screenstream/common/settings/JsonPreferencesStore.kt) owns common transaction and recovery behavior.
- [Controller](../../src/main/kotlin/io/screenstream/mjpeg/MjpegStreamingModuleController.kt) owns startup PIN preparation and effective application; [engine parameters](../../../screen-capture-engine/src/main/kotlin/io/screenstream/capture/ScreenCaptureParameters.kt) own image value types.
- [Testing](../04-testing/testing.md) describes verification scope and evidence requirements.

Navigation: [Internal guide](../README.md) · [Usage](../../docs/usage.md) · [Architecture](../../docs/architecture.md).
