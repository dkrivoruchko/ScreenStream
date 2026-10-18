[Scenario index](../verification-contracts.md) · [Running tests](../testing.md)

# Capture adapter and independent cleanup

Behavior: [maintained contract](../../01-lifecycle/module-lifecycle.md). Use L1 for consent/cancellation, factory/ownership failures, parameter/state mapping and independent cleanup. A projection shadow supplies a stop input; it does not start graphics, JNI or a virtual display. Controlled Engine admission after ownership transfer does not establish real Android revocation. Successful real projection, foreground and JPEG output require L2.

## Consent, single use and early cancellation

<a id="mjpeg-cap-01"></a>

- **Single-use inert adapter and consent refusal.** Read an adapter before running capture, decline consent, then attempt a second run. Collection starts no work; initial state waits for permission. Declined consent ends without projection/Engine capture, and cleanup succeeds if rollback succeeds. A second run is rejected; the first run call returning does not confirm startup or JPEG output.

<a id="mjpeg-cap-02"></a>

- **Stop/cancellation before coroutine entry or during consent.** Hold capture entry or consent, request Stop/cancellation, then let the attempt settle. Pre-entry cancellation acquires no capture resources and reports successful cleanup. An entered attempt runs rollback independently. The first accepted Stop cause persists; awaiting cleanup neither starts nor stops capture.

## Startup order and failures

<a id="mjpeg-cap-03"></a>

- **Foreground before projection and foreground failure.** After consent succeeds, complete or fail foreground promotion. Projection creation follows successful promotion only. Promotion failure retains Startup.foreground and rolls back without projection handoff. Foreground release tolerates rollback before promotion, including consent failure.

<a id="mjpeg-cap-04"></a>

- **Projection/factory/handoff failures.** After consent and foreground succeed, fail projection creation, factory construction or handoff separately, then await cleanup. Creation failure leaves no adopted session; factory failure leaves the raw projection with the adapter for Stop. Successful factory construction transfers ownership, so cleanup stops the session. Handoff failure remains capture failure and guard cleanup is still attempted.

Use controlled Engine inputs for initial start failure and lawful terminal registration rejection after ownership transfer, before registration/start. Registration rejection never starts capture and settles owned media/foreground/guard cleanup without offering consent. Registration/start failures retain Engine.registration/Engine.start respectively and settle applicable consumer obligations. These controlled checks do not establish Android projection revocation; late projection-creation/Stop races need separate barriers.

## Initial and latest image parameters

<a id="mjpeg-cap-05"></a>

- **Initial image parameter mapping.** Start the controlled Engine input with enabled/disabled crop, scale/target mode, rotation, mirror, color, frame-rate and quality settings. Selected region is forwarded; disabled crop uses ZERO while preserving saved insets; scale divides percent by 100; selected target is used; remaining live values are forwarded; backend config is fixed for the attempt. Engine geometry/image correctness remains outside scope.

<a id="mjpeg-cap-06"></a>

- **Latest updates through consent/startup/running.** Change live image settings through consent, startup and running phases, and propose a backend edit. The latest parameters persist until eligible application; equal submitted values create no new effect. Backend remains fixed for the attempt. After accepted Stop, later settings are ignored; closed update admission is tolerated without reviving capture.

## Confirmed streaming and engine state

<a id="mjpeg-cap-07"></a>

Supply NotStarted, Starting, Active, Reconfiguring, Suspended with each allowed problem, Stopped and Failed through controlled Engine inputs. Compare availability with startup still held, after startup succeeds and after a terminal state. Availability maps to Starting/Active/Reconfiguring/Suspended; isStreaming waits for startup success and stays true through recoverable suspension until Stop/failure. InvalidRequest/CaptureUnavailable/ResourceExhausted map exactly; engine failure retains Engine.state and ends streaming. Confirmed startup can report isStreaming with no JPEG yet; image availability remains a separate delivery fact.

## Borrowed JPEG output

<a id="mjpeg-cap-08"></a>

While the Engine input is Active, accept and synchronously copy a borrowed frame, ignore it, or throw from the output callback. Exactly byteCount bytes are complete in caller-owned storage and remain owned after the borrowed storage is reused. Ignoring an offer is allowed. Output failure retains Output.frame, requests error shutdown and ends ongoing capture. The copier stays within its callback/thread.

