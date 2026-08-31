# Capture and rendering

Capture owns the Android projection and GPU path that turns one available projection image into tight, top-down RGBA8888 bytes. It reports physical facts; the session decides whether those facts still belong to the requested configuration. The public pipeline is summarized in [Android capture and JPEG pipeline](../../docs/architecture.md#android-capture-and-jpeg-pipeline), while transform semantics are described for integrators in [How image settings combine](../../docs/usage.md#how-image-settings-combine). See the [internal architecture overview](../architecture/overview.md) for dependency direction and [Runtime flows](../architecture/runtime.md) for the session sequence around these physical operations.

## Responsibility boundary and owned resources

One `SessionCaptureOwner` is the sole physical root for a session's:

- adopted `MediaProjection`, projection callback, and sole `VirtualDisplay`;
- dedicated Capture `HandlerThread` and handler-confined command execution;
- current Target (`GL_TEXTURE_EXTERNAL_OES`, `SurfaceTexture`, and `Surface`) and source-candidate state;
- EGL display, ES2 context, pbuffer, renderer program, output texture, and framebuffer;
- current physical `CapturePlan`, active read, and exact result ports; and
- replacement candidates, retirement proofs, and late or nonreturning operation roots.

Capture does not own parameter revisions, geometry authority, production identity, pacing, JPEG encoding, publication, statistics, or terminal choice. It imports neither Encoding nor Storage. The session-facing Capture link carries exact request/result correlation and immutable facts; it does not reinterpret physical outcomes.

## Commands, plans, and identity

Session issues four operations: one-shot open, plan apply, direct read, and retirement. The command root is installed before posting to the Capture handler. A rejected post proves non-entry; an accepted post may enter immediately, later, or never. The owner therefore retains every accepted command until real entry and return. Retirement fences a queued command but never relabels an entered command as skipped.

An immutable `CapturePlan` fixes source dimensions and density, selected/cropped source rectangle, rotation, mirror, color mode, Target dimensions and mode, output dimensions, and checked RGBA byte count. Plan identity is physical correlation, not semantic currentness. A source identity similarly names one Target's conflated candidate; it is not a general "frame available" boolean.

At steady state the owner has at most one Target, one display, one renderer/output, and one read. During replacement it may additionally retain one unattached candidate and the old Target being retired. These bounds are central to the module's memory and ambiguity containment.

## Projection and VirtualDisplay lifecycle

Open registers [`MediaProjection.Callback`](https://developer.android.com/reference/android/media/projection/MediaProjection.Callback) on the control handler before it creates the virtual display, as required by Android. It then creates and binds EGL, constructs and listens to the Target, creates the renderer/output FBO, and finally invokes [`MediaProjection.createVirtualDisplay`](https://developer.android.com/reference/android/media/projection/MediaProjection#createVirtualDisplay(java.lang.String,int,int,int,int,android.view.Surface,android.hardware.display.VirtualDisplay.Callback,android.os.Handler)) with the source width, source height, density, Target surface, and `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`.

There is exactly one `createVirtualDisplay` call per projection. Reconfiguration uses [`VirtualDisplay.resize`](https://developer.android.com/reference/android/hardware/display/VirtualDisplay#resize(int,%20int,%20int)) and, when the Target changes, [`VirtualDisplay.setSurface`](https://developer.android.com/reference/android/hardware/display/VirtualDisplay#setSurface(android.view.Surface)); it never creates a second display. This preserves Android 14+ single-use projection consent semantics.

`onStop` fences duplicate projection callbacks, reports the exact projection-stopped fact, and requests Capture-lane retirement. It performs no EGL work and chooses no public terminal result. On API 34–37, positive captured-content resize callbacks supply authoritative physical source dimensions; visibility callbacks remain optional information. On older APIs, the session resolves source dimensions from [Metrics](metrics.md).

## Target and source arrival

A Target configures one external OES texture for linear, clamp-to-edge sampling, wraps it in a detached [`SurfaceTexture`](https://developer.android.com/reference/android/graphics/SurfaceTexture), applies the Target buffer size, creates a `Surface`, and installs its frame listener on the Capture handler. The listener only marks its `SourceCandidate` available and reports the exact Target identity. There is no frame-event queue or pending count.

Candidate state is linear: unavailable, available, reserved by one read, then available again only if settlement proves that the source was not consumed. Because listener dispatch and reads share the Capture handler, a callback dispatched before read settlement belongs to the reserved opportunity. Only a later callback can create a successor. `SurfaceTexture.updateTexImage()` consumes the producer's most recent image, so counting callbacks would imply a precision the platform does not provide.

The resolved `CapturePlan` selects Full or Downscaled Target mode under the [Image pipeline contract](../contracts/image-pipeline.md). Capture implements Downscaled mode only on API 32–37, where Android specifies uniform fit and centering into a smaller Surface. API 34+ provisional dimensions remain Full until projection resize establishes authority.

A healthy Full Target is reused while Full remains selected. A healthy downscaled Target is reused while still eligible and large enough on both rotation-aware axes; it is not rebuilt solely to shrink. The optimization changes producer resolution, never crop or transform semantics.

## EGL, GLES, and the pixel boundary

EGL is created and used only on the Capture thread. The owner selects an ES2 pbuffer configuration with at least 8-bit RGBA channels, creates an unshared context, binds the exact display/context/pbuffer tuple, and verifies that the tuple is current. Capability setup checks maximum texture size, both viewport dimensions, and fragment float precision through [`glGetShaderPrecisionFormat`](https://developer.android.com/reference/android/opengl/GLES20#glGetShaderPrecisionFormat(int,%20int,%20int[],%20int,%20int[],%20int)). The engine prefers high precision and selects its GLES 2.0 medium-precision compatibility path when the high-precision report is all zeroes.

The renderer realizes the [Image pipeline contract](../contracts/image-pipeline.md) with one OES shader pass into a reusable final-size RGBA framebuffer and direct [`glReadPixels`](https://developer.android.com/reference/android/opengl/GLES20#glReadPixels(int,%20int,%20int,%20int,%20int,%20int,%20java.nio.Buffer)) into the borrowed carrier. It owns the transform, dataspace, draw-state, color, and row-orientation mechanics without redefining their pixel semantics here. API 33+ queries [`SurfaceTexture` dataspace](https://developer.android.com/reference/android/graphics/SurfaceTexture#getDataSpace()) before drawing; API 24–32 do not access that symbol. The path has no `ImageReader`, PBO, staging frame, or full-frame copy.

Each coherent GLES operation group has one post-operation `glGetError` probe. Any contained group failure makes the entire GL graph unusable. An unusable graph cannot be read, reused, repaired, or have individual GL names deleted; the namespace is quarantined until exact EGL teardown can retire it. This avoids treating undefined GLES state as a recoverable local allocation failure.

For EGL display-connection teardown, the default display may be process-shared and is not Capture-owned, so Capture must not call `eglTerminate()` on it. Teardown is limited to unbinding the Capture thread and destroying the Capture-owned context and pbuffer/surface, then releasing that thread's EGL binding; display-connection lifetime remains with its external owner.

## Read bridge to Encoding

For fresh production, [Encoding](encoding.md) loans one exact direct writable range. The session binds that input to an opaque `CaptureReadReturnPort` and passes Capture only the view, exact plan/source identities, and return port. Capture cannot settle or reuse the carrier and sees no Encoding type.

On entry the read reserves the exact source candidate, writes only `[0, B)`, settles whether the source opportunity was consumed, releases its own command occupancy, and invokes the return port once. After the exact request is installed, either its real Capture return or definite proof that submission was rejected before entry lets the session claim and settle the matching read bridge and discard its exact Encoding input. Submission acceptance, timeout, cancellation, terminal state, and reference loss provide no such proof. Retirement may detach semantic correlation, but an accepted nonreturning read continues to root its carrier view and physical dependencies. A late return may settle only that bridge; it cannot restore session admission.

## Replacement, failures, and retirement

Target replacement constructs an unattached candidate first. A clean pre-attachment capacity denial rolls the candidate back and leaves the old graph usable. After attachment begins, listener removal, display resize, listener installation, and `setSurface` form an identity-sensitive transition. A throwing or nonreturning `setSurface` proves neither attachment nor detachment, so both Target roots remain retained and the owner is invalidated.

Apply and read failures distinguish `OperationLocal` from `OwnerInvalidated`. The former requires proof that the owner remained reusable or was fully rolled back. The latter is used whenever mutation, attachment, listener state, or GL integrity is ambiguous. Session consumes the explicit scope; it must not infer health from the problem category or whether a source was consumed. Cross-component classification and terminal priority are defined in [Failures and terminal semantics](../contracts/failures-and-terminal-semantics.md).

Capture uses this compact physical classifier and precedence at the boundary; the [checked-in verification audit](../testing.md#checked-in-verification-audit) maps focused `CAP-01`–`CAP-06` and `TGT-01`–`TGT-02` evidence:

| Physical evidence, in precedence order | Capture classification and consequence |
| --- | --- |
| Projection creation returns `null`, or a contained `SecurityException` occurs before a display is owned | `CaptureUnavailable` only when open rollback/teardown proves no cleanup failure or unsafe residue; otherwise `InternalFailure` invalidates and quarantines the owner. No display-ownership proof is fabricated. |
| A raw `OutOfMemoryError` escapes the boundary | Preserve the identical uncontained error; publish no typed classification or ordinary cleanup proof. |
| A coherent EGL allocation failure reports `EGL_BAD_ALLOC` | `ResourceExhausted` only when open rollback/teardown proves no cleanup failure or unsafe residue; otherwise `InternalFailure` invalidates and quarantines the owner. Incoherent EGL evidence is also `InternalFailure`. |
| A GLES group has no higher-priority contained command failure and its single postprobe reports `GL_OUT_OF_MEMORY` | `ResourceExhausted`, then make the complete GL graph unusable; do not reuse, recover, or delete names in that namespace. |
| FBO/precision evidence is invalid, malformed, or otherwise fails the setup invariant | `InternalFailure` and unusable/quarantined graph; it is not a capacity or fallback signal. |
| Graph mutation, attachment, cleanup, or integrity is ambiguous or unproved | `OwnerInvalidated`; retain unproved roots and do not reuse or fall back. |

A contained command failure outranks its postprobe; other non-OOM GLES errors and malformed group success map through the same unusable-graph rule. The scope (`OperationLocal` only with proved rollback or settlement, otherwise `OwnerInvalidated`) is independent of the problem name.

Retirement is monotone and dependency ordered: fence callbacks/listeners; detach and release the display; release Target Android objects and eligible GL names; unbind and destroy eligible EGL resources; unregister and stop the projection; then request `quitSafely()` once no accepted command remains unresolved. Each physical action is attempted at most once. After callback fencing, an `Exception` returned by callback unregistration does not suppress the independent, at-most-once [`MediaProjection.stop()`](https://developer.android.com/reference/android/media/projection/MediaProjection#stop()) attempt. Failed or ambiguous resources remain quarantined while independent safe suffix work may continue. Terminal publication does not wait for this sequence and is never a cleanup receipt. Accepted/nonreturning command liveness and late-settlement rules are shared with the other owners through [Concurrency and liveness](../contracts/concurrency-and-liveness.md).
