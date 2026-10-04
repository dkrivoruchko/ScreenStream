[Scenario index](../verification-contracts.md) · [Running tests](../testing.md)

# Settings and saved compatibility

Behavior: [maintained contract](../../02-configuration/settings.md). Pure values and explicit-title saved JSON use L0; Android-derived titles and preferences use L1. Isolated storage paths and test-owned dispatchers avoid competing store owners. Fresh-instance checks copy saved bytes to a new path, rather than simulate a process restart on the same path. Transform cancellation/failure and filesystem read/write faults are exercised; deterministic encoding failures and internal DataStore-scope teardown remain unarranged.

## Product defaults and boundaries

<a id="mjpeg-set-01"></a>

Defaults describe open local streaming: Full source, crop off/ZERO, Scale 50%, quality 80, Auto frame rate/backend, IPv4 with Wifi/Ethernet and Private addresses, port 8080, protection off, a one-minute PIN block, 60-second network-loss timeout and Placeholder after Stop. The independently specified five-group snapshot also checks a null saved target, zero rotation, no mirroring, Color mode, access/display defaults and dormant behavior flags. Android-derived device titles are checked separately below.

Construct representative boundary values. Reject nonfinite/nonpositive scale and positive scale whose divided factor becomes zero, quality outside 0..100, selected target mode without a target, empty saved filter groups, ports outside 1024..65535, invalid PIN, block minutes below 1, invalid RGB color and timeout outside 10..1800. Accept valid boundary values without expanding every enum permutation.

## Saved-schema and recovery fixtures

<a id="mjpeg-set-02"></a>

- **Image serialization and retained choices.** Independent saved JSON fixtures exercise custom crop, target-size and frame-rate representations, plus representative source/rotation/mirror/color/backend choices; disabled crop and unselected target values are retained. Encode/decode these fixtures with explicit page values and compare the saved schema independently. Crop insets, target dimensions/content mode and ISO-duration frame rates round-trip; disabled/unselected saved values survive; older target fixtures missing contentMode use AspectFit; malformed values fail decoding rather than inventing a valid geometry.

<a id="mjpeg-set-03"></a>

- **Network and access serialization.** Distinct nonempty filter sets and valid permanent/module-start PIN settings are supplied. Round-trip values and decode fixtures with absent optional fields/unknown fields through the module storage boundary. Selected sets/port/PIN policy/limiter duration survive; supplied secrets serialize their text but toString redacts; storage accepts unknown fields and defaults optional fields. Invalid saved groups/PIN/port fall back through supported read/decode handling.

<a id="mjpeg-set-04"></a>

- **Device title and saved compatibility.** L1 Build manufacturer/model fixtures include repeated manufacturer, blank values and differing names; explicit empty title and old retainImageOnReconnect fixture are supplied. Construct page defaults; save and read page settings with changed later Build metadata. Trimmed title avoids repeated manufacturer and falls back to Android device; initial chosen title is encoded even when equal to its default and remains saved; empty title stays empty; old retainImageOnReconnect field preserves retainImageOnMediaFailure.

<a id="mjpeg-set-07"></a>

- **Read/decode defaults and destination priority.** The MJPEG preference file is missing, unreadable or contains malformed/module-invalid JSON or corrupt protobuf. Collect MjpegSettings.data using isolated fixtures. Supported read or decode failures yield defaults. A raw-present destination payload blocks legacy import even when malformed; source deletion and import otherwise follow the migration scenario below. A directory at the preferences path yields defaults without successful repair; corrupt protobuf is durably replaced and permits a later save. A direct identity update before collection receives defaults and durably replaces malformed/module-invalid JSON even when its result equals defaults. Unrelated failures are not claimed recoverable. The required web payload absent from old module JSON follows supported invalid-payload handling.

<a id="mjpeg-set-12"></a>

- **Destructive legacy import attempt.** Seed real old Preferences protobuf before constructing MjpegSettings. Observe converted settings and independently inspect saved JSON: only valid new-nondefault choices survive, required aggregate members remain, and synthetic title is absent. Present values equal to old defaults can survive when different from new defaults; absent, invalid, unsupported and new-default-equal values create no payload. Reject whole crop/target/filter compounds for any invalid participating member while unrelated settings survive. Cover oriented target bounds, absent/new stretch default, dormant choices, FPS ranges/overflow, masks excluding LinkLocal, family membership, historical-format keys and raw modern-key precedence. Existing valid, malformed or wrong-typed DATA wins. Source deletion occurs for successful/no-op/corrupt attempts and ordinary read failure; a real destination temporary-write fault establishes deletion before a failed commit and no import retry. Exact nonrecursive deletion failure preserves a primary read exception with the cleanup exception suppressed. Source absence prevents repeat import, while restoration/recreation can trigger another attempt. These tests do not simulate an uncatchable process termination or establish platform/OEM storage guarantees.

<a id="mjpeg-set-10"></a>

- **Dormant preferences persist.** Save and read nondefault hidePinOnCaptureStart, keepScreenAwake, stopOnScreenOff and notifySlowViewers. The values survive serialization and storage. These checks establish no PIN display, wakefulness, screen-off Stop or slow-viewer notification behavior; statistics are independent of notification preference.

## Atomic writes and admitted cancellation

<a id="mjpeg-set-05"></a>

- **Latest atomic transaction.** Two module settings edits affect different groups; a stale observed snapshot exists. Arrange sequential/concurrent updateData transforms, each against the latest saved value. Each admitted transform sees the latest stored model; accepted unrelated deltas are preserved together; each group update is atomic. Saving alone does not establish applied HTTP/capture state.

<a id="mjpeg-set-08"></a>

- **Write cancellation and failures.** Hold an admitted latest-value transform and cancel its caller; the write continues independently and its normal completion establishes saved state. Separately throw from the transform or block the temporary write file (`<preferences path>.tmp`) with a nonempty directory before constructing the store. The failed save propagates IOException and preserves the previously saved bytes/model. Transform or storage failure does not claim success. A cancelled wait alone does not establish whether work was admitted.

## Protection preparation

<a id="mjpeg-set-06"></a>

Stored access is open with no PIN, or protected with an already valid PIN. Enable protection via updateData; repeat unrelated edits. Enabled protection has a saved six-ASCII-digit PIN before it can be applied; an existing PIN is preserved by unrelated edits. Random generation is tested for validity/redaction, not guaranteed difference or entropy. Disabling protection retains the saved PIN. Enabling protection after an open activation does not by itself repeat NewOnModuleStart preparation; the [controller activation history](controller.md#mjpeg-ctl-06) checks application.

## Saved versus effective edit policy

<a id="mjpeg-set-09"></a>

Current and proposed Data differ in backend, port, access, live image/filter/page/behavior values. Apply EditPolicy.All/LiveOnly and allows to mixed, unchanged and restricted deltas. All accepts proposed values. LiveOnly preserves current backend/port/access while accepting live groups; unchanged restricted values do not reject a live transaction; saved proposed values can remain unapplied until successful cleanup.

## Secret redaction

<a id="mjpeg-set-11"></a>

Recognizable supplied secrets occur individually and inside saved access settings; the intentional serializer boundary is exercised. Stringify the secret and enclosing settings, then serialize the supplied value. Display/log-oriented strings contain `<redacted>` and no recognizable secret; serialization preserves supplied text at its intended boundary. Independently constructed equal PINs preserve applied credentials through [credential preservation](access.md#mjpeg-acc-03).