A truthful six-byte borrowed input rejects a five-byte destination before writing; the unchanged destination is not published as an owned frame, and Output.frame retains the range failure through successful cleanup. Separately, count-access failure and short/excessive copy results are defensive broken-contract inputs; they do not establish that the real Engine produces them. A frame before start returns also does not establish confirmed streaming.

## Cleanup obligations and retained failures

<a id="mjpeg-cap-09"></a>

- **Retained capture versus cleanup failures.** Retain a capture failure, then fail media, foreground or guard cleanup separately or together. Complete the remaining obligations and read final state. First capture failure and first cleanup failure remain separately observable; cleanup failure/false outranks normal terminal engine state and keeps Failed. Successful cleanup may coexist with retained capture failure and does not erase it.

<a id="mjpeg-cap-10"></a>

- **Media and exact consumer settlement.** Hold consumer unregister completion and media stop independently. Stop capture, release each obligation in either order and cancel an awaitCleanup waiter. Media stop and exact registration completion are separate receipts. Foreground release follows media outcome; a created session whose media stop fails does not gain confirmed foreground release. Cleanup waits for exact consumer completion; cancelling a waiter cannot cancel cleanup.

Ordinary Engine unregister waits for the exact callback to return and does not naturally fail from session Stop/failure. Cleanup.consumer is a defensive failure path, not an ordinary Engine outcome.

<a id="mjpeg-cap-11"></a>

- **Guard/foreground/unowned rollback failures.** Startup stopped before session creation or owned media cleanup is complete; leaveForeground/finishProjectionCreation can throw. Run each failure and competing rollback history. Applicable remaining cleanup obligations still run. Raw-projection rollback failure before session creation still attempts leaveForeground; owned engine.stop failure skips foreground removal. Consumer unregister and projection-creation guard release are independently attempted. Cleanup.foreground/media/projectionCreation are retained distinctly as first cleanup failure; final success is false if any required operation fails. Guard release does not target another attempt.

## Consent reuse eligibility

<a id="mjpeg-cap-12"></a>

Successful startup, requested engine Stop, first Stop cause User/Shutdown, no capture failure and all cleanup success are contrasted with System/Error/startup failure/revocation/cleanup failure. Complete adapter cleanup. Only the fully eligible grant is offered for reuse; save failure becomes Cleanup.consentReuse and false cleanup. API eligibility and global consent coordinator behavior remain owned by streaming-core.

## Native Service adapter

<a id="mjpeg-cap-13"></a>

Call the real MJPEG Service promotion/release boundary in L1 with simulated platform outcomes and request a capture-error notification. Foreground notification identifies the capture attempt and media-projection type. Rejected/failed promotion and unconfirmed/failed release throw rather than claim success. Error notification uses the MJPEG capture-error resource and ID 410; normal foreground ID is 400.

Qualify the normal L1 notification/attempt Stop/removal history at the 24/26/29/34/37 platform boundaries. Before 29, ServiceCompat uses the untyped foreground call, so the media-projection type getter is not an available oracle; 29–33 and 34–37 use their typed paths. Failure/lifecycle histories can use a representative SDK because their MJPEG obligation contract is unchanged. Simulated completion does not prove system permission checks or visible notifications. Real capture needs the [platform prerequisites](https://developer.android.com/develop/background-work/services/fgs/launch) and [per-session API-34 consent/callback rules](https://developer.android.com/about/versions/14/behavior-changes-14#user-consent-required); shared consent reuse is limited by its owner to API 24–33. The [integration smoke](integration.md#mjpeg-cfg-04) defines the supported-platform differences to qualify.

## Defensive observation boundaries

<a id="mjpeg-cap-14"></a>

The real Engine exposes StateFlows that do not naturally throw observation exceptions. With a faithful public stimulus, state-observation failure should retain Engine.stateObservation and end capture; statistics-observation failure should be logged without changing capture success/state or inventing statistics output. A fabricated throwing Flow or global logger mutation would not establish this real boundary behavior. Logging presence/text is not the oracle.
