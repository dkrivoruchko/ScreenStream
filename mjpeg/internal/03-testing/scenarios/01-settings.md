# Settings and saved-compatibility scenarios

[Scenario index](../02-verification-contracts.md) · [Testing](../01-testing.md)

This page lists the test histories for MJPEG's saved settings. They cover which values are valid, how saved data decodes across versions, how the old settings file is imported, how concurrent saves combine, and how saved values differ from the values the running stream uses. Use it when you change a settings model, its serialization, the legacy import, or the edit and application rules.

The scenarios build on these owners:

| Behavior | Owner |
| --- | --- |
| Setting meanings, defaults and editor ranges | [Usage: Choosing settings](../../../docs/usage.md#choosing-settings) |
| When each change takes effect, and which settings wait until capture has stopped | [Architecture: Settings](../../../docs/architecture.md#settings) |
| Saved model, validation, transactions, PIN preparation, edit policy and read recovery | [Settings](../../01-module/04-settings.md) |
| The order in which the controller applies observed settings | [Coordination](../../01-module/03-coordination.md) |
| Crop, output size and frame-rate value types and their ranges | Capture Engine [Image and geometry](../../../../screen-capture-engine/docs/usage.md#image-and-geometry) and [Frame timing](../../../../screen-capture-engine/docs/usage.md#frame-timing) |

## Components and terms

- [`MjpegSettings`](../../../src/main/kotlin/io/screenstream/mjpeg/settings/MjpegSettings.kt) is the process-wide owner. It provides:
  - the `data` flow of saved snapshots
  - the `updateData` transaction
  - the runtime `EditPolicy`

  It stores each snapshot as JSON under the key `DATA` in the Preferences DataStore file `mjpeg.preferences_pb`. A shared JSON preferences store does this work and also recovers from read and decode failures ([mjpeg-set-07](#mjpeg-set-07)).
- [`MjpegSettingsModels.kt`](../../../src/main/kotlin/io/screenstream/mjpeg/settings/MjpegSettingsModels.kt) defines the five groups (image, network, access, web page and behavior), their validation and serializers, and `SecretValue`.
- [`MjpegSettingsMigration`](../../../src/main/kotlin/io/screenstream/mjpeg/settings/MjpegSettingsMigrations.kt) imports the legacy preferences file once.
- The settings screens submit field transforms. The controller turns observed snapshots into its effective settings.

The scenarios use these terms:

- **Saved snapshot:** one immutable `MjpegSettings.Data` value holding all five groups.
- **Transaction:** one `updateData` call. It transforms the latest saved snapshot and stores the result.
- **Restricted setup:** the JPEG encoder, the HTTP port, and the access settings except **Hide PIN when starting a stream**. All other fields are **live**.
- **Edit policy:** `All` while the controller is idle, otherwise `LiveOnly`. The controller also reports `LiveOnly` before it has accepted its first settings.
- **Effective settings:** the values the controller has accepted for the running instance. While a capture attempt is in progress, they keep the previous restricted values.

## Test layers and evidence

[Testing](../01-testing.md) defines the layers, the current verification status and the evidence rules. For settings they work as follows:

- **L0** covers pure model values and decoding of independently written JSON.
- **L1** covers DataStore files, Preferences protobuf bytes, the legacy import and values derived from Android `Build`.
- **Independent fixtures.** Parse saved JSON independently. Don't build expected payloads with the production serializer.
- **Separate observations.** Keep saving, observing and applying apart: a completed save proves none of the later steps.

## Values and saved schema

<a id="mjpeg-set-01"></a>

### Defaults and valid boundaries

**Setup.** Construct the defaults and compare all five groups with independently written expected values.

**Steps and expected.** Construct each value at its accepted limits and just outside them:

| Value | Accepted | Rejected |
| --- | --- | --- |
| JPEG quality | 0 and 100 | −1 and 101 |
| Saved HTTP port | 1024 and 65535 | 1023 and 65536 |
| Address-loss timeout | 10 s and 1800 s | 9 s and 1801 s |
| Block duration | 1 minute | 0 |
| PIN | Six ASCII digits, leading zeros kept | Five or seven digits, letters, non-ASCII digits |
| Background | `#RRGGBB` in either letter case | `#RGB`, `#AARRGGBB`, a value without `#` |
| Scale percent | Any finite positive value that stays positive after division by 100 | 0, negative values, NaN, infinities, and tiny values such as `Double.MIN_VALUE` that become zero |

The model also rejects these values:

- an empty filter group
- a selected target size that is missing

Some value types belong to the Engine. Decode saved JSON that holds each of these, and check that decoding fails:

- a negative crop inset
- a target size with a zero or negative dimension
- `MaxFps` outside 1–120
- a sampling interval outside 1,000–3,600,000 ms

Some values that the native editors refuse are still valid saved values:

- scale above 200%
- target sizes above 8192 px
- blocks longer than 60 minutes
- stream names longer than 64 characters

Keep the defaults derived from Android, such as the device title, apart from pure values. Geometry that is valid as a value but impractical on a device is an Engine runtime case, not a validation failure.

<a id="mjpeg-set-02"></a>

### Image schema and dormant choices

**Setup.** Write independent JSON for the source region, all four crop insets, scale and target selection, target content mode, rotation, mirror, color, quality, timing and JPEG encoder.

**Steps.** Round-trip each field, and also round-trip disabled crop and an unselected target. Decode a target saved before `contentMode` existed, and a payload that omits newer optional fields. Decode the `Auto`, `MaxFps` and ISO-duration sampling forms separately. Decode invalid crop, target and timing forms.

**Expected.**

- Dormant values survive saving, even though capture ignores them while they are inactive.
- An older target without `contentMode` decodes as AspectFit, and omitted optional fields get their declared defaults.
- Invalid crop, target and timing forms make decoding of the whole payload fail. The model never repairs them into made-up values. What the store does next is [mjpeg-set-07](#mjpeg-set-07).

<a id="mjpeg-set-03"></a>

### Network and access schema

**Setup.** Write independent nondefault fixtures for the filter and the access group.

**Steps.** Add unknown fields, leave out optional fields, and supply invalid members, such as an empty family set, an unknown enum value, port 80, a five-digit PIN or a block duration of 0.

**Expected.**

- Supported fixtures keep every choice. Unknown fields are ignored, and missing optional fields get defaults.
- A payload with an invalid member fails to decode as a whole. The store's recovery then removes or replaces the entire payload ([mjpeg-set-07](#mjpeg-set-07)). Valid choices in the other groups are lost too, not just the invalid member's group.
- The saved PIN is stored as its plain value on purpose. Its redaction in text output is a separate boundary ([mjpeg-set-11](#mjpeg-set-11)).

<a id="mjpeg-set-04"></a>

### Device title and old web fields

**Setup.** Supply manufacturer and model names that repeat, contain extra spaces or are blank. Supply an explicit empty title, and an old web-settings fixture with fields that no longer exist. Use L1 for the Android metadata.

**Expected.**

- The default title is built from the trimmed names:
  - when the model already starts with the manufacturer, ignoring case, the title is the model alone
  - otherwise it is the nonblank names joined with one space
  - when both names are blank, it is `Android device`
- Once a normal write has stored the title, later changes to the device metadata don't change it. An explicitly empty title stays empty.
- Until a write stores a title, no title is saved. Each new process computes the default again from current device metadata. An identity update on a missing payload writes nothing, so it doesn't store the title either.
- Removed fields in old web settings change no other values.

## Storage recovery and legacy import

<a id="mjpeg-set-07"></a>

### Read/decode recovery and destination priority

**Setup.** Prepare each of these destination states:

- no payload
- malformed JSON
- JSON with invalid values
- a `DATA` value of the wrong type
- a corrupt Preferences protobuf
- an unreadable file path, such as a directory

Separately, add a destination key named `DATA` that is malformed, invalid or of the wrong type, next to a legacy source file.

**Expected.**

- **No payload.** Observation emits the defaults and writes nothing.
- **Malformed, invalid or wrongly typed payload.** Decoding fails, and observation makes one repair attempt that removes the payload.
  - The whole payload is removed, so every group returns to its defaults.
  - After a successful repair, observation reads the file again and emits the defaults.
  - The repair attempt is spent until a value decodes successfully again.
- **Corrupt protobuf.** The file is replaced with empty preferences, and observation emits the defaults.
- **Unreadable path.** The repair write fails as well. Observation emits the defaults while the file stays unreadable. Observation stops reading until a normally completed update or a new collection starts it again.
- **Destination priority.** A destination key named `DATA` wins over the legacy import, whatever its type or content. Normal recovery handles it later.

**Steps.** Run each separately:

1. With an invalid payload, run an identity update. The store writes the encoded default input, even though the transformed model equals the defaults, and that replaces the invalid payload. With no payload at all, the same identity update writes nothing.
2. Make a repair fail, then let a normal update complete. Check that the update restarts the observation that was emitting defaults.

**Evidence boundary.** Keep these observations apart: defaults emitted, repair attempted, repair made durable, and a write failure propagated.

<a id="mjpeg-set-12"></a>

### One destructive legacy import attempt

**Setup.** Seed real legacy Preferences bytes in `MJPEG_settings.preferences_pb`. Inspect both the converted settings and the destination JSON independently.

**Steps and expected for values.**

- **Scalars.** Valid scalars convert. A missing or wrongly typed scalar falls back to its own default, independently of the others.
- **Crop.** The crop keys (`IMAGE_CROP` and the four `IMAGE_CROP_*` insets) form one compound. A wrong type or a negative inset rejects the whole compound.
- **Target size.** The target keys (`RESOLUTION_WIDTH`, `RESOLUTION_HEIGHT` and `RESOLUTION_STRETCH`) form one compound. Both dimensions must be positive, and a wrongly typed stretch rejects it. The dimensions swap for 90° and 270° rotation.
- **PIN.** PINs with leading zeros keep them. Unsupported legacy fields are dropped.
- **Frame rate.** `MAX_FPS` converts as follows:
  - 0 becomes Auto
  - 1–120 becomes MaxFps
  - negative values of magnitude 1–3600 become a sampling interval of that many seconds
  - anything else, including `Int.MIN_VALUE`, falls back to the default, with conversion that can't overflow

**Steps and expected for the address filter.** The filter is one compound. Rejecting any part restores the whole default filter.

- **Interface mask** (`INTERFACE_FILTER`):
  - 0 selects all five interface types
  - the known bits select Wi-Fi, Mobile, Ethernet and VPN
  - a value outside 0–15 rejects the filter
- **Address mask** (`ADDRESS_FILTER`):
  - 0 selects Private, NonPrivate and Loopback
  - the known bits select those same categories, never LinkLocal
  - a value outside 0–7 rejects the filter
- **Family flags** (`ENABLE_IPV4`, `ENABLE_IPV6`) adjust the IPv4-only default. A wrong type, or an empty set of families, rejects the filter.
- **Legacy booleans.** When a modern mask key is present, the older booleans for it are ignored, even if the mask is invalid. Otherwise:
  - `USE_WIFI_ONLY` chooses between Wi-Fi only and all types
  - `LOCAL_HOST_ONLY` selects Loopback only
  - otherwise `ENABLE_LOCAL_HOST` decides whether Loopback joins Private and NonPrivate
  - a wrongly typed boolean rejects the filter

**Expected payload.**

- An existing destination `DATA` key wins as a whole ([mjpeg-set-07](#mjpeg-set-07)).
- An import where everything equals the defaults leaves no payload.
- Otherwise the payload leaves out default values and the made-up device title, but keeps the required `web` object, which is `{}` when nothing in it differs.

**Steps and expected for cleanup.** Run these cases: a no-op import, a corrupt source, a source read failure, and a failure to delete the source. Separately, force the destination's temporary write or commit to fail.

- The exact source file is deleted in the `finally` of `migrate`, before DataStore commits, even when conversion or the commit fails.
- A corrupt source skips the import with a diagnostic that contains no secrets.
- A deletion failure propagates. If another failure is already propagating, the deletion failure is attached to it as suppressed. The cleanup hook writes no marker.
- A failed commit after a successful deletion loses that source's values.
- A failed deletion, or a source file that reappears, allows another attempt.

**Evidence boundary.** Record these outcomes as they are. They don't guarantee a retry or crash-safe atomicity.

## Transactions and application

<a id="mjpeg-set-05"></a>

### Latest atomic transaction

**Setup.** Observe a snapshot, then make it stale by saving another change.

**Steps.** Run two independent edits based on the stale snapshot, first one after the other and then concurrently.

**Expected.**

- Each transaction receives the latest saved snapshot, not the stale one, so unrelated accepted changes survive together.
- Transactions run in the order they entered the store.
- A completed save doesn't mean capture or HTTP has applied the value.

<a id="mjpeg-set-08"></a>

### Admitted cancellation and write failure

**Setup.** Hold a transaction inside storage. Queue a second transaction behind it.

**Steps.** Cancel both callers, then release the held work. Separately, make a transform throw, and make a storage write fail.

**Expected.**

- Cancelling a caller doesn't abandon its persistence, even for a caller that was still queued. Both transforms run in order and are stored.
- A cancelled caller's own return says nothing about whether its write committed. Inspect storage instead.
- A failing transform or a failing write keeps the previous saved snapshot and propagates the failure to the caller.

<a id="mjpeg-set-06"></a>

### Protection preparation

**Setup and steps.**

1. Turn on protection while no PIN is saved. Run several unrelated updates. Turn protection off and on again while a PIN exists.
2. Separately, seed a saved snapshot, or a migrated one, that has protection on and no PIN. Then start the controller's settings observation.

**Expected.**

- Any transaction that leaves protection on without a PIN stores six ASCII digits in that same transaction.
- Unrelated edits keep the PIN, and turning protection off keeps it.
- Turning protection back on with a saved PIN keeps that PIN.
- When the controller starts observing, its activation transaction runs before the first snapshot is applied, so HTTP never receives protection without a PIN.

**Evidence boundary.** Check the PIN's validity and that it authorizes. Don't expect each random PIN to differ from the last. [mjpeg-ctl-06](02-controller.md#mjpeg-ctl-06) covers the once-per-activation policy for **New PIN on open**.

<a id="mjpeg-set-09"></a>

### Edit policy

**Setup.** Prepare proposals for the access, connection and image groups. Some change only live fields. Some change restricted fields. Some carry restricted fields unchanged alongside live changes.

**Steps.** Submit each proposal under `All` and under `LiveOnly`.

**Expected in the transaction.**

- Each settings screen checks its proposal inside the transaction, against the latest saved snapshot and the edit policy it last received.
- Under `All`, every valid proposal is saved.
- Under `LiveOnly`, a proposal that changes any restricted field leaves the saved snapshot unchanged as a whole, without an error.
- Restricted fields that are unchanged don't block the live part of a proposal. **Hide PIN when starting a stream** is live.

**Expected in the controller.**

- While an attempt is in progress, the effective settings take the observed live values and keep the previous restricted values. Saved and effective snapshots therefore differ until capture cleanup succeeds, and then the latest observed values apply.
- A restricted edit accepted under `All` just before Start can be saved after Start. The busy attempt keeps its effective setup ([mjpeg-ctl-05](02-controller.md#mjpeg-ctl-05)).

## Presentation and secrets

<a id="mjpeg-set-10"></a>

### Behavior persistence versus presentation

**Setup.** Save nondefault values for these preferences:

- **Hide PIN when starting a stream**
- **Keep screen awake**
- **Stop when screen turns off**
- **Warn about skipped frames**

**Steps.** Reload the saved settings, then check the native PIN row separately.

**Expected.**

- The values survive storage. That alone doesn't prove their runtime effects.
- The PIN row shows the applied PIN, not the saved one, and only while protection is applied.
- Copying the PIN marks the clip as sensitive.
- The row's reveal resets when the applied PIN changes. It also resets when the hide-during-capture condition changes in either direction. That condition holds while the preference is on and an attempt is pending cleanup.
- Revealing the PIN in the Access settings editor is independent of the row.

**Related.** [mjpeg-ctl-14](02-controller.md#mjpeg-ctl-14) covers the effects of stopping when the screen turns off. Keep-awake and warning effects belong to [mjpeg-ctl-17](02-controller.md#mjpeg-ctl-17).

<a id="mjpeg-set-11"></a>

### Secret redaction

**Setup.** Use recognizable secret values, on their own and inside enclosing settings.

**Steps.** Convert them to strings, and serialize them.

**Expected.**

- Text representations redact the secrets, including those of the enclosing settings objects.
- Intentional persistence keeps the values as supplied.
- Equality follows the secret value.
- Actual authorization establishes credential behavior ([mjpeg-acc-01](06-access.md#mjpeg-acc-01)). Hash codes and random inequality don't.
