# Capture and rendering

Capture turns an available projection image into tight, top-down RGBA8888 bytes. It reports physical facts; Session decides whether they belong to the requested configuration. Public transform choices are in [How image settings combine](../../docs/usage.md#how-image-settings-combine); [Runtime flows](../architecture/runtime.md) describes the surrounding Session sequence.

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

Capture adopts a projection already owned by the Session following successful synchronous factory return. This is an internal handoff; later `start()` takes no projection. Ownership before Capture adoption, including stopping a created Session whose startup never enters, belongs to the [runtime lifecycle](../architecture/runtime.md).

Open registers [`MediaProjection.Callback`](https://developer.android.com/reference/android/media/projection/MediaProjection.Callback) on the control handler before it creates the virtual display, as required by Android. It then creates and binds EGL, constructs and listens to the Target, creates the renderer/output FBO, and finally invokes [`MediaProjection.createVirtualDisplay`](https://developer.android.com/reference/android/media/projection/MediaProjection#createVirtualDisplay(java.lang.String,int,int,int,int,android.view.Surface,android.hardware.display.VirtualDisplay.Callback,android.os.Handler)) with the source width, source height, density, Target surface, and `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`.

There is exactly one `createVirtualDisplay` call per projection. Reconfiguration uses [`VirtualDisplay.resize`](https://developer.android.com/reference/android/hardware/display/VirtualDisplay#resize(int,%20int,%20int)) and, when the Target changes, [`VirtualDisplay.setSurface`](https://developer.android.com/reference/android/hardware/display/VirtualDisplay#setSurface(android.view.Surface)); it never creates a second display. This preserves Android 14+ single-use projection consent semantics.

`onStop` fences duplicate projection callbacks, reports the exact projection-stopped fact, and requests Capture-lane retirement. It performs no EGL work and chooses no public terminal result. On API 34+, positive captured-content resize callbacks supply authoritative physical source dimensions; visibility callbacks remain optional information. On older APIs, the session resolves source dimensions from [Metrics](metrics.md).

Before the first authoritative API 34+ resize, Open uses only positive Metrics source dimensions and density. It creates a Full Target over the whole source with neutral rotation, mirror, and crop, plus a bounded `1 x 1` renderer output. The resolved plan is explicitly provisional: its internal preparation parameters cannot become public effective metadata, and after the one Open the session waits without definitive Apply or Encoding preparation. The first authoritative resize resolves and validates the latest requested parameters, then follows the ordinary atomic revision, Apply, Encoding, and first-Active sequence. Failure of the neutral physical setup remains an Open failure; authoritative invalidity remains a startup failure.

## Target and source arrival

A Target owns one external OES texture, a [`SurfaceTexture`](https://developer.android.com/reference/android/graphics/SurfaceTexture), and its `Surface`. For detached construction, [`attachToGLContext`](https://developer.android.com/reference/android/graphics/SurfaceTexture#attachToGLContext(int)) must run with the Capture context current and an unused texture name; attachment creates the texture object, which can then be configured for linear, clamp-to-edge sampling. Attachment must precede `updateTexImage()`. Target setup also applies the buffer size and installs the frame listener on the Capture handler. The listener only marks its `SourceCandidate` available and reports the exact Target identity. There is no frame-event queue or pending count.

Candidate state is linear: unavailable, available, reserved by one read, then available again only if settlement proves that the source was not consumed. Because listener dispatch and reads share the Capture handler, a callback dispatched before read settlement belongs to the reserved opportunity. Only a later callback can create a successor. `SurfaceTexture.updateTexImage()` consumes the producer's most recent image, so counting callbacks would imply a precision the platform does not provide.

The resolved definitive `CapturePlan` selects Full or Downscaled Target mode under the [Image pipeline contract](../contracts/image-pipeline.md). Capture implements Downscaled mode on API 32+, where Android specifies uniform fit and centering into a smaller Surface. The provisional API 34+ preparation plan is always Full.

A healthy Full Target is reused while Full remains selected and source dimensions are unchanged. Source-size changes replace the Target. A healthy Downscaled Target may be reused without shrinking only while it satisfies the [image contract's compatibility invariant](../contracts/image-pipeline.md#output-sizing-and-requested-versus-applied): sufficient size on both rotation-aware axes, matching source aspect, and the same source-to-Target coordinate meaning. Size alone does not establish compatibility after an aspect-changing source resize. Minimum size governs creation of a new Target; reuse need not choose that minimum again. The optimization changes producer resolution, never crop or transform semantics.

## EGL, GLES, and the pixel boundary

EGL is created and used only on the Capture thread. The owner selects an ES2 pbuffer configuration with at least 8-bit RGBA channels, creates an unshared context, binds the exact display/context/pbuffer tuple, and verifies that the tuple is current. Capability setup checks maximum texture size, both viewport dimensions, and fragment float precision through [`glGetShaderPrecisionFormat`](https://developer.android.com/reference/android/opengl/GLES20#glGetShaderPrecisionFormat(int,%20int,%20int[],%20int,%20int[],%20int)). The engine prefers high precision and selects its GLES 2.0 medium-precision compatibility path when the high-precision report is all zeroes.

The renderer realizes the [Image pipeline contract](../contracts/image-pipeline.md) with one OES shader pass into a reusable final-size RGBA framebuffer and direct [`glReadPixels`](https://developer.android.com/reference/android/opengl/GLES20#glReadPixels(int,%20int,%20int,%20int,%20int,%20int,%20java.nio.Buffer)) into the borrowed carrier. Private plan parameters keep the inverse logical transform, color mode, and all four retained-edge bounds coherent on Open and every Apply, including an Apply that keeps the same output size. The vertex shader emits the transformed logical image coordinate; each fragment clamps that coordinate to retained centers, performs row inversion, applies the acquired OES matrix once, and samples once. Full and Downscaled whole-image plans use bounds `0..1`. API 33+ queries [`SurfaceTexture` dataspace](https://developer.android.com/reference/android/graphics/SurfaceTexture#getDataSpace()) before drawing; API 24–32 do not access that symbol. The path adds four scalar uploads per read and bounded fragment arithmetic, with no additional sample, pass, frame allocation, `ImageReader`, PBO, staging frame, or full-frame copy.

Each coherent GLES operation group has one post-operation `glGetError` probe. Any contained group failure makes the entire GL graph unusable. It cannot be read, reused, repaired, or have individual GL names deleted; exact EGL teardown retires the namespace. This conservative engine policy accounts for uncertain earlier mutations and graph reusability. It is stricter than [GLES 2.0, section 2.5](https://registry.khronos.org/OpenGL/specs/es/2.0/es_full_spec_2.0.pdf): `GL_OUT_OF_MEMORY` can leave undefined results, while ordinary GL errors cause the offending command to be ignored.

Each successful EGL initialization owns one Android display-initialization reference. The default display can be shared: balancing that reference through `eglTerminate` relies on [Android's reference-counted display implementation](https://android.googlesource.com/platform/frameworks/native/+/master/opengl/libs/EGL/egl_display.cpp) and cooperating clients that release only their own successful initialization. This is not portable EGL reference-counting or an all-OEM guarantee. Initialization entry without a proved successful return is retained; a returned false acquires no successful reference.

The binding thread is recorded before the first `eglMakeCurrent` entry. Even a failed or malformed initial bind requires an independent same-thread unbind and observed `EGL_NO_CONTEXT` before exact context/pbuffer destruction. Failed, throwing, or unproved unbinding retains those resources without retry. Exact context destruction supplies namespace proof independently of later pbuffer, initialization, or thread residue.

After exact context/pbuffer retirement, Capture releases its initialization only when all current, candidate, and retiring Target external graphics resources permit it. Renderer GL-name residue alone can be settled by namespace proof. External denial becomes permanently retained before final thread release; a later close cannot reopen it. Each initialization-release attempt is recorded before platform entry and is never retried after false, ordinary failure, or an escaping non-`Exception`. Eligible initialization release precedes final `eglReleaseThread`, because [later EGL calls can recreate thread-local state](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/sdk/docs/man/eglReleaseThread.xml). A final thread-release failure retains that separate obligation without resurrecting an already released initialization. Never-bound failed-open prefixes balance eligible initialization without inventing a binding-thread release step.

## Read bridge to Encoding

For fresh production, [Encoding](encoding.md) loans one exact direct writable range. The session binds that input to an opaque `CaptureReadReturnPort` and passes Capture only the view, exact plan/source identities, and return port. Capture cannot settle or reuse the carrier and sees no Encoding type.

On entry the read reserves the exact source candidate, writes only `[0, B)`, settles whether the source opportunity was consumed, releases its own command occupancy, and invokes the return port once. After the exact request is installed, either its real Capture return or definite proof that submission was rejected before entry lets the session claim and settle the matching read bridge and discard its exact Encoding input. Submission acceptance, timeout, cancellation, terminal state, and reference loss provide no such proof. Retirement may detach semantic correlation, but an accepted nonreturning read continues to root its carrier view and physical dependencies. A late return may settle only that bridge; it cannot restore session admission.

## Replacement, failures, and retirement

Target replacement constructs an unattached candidate first. A clean pre-attachment capacity denial rolls the candidate back and leaves the old graph usable. After attachment begins, listener removal, display resize, listener installation, and `setSurface` form an identity-sensitive transition. A throwing or nonreturning `setSurface` proves neither attachment nor detachment, so both Target roots remain retained and the owner is invalidated.

Apply and read failures distinguish `OperationLocal` from `OwnerInvalidated`. The former requires proof that the owner remained reusable or was fully rolled back. The latter is used whenever mutation, attachment, listener state, or GL integrity is ambiguous. Session consumes the explicit scope; it must not infer health from the problem category or whether a source was consumed. Cross-component classification and terminal priority are defined in [Failures and terminal semantics](../contracts/failures-and-terminal-semantics.md).

Capture uses this compact physical classifier and precedence at the boundary; the [verification contracts](../testing.md#verification-contracts) map focused `CAP-01`–`CAP-06` and `TGT-01`–`TGT-02` evidence:

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
