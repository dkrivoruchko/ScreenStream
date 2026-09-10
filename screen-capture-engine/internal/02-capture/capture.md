# Capture

Capture turns an available projection image into tight, top-down RGBA8888 bytes. It reports physical facts; Session decides whether they belong to the requested configuration. Read the public [Usage guide](../../docs/usage.md#image-and-geometry) for transform choices and [Architecture](../../docs/architecture.md) for the session model. This page extends that foundation with physical commands, graphics validity, failure scope, and retirement proofs. [Session](../01-session/session.md) owns lifecycle decisions; [Image pipeline](image-pipeline.md) owns pixel mathematics.

## Contents

- [Responsibility boundary and owned resources](#responsibility-boundary-and-owned-resources)
- [Commands, plans, and identity](#commands-plans-and-identity)
- [Projection and VirtualDisplay lifecycle](#projection-and-virtualdisplay-lifecycle)
- [Target and source arrival](#target-and-source-arrival)
- [Rendering and readback](#rendering-and-readback)
- [Read bridge to Encoding](#read-bridge-to-encoding)
- [Target replacement](#target-replacement)
- [GLES validity and failure scope](#gles-validity-and-failure-scope)
- [EGL retirement proofs](#egl-retirement-proofs)
- [Owner retirement](#owner-retirement)
- [Implementation and verification](#implementation-and-verification)

## Responsibility boundary and owned resources

After adoption, one `SessionCaptureOwner` is the sole physical root for a session's:

- adopted `MediaProjection`, projection callback, and sole `VirtualDisplay`;
- dedicated Capture `HandlerThread` and handler-confined command execution;
- current Target (`GL_TEXTURE_EXTERNAL_OES`, `SurfaceTexture`, and `Surface`) and source-candidate state;
- EGL display, ES2 context, pbuffer, renderer program, output texture, and framebuffer;
- current physical `CapturePlan`, active read, and exact result ports; and
- replacement candidates, retirement proofs, and late or nonreturning operation roots.

Capture does not own parameter revisions, geometry authority, production identity, pacing, JPEG encoding, publication, statistics, or terminal choice. It imports neither Encoding nor Storage. The session-facing Capture link carries exact request/result correlation and immutable facts; it does not reinterpret physical outcomes.

## Commands, plans, and identity

Session issues four operations: one-shot open, plan apply, direct read, and retirement. The command root is installed before posting to the Capture handler. A rejected post proves non-entry; an accepted post may enter immediately, later, or never. The owner therefore retains every accepted command until real entry and return. Retirement fences a queued command but never relabels an entered command as skipped.

An immutable `CapturePlan` fixes source dimensions and density, the resolved source rectangle, rotation, mirror, color mode, Target dimensions and mode, output dimensions, and checked RGBA byte count. Capture-plan equivalence compares that complete physical configuration. Different raw source-region or crop requests therefore do not force plan Apply when they resolve to the same rectangle and every other physical fact matches; those raw requests remain Session metadata rather than Capture configuration. Plan identity is physical correlation, not semantic currentness. A source identity similarly names one Target's conflated candidate; it is not a general "frame available" boolean.

At steady state the owner has at most one Target, one display, one renderer/output, and one read. During replacement it may additionally retain one unattached candidate and the old Target being retired, keeping memory and ownership ambiguity bounded.

## Projection and VirtualDisplay lifecycle

Capture adopts a projection already owned by the Session following successful synchronous factory return. This is an internal handoff; later `start()` takes no projection. Ownership before Capture adoption, including stopping a created Session whose startup never enters, belongs to the [runtime lifecycle](../01-session/session.md).

Open registers [`MediaProjection.Callback`](https://developer.android.com/reference/android/media/projection/MediaProjection.Callback) on the control handler before it creates the virtual display, as required by Android. It then creates and binds EGL, constructs and listens to the Target, creates the renderer/output FBO, and finally invokes [`MediaProjection.createVirtualDisplay`](https://developer.android.com/reference/android/media/projection/MediaProjection#createVirtualDisplay(java.lang.String,int,int,int,int,android.view.Surface,android.hardware.display.VirtualDisplay.Callback,android.os.Handler)) with the source width, source height, density, Target surface, and `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`.

Open attempts `createVirtualDisplay` at most once per projection; rejected or failed Open never re-arms it. Reconfiguration uses [`VirtualDisplay.resize`](https://developer.android.com/reference/android/hardware/display/VirtualDisplay#resize(int,%20int,%20int)) and, when the Target changes, [`VirtualDisplay.setSurface`](https://developer.android.com/reference/android/hardware/display/VirtualDisplay#setSurface(android.view.Surface)); it never creates a second display. This preserves Android 14+ single-use projection consent semantics.

`onStop` fences duplicate projection callbacks, reports the exact projection-stopped fact, and requests Capture-lane retirement. It performs no EGL work and chooses no public terminal result. On API 34+, positive captured-content resize callbacks supply authoritative physical source dimensions; visibility callbacks remain optional information. On older APIs, the session resolves source dimensions from [Metrics](metrics.md).

Before the first authoritative API 34+ resize, Open uses only positive Metrics source dimensions and density. It creates a Full Target over the whole source with neutral rotation, mirror, and crop, plus a bounded `1 x 1` renderer output. The resolved plan is explicitly provisional: its internal preparation parameters cannot become public effective metadata, and after the one Open the session waits without definitive Apply or Encoding preparation. The first authoritative resize resolves and validates the latest requested parameters, then follows the ordinary atomic revision, Apply, Encoding, and first-Active sequence. Failure of the neutral physical setup remains an Open failure; authoritative invalidity remains a startup failure.

## Target and source arrival

A Target owns one external OES texture, a [`SurfaceTexture`](https://developer.android.com/reference/android/graphics/SurfaceTexture), and its `Surface`. For detached construction, [`attachToGLContext`](https://developer.android.com/reference/android/graphics/SurfaceTexture#attachToGLContext(int)) must run with the Capture context current and an unused texture name; attachment creates the texture object, which can then be configured for linear, clamp-to-edge sampling. Attachment must precede `updateTexImage()`. Target setup also applies the buffer size and installs the frame listener on the Capture handler. The listener only marks its `SourceAvailability` available and reports the exact Target identity. There is no frame-event queue or pending count.

Candidate state is linear: unavailable, available, reserved by one read, then available again only if settlement proves that the source was not consumed. Because listener dispatch and reads share the Capture handler, a callback dispatched before read settlement belongs to the reserved opportunity. Only a later callback can create a successor. `SurfaceTexture.updateTexImage()` consumes the producer's most recent image, so counting callbacks would imply a precision the platform does not provide.

The resolved definitive `CapturePlan` selects Full or Downscaled Target mode under the [Image pipeline contract](image-pipeline.md). Capture implements Downscaled mode on API 32+, where Android specifies uniform fit and centering into a smaller Surface. The provisional API 34+ preparation plan is always Full.

A healthy Full Target is reused while Full remains selected and source dimensions are unchanged. Source-size changes replace the Target. This replacement rule also applies to Downscaled Targets. With unchanged source dimensions, a healthy Downscaled Target may be reused without shrinking only while it satisfies the [image contract's compatibility invariant](image-pipeline.md#target-selection-and-reuse): sufficient size on both rotation-aware axes, matching source aspect, and the same source-to-Target coordinate meaning. Size alone does not establish compatibility after an aspect-changing source resize. Minimum size governs creation of a new Target; reuse need not choose that minimum again. The optimization changes producer resolution, never crop or transform semantics.

## Rendering and readback

EGL is created and used only on the Capture thread. The owner selects an ES2 pbuffer configuration with at least 8-bit RGBA channels, creates an unshared context, binds the exact display/context/pbuffer tuple, and verifies that the tuple is current. Capability setup checks maximum texture size, both viewport dimensions, and fragment float precision through [`glGetShaderPrecisionFormat`](https://developer.android.com/reference/android/opengl/GLES20#glGetShaderPrecisionFormat(int,%20int,%20int[],%20int,%20int[],%20int)). The engine prefers high precision only when both reported range entries and the precision are positive; all three zeroes select its GLES 2.0 medium-precision compatibility path. A mixed or malformed report is an invariant failure. Target and output dimensions must fit the maximum texture size and both viewport limits. After initial binding, operation entry checks the recorded healthy/current state and exact owning thread; it does not re-query the full EGL tuple before every group.

The renderer realizes the [Image pipeline contract](image-pipeline.md) with one OES shader pass into a reusable final-size RGBA framebuffer and direct [`glReadPixels`](https://developer.android.com/reference/android/opengl/GLES20#glReadPixels(int,%20int,%20int,%20int,%20int,%20int,%20java.nio.Buffer)) into the borrowed carrier. Private plan parameters keep the inverse logical transform, color mode, and all four retained-edge bounds coherent on Open and every Apply, including an Apply that keeps the same output size. The vertex shader emits the transformed logical image coordinate; each fragment clamps that coordinate to retained centers, performs row inversion, applies the acquired OES matrix once, and samples once. Full and Downscaled whole-image plans use bounds `0..1`. API 33+ queries [`SurfaceTexture` dataspace](https://developer.android.com/reference/android/graphics/SurfaceTexture#getDataSpace()) before drawing; API 24–32 do not access that symbol. The path adds four scalar uploads per read and bounded fragment arithmetic, with no additional sample, pass, frame allocation, `ImageReader`, PBO, staging frame, or full-frame copy.

## Read bridge to Encoding

For fresh production, [Encoding](../03-output/encoding.md) loans one exact direct writable range. At read entry the carrier must be direct and writable, with position `0` and both limit and capacity exactly `B = 4 * outputWidth * outputHeight`; the plan object must be the installed plan and the source identity must name this owner and Target. The session binds that input to an opaque `CaptureReadReturnPort` and passes Capture only the view, exact plan/source identities, and return port. Capture cannot settle or reuse the carrier and sees no Encoding type.

On entry the read reserves the exact source candidate, writes only `[0, B)`, settles whether the source opportunity was consumed, releases its own command occupancy, and invokes the return port once. Before calling `updateTexImage()`, the renderer records that the source may have been consumed even if that call throws. A successful draw brackets only `glReadPixels` with the monotonic clock, validates the exact carrier shape again, and reports a nonnegative readback duration; this duration excludes the preceding transform and draw work. The [Production read bridge](../01-session/production.md#read-bridge-and-production-progression) owns submission, discard authority, and Encoding settlement. A real Capture return or definite rejected entry supplies physical completion evidence; acceptance, timeout, cancellation, terminal state, and reference loss do not. Retirement may detach semantic correlation, but an accepted nonreturning read continues to root its carrier view and physical dependencies. A late return may settle only that bridge; it cannot restore session admission.

## Target replacement

Target replacement constructs an unattached candidate first. A clean pre-attachment capacity denial rolls the candidate back and leaves the old graph usable. After candidate setup, renderer Apply, old-listener removal, display resize, new-listener installation, and `setSurface` form an identity-sensitive transition. Only a returned proof naming the exact new Surface permits promotion of the candidate; release of the old Target requires both its listener-removal proof and the exact replacement receipt. The plan is installed only after that retirement succeeds. A throwing or nonreturning `setSurface` proves neither attachment nor detachment, so both Target roots remain retained and the owner is invalidated.

## GLES validity and failure scope

A coherent GLES operation group performs one post-operation `glGetError` probe after normal command return or a contained command `Exception`. Any contained group failure makes the complete GL graph unusable: no read, reuse, repair, or individual GL-name deletion is then safe. Exact EGL namespace retirement is the remaining route. This conservative engine policy is stricter than [GLES 2.0, section 2.5](https://registry.khronos.org/OpenGL/specs/es/2.0/es_full_spec_2.0.pdf): `GL_OUT_OF_MEMORY` can leave undefined results, while ordinary GL errors cause the offending command to be ignored.

An escaping non-`Exception` such as raw `OutOfMemoryError` interrupts the group before its postprobe and ordinary result settlement. It is not converted into typed failure or proof of cleanup. A contained command failure outranks postprobe evidence, including `GL_OUT_OF_MEMORY`; a postprobe exception outranks the returned GL error. Exact Display-P3 metadata is recorded as a pending `UnsupportedColorSpace` read failure before drawing. It becomes operation-local only after the enclosing group proves healthy; a failing group takes precedence.

Apply and read failures distinguish `OperationLocal` from `OwnerInvalidated`. The former requires proof that the owner remained reusable or was fully rolled back. The latter is used whenever mutation, attachment, listener state, or GL integrity is ambiguous. Session consumes the explicit scope; it must not infer health from the problem category or whether a source was consumed. Cross-component classification and terminal priority are defined in [Session failure policy](../01-session/session.md#stable-problem-mapping).

Capture uses this compact physical classifier and precedence at the boundary; the [verification contracts](../04-testing/verification-contracts.md) map focused `CAP-01`–`CAP-06` and `TGT-01`–`TGT-02` evidence:

| Physical evidence | Capture classification and consequence |
| --- | --- |
| VirtualDisplay creation returns `null`, or a contained `SecurityException` occurs before a display is owned | `CaptureUnavailable` only when open rollback/teardown proves no cleanup failure or unsafe residue; otherwise `InternalFailure` invalidates and quarantines the owner. No display-ownership proof is fabricated. |
| A raw `OutOfMemoryError` escapes the boundary | Preserve the identical uncontained error; publish no typed classification or ordinary cleanup proof. |
| A coherent EGL allocation failure reports `EGL_BAD_ALLOC` | `ResourceExhausted` only when open rollback/teardown proves no cleanup failure or unsafe residue; otherwise `InternalFailure` invalidates and quarantines the owner. Incoherent EGL evidence is also `InternalFailure`. |
| A GLES group has no higher-priority contained command failure and its single postprobe reports `GL_OUT_OF_MEMORY` | `ResourceExhausted`, then make the complete GL graph unusable; do not reuse, recover, or delete names in that namespace. |
| FBO/precision evidence is invalid, malformed, or otherwise fails the setup invariant | `InternalFailure` and unusable/quarantined graph; it is not a capacity or fallback signal. |
| Graph mutation, attachment, cleanup, or integrity is ambiguous or unproved | `OwnerInvalidated`; retain unproved roots and do not reuse or fall back. |

Other non-OOM GLES errors and malformed group success are `InternalFailure` through the same unusable-graph rule. The scope (`OperationLocal` only with proved rollback or settlement, otherwise `OwnerInvalidated`) is independent of the problem name.

## EGL retirement proofs

Each successful EGL initialization owns one Android display-initialization reference. The default display can be shared: balancing that reference through `eglTerminate` relies on [Android's reference-counted display implementation](https://android.googlesource.com/platform/frameworks/native/+/master/opengl/libs/EGL/egl_display.cpp) and cooperating clients that release only their own successful initialization. This is not portable EGL reference-counting or an all-OEM guarantee. Initialization entry without a proved successful return is retained; a returned false acquires no successful reference.

The binding thread is recorded before the first `eglMakeCurrent` entry. Even a failed or malformed initial bind requires an independent same-thread unbind and observed `EGL_NO_CONTEXT` before exact context/pbuffer destruction. Failed, throwing, or unproved unbinding retains those resources without retry. Successful context destruction supplies proof for this exact owner’s context namespace, never another owner’s GL names, independently of later pbuffer, initialization, or thread residue.

For a healthy graph, unresolved Target external resources or renderer residue block EGL teardown. An unusable graph can proceed to exact context destruction to settle its GL namespace while external resources remain retained. These are separate proofs: namespace destruction cannot establish listener removal, Surface detachment, or Android-object release.

After exact context/pbuffer retirement, Capture releases its initialization only when all current, candidate, and retiring Target external graphics resources permit it. Renderer GL-name residue alone can be settled by namespace proof. External denial becomes permanently retained before final thread release; a later close cannot reopen it. Each initialization-release attempt is recorded before platform entry and is never retried after false, ordinary failure, or an escaping non-`Exception`. Eligible initialization release precedes final `eglReleaseThread`, because [later EGL calls can recreate thread-local state](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/sdk/docs/man/eglReleaseThread.xml). A final thread-release failure retains that separate obligation without resurrecting an already released initialization. Never-bound failed-open prefixes balance eligible initialization without inventing a binding-thread release step.

## Owner retirement

Retirement is monotone and dependency ordered: fence projection callbacks; attempt projection stop and notify its result; unregister the projection callback; fence target listeners; detach and release the display; retire the renderer and release Target Android objects and eligible GL names; unbind and destroy eligible EGL resources; then request `quitSafely()` once no accepted command remains unresolved. Each action is attempted at most once. Failed or ambiguous resources remain quarantined while independent safe suffix work may continue.

The [`MediaProjection.stop()`](https://developer.android.com/reference/android/media/projection/MediaProjection#stop()) normal/ordinary-exception result is recorded before notifying `ProjectionStopCompletion`; this settles only projection stop, and later unregister or graphics failure does not revoke it. The [AOSP wrapper catches and logs RemoteException](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/media/java/android/media/projection/MediaProjection.java#L220), so normal local return is not independent service acknowledgment. [Session retirement](../01-session/session.md#retirement-and-later-sessions) distinguishes logical completion from this physical sequence; [Coordination](../01-session/coordination.md#progress-limits) owns shared liveness and late-settlement rules.

## Implementation and verification

Start with [SessionCaptureOwner](../../src/main/kotlin/io/screenstream/capture/internal/capture/SessionCaptureOwner.kt) for command entry, replacement, and retirement; [TargetOwner](../../src/main/kotlin/io/screenstream/capture/internal/capture/TargetOwner.kt) and [ProjectionOwner](../../src/main/kotlin/io/screenstream/capture/internal/capture/ProjectionOwner.kt) own external-resource proofs. [EglOwner](../../src/main/kotlin/io/screenstream/capture/internal/capture/EglOwner.kt) owns integrity and namespace retirement; [GLRenderer](../../src/main/kotlin/io/screenstream/capture/internal/capture/GLRenderer.kt) owns the one-pass renderer and exact readback boundary.

Focused evidence lives in `ProjectionOwnerLifecycleTest`, `SessionCaptureOwnerTargetReplacementTest`, `SessionCaptureOwnerReadbackTest`, `EglOwnerLifecycleTest`, and `GLRendererReadbackTest`; device pixels are covered by `GLRendererRawPixelTest`. The [verification catalog](../04-testing/verification-contracts.md) retains the `CAP-*` and `TGT-*` obligations; [image test oracles](../04-testing/image-test-oracles.md) define pixel fixtures and tolerances.
