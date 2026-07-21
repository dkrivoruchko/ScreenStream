# Screen Capture Engine — Domain Contract: EGL, GLES, Readback, and Color

This file solely owns the private GL lane, EGL/GLES2 calls, render/readback/color mechanics, context integrity and
GL cleanup. Product transform order and output semantics remain in [PROD-031](02-product-contract.md#prod-031--geometry-and-visible-transform);
generic occurrence, deadline, terminal and quarantine mechanics remain in
[shared runtime](03-shared-runtime.md); controller selection/currentness remains in
[controller/reconciliation](05-domain-controller-reconciliation.md); and OES/`SurfaceTexture`/`Surface`
ownership and ports remain in [Target](07-domain-target.md).

## Navigation

- [GL-001](#gl-001--files-owners-and-typed-boundary)
- [GL-010](#gl-010--private-lane-admission-fatal-fence-and-termination)
- [GL-020](#gl-020--egl-construction-and-exact-result-reads)
- [GL-030](#gl-030--capability-and-admission)
- [GL-040](#gl-040--fail-closed-gles-groups-and-context-integrity)
- [GL-050](#gl-050--target-oes-bridge-and-gl-construction)
- [GL-060](#gl-060--geometry-shader-color-and-readback)
- [GL-070](#gl-070--destruction-namespace-poison-and-cleanup-suffix)
- [GL-080](#gl-080--exact-verification-matrix)
- [GL-081](#gl-081--raw-image-vector-and-tolerances)
- [GL-090](#gl-090--forbidden-alternatives)
- [GL-100](#gl-100--official-sources)

## GL-001 — Files, owners, and typed boundary

GL is one Session-private authority for its lane/fatal fence, EGL context/pbuffer, programs, render targets,
reusable client storage, context integrity, and every EGL/GLES call. Nothing is shared with the application or
another Session, and private decomposition creates no independent GL lifecycle.

Its closed facts cover per-axis capability/precision, identity-fenced construction/frame/destruction,
retained CPU-state reconciliation, complete render-owner shape, `Success | ResourceExhausted | InternalFailure`,
and `Intact | PoisonedByOutOfMemory | Unknown` context integrity. Retained application is bound to the exact
occurrence, owner/Target identities and quiescence proof, and reports applied actual state or rejection.

Under [CORE-SET-1](03-shared-runtime.md#core-set-1--generic-operation-occurrence) and
[CORE-SET-2](03-shared-runtime.md#core-set-2--entry-return-publication-and-lock-order), each operation/destruction
return writes only its precreated discriminator, scalar error-code/presence fields,
throwable/returned-owner reference when applicable, integrity, and fixed raw facts before generic settlement
publication. Neither success nor failure evidence contains duration. `OperationReturnCell.settlementNanos` is the
sole return sample; duration and immutable diagnostics are derived after settlement and gate release. The return
path allocates no receipt, wrapper, error array, message, action, or fake duration.

Exact inbound/outbound boundaries are:

| Direction | Boundary | GL obligation |
| --- | --- | --- |
| inbound from `CTRL-100`/`CTRL-110` | current key/plan, installed exact render-owner identity, immutable shape and compatibility facts | preflight per axis, execute only current work, publish typed result; never decide lifecycle, replacement, or fallback |
| inbound from `TGT-030`/`TGT-070` | registered one-operation `GlFramePort`, `SurfaceReleasePort`, or TargetScope-destruction port plus counted lease | use only the matching `Surface`, `SurfaceTexture`, and/or OES name inside the lease; retain exact provenance and never copy a raw handle |
| inbound from `CTRL-120`/`TGT-030` | retained reconciliation command with exact occurrence/topology/owner generations, `TargetFrameQuiescedFact`, and complete precreated CPU state | atomically apply or reject it under `glGate` |
| inbound from `NJPEG-030` | exact native- or managed-carrier owner and one operation lease for checked range `[0,B)` | Direct-read only into that range; bytes stay tentative until timely settlement |
| outbound to `TGT-020`/`TGT-070`/`TGT-080` | typed target-OES construction result, exact Surface-release outcome, canonical TargetScope receipt, or exclusive `ContextNamespace` transfer/receipt | publish only exact evidence; Target adopts each returned owner and GL never fabricates a Surface or OES receipt |
| outbound to `CTRL-120`/`CTRL-300` | exact `GlFrameReconciliationResult` | report `Applied(actual installed state)` or `Rejected(reason)`; never reopen or publish |
| outbound to `CTRL-300`/`CTRL-400` and `CORE-CLEAN-1`/`CORE-CLEAN-2` | capability, render-owner/result, color action, readback fact, integrity/namespace and exact residue | publish immutable facts only; transfer the intact occurrence/owner graph when mechanical completion is unknown |

Per [CORE-OWN-1](03-shared-runtime.md#core-own-1--ownership-loans-and-receipts), raw EGL handles, GL names,
generation numbers, Booleans, copied facts, or managed-reference retirement never prove
ownership, currentness, deletion, context retirement, or cleanup.

## GL-010 — Private lane, admission, fatal fence, and termination

The GL lane is the distinct prestarted one-ticket endpoint defined by `CORE-EXEC-1`; EGL ownership begins only
after successful prestart. GL adds no duplicate submission, entry, poison, fatal, or termination protocol. No
synchronous public call waits for this lane.

A frame Runnable's first domain action is its exact GL-frame-port `TargetEntered` transition from `TGT-030`,
before `requireCurrent`, `updateTexImage`, or any other Target/GL outward work. If a Target seal already rejected
the reserved port, the Runnable executes zero such work, publishes only its inert exact settlement, releases its
carrier dependency, and retires the port. If port entry won first, that exact sole predecessor may later acquire
its one Target lease and drain. On every returning or safely unentered/rejected path, carrier release precedes
mechanically settled Target-port retirement; no copied lease count or separate dependency receipt substitutes
for that order.

The lane admits no successor after shutdown admission closes. It retains every accepted, queued, entered, late,
or cleanup-dependent occurrence until that occurrence settles or proves it can need no later lane call. Logical
cleanup transfer alone is insufficient. Only then it calls unlocked `shutdown()` exactly once. Calls to
`shutdownNow()` and shutdown watchdogs are zero. The executor's `terminated()` publishes one precreated
atomic/volatile termination receipt with release/acquire visibility before signalling; nontermination roots the
executor/thread and exact dependent residue.

Only enumerated allocation-boundary `OutOfMemoryError` is an ordinary domain result. Every other direct fatal
throwable uses the precreated GL fatal slot, exact raw engine-boundary rethrow, replacement-worker poison check,
and retained owner suffix required by `CORE-FATAL-1`. Direct `Error` retains thread-top identity; a custom direct
non-`Exception`/non-`Error` Throwable may have a runtime-shaped thread-top wrapper without changing raw engine
authority. The active occurrence and namespace remain rooted; later EGL teardown is not promised.

This structure proves engine-owned queue/worker isolation between Sessions. It does not assert finite progress
through vendor-global driver locks. Deterministic isolation tests stop immediately before driver entry; real
driver cross-Session concurrency is observational only.

## GL-020 — EGL construction and exact result reads

`EGLDisplay` and `EGLConfig` are non-owned framework/process handles and never enter Session quarantine. The
Session owns only its context, pbuffer, lane work and GL objects. Construction executes on the private lane:

| Step | Exact call/requirements | Ownership/result |
| --- | --- | --- |
| display | `eglGetDisplay(EGL_DEFAULT_DISPLAY)`; require not `EGL_NO_DISPLAY`; then `eglInitialize(display, major,0, minor,0)` true | display remains non-owned |
| config | `eglChooseConfig` requests one config; `EGL_SURFACE_TYPE=PBUFFER_BIT`, `EGL_RENDERABLE_TYPE=OPENGL_ES2_BIT`, `EGL_CONFORMANT=OPENGL_ES2_BIT`, RGBA minima `8/8/8/8`, depth/stencil requests `0/0`, terminator `EGL_NONE`; require true, count 1, nonnull descriptor | larger components and physical depth/stencil are valid; config remains non-owned |
| context | `eglCreateContext(display, config, EGL_NO_CONTEXT, [EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE],0)`; require not `EGL_NO_CONTEXT` | returned context enters partial owner immediately |
| pbuffer | `eglCreatePbufferSurface(display, config, [EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE],0)`; require not `EGL_NO_SURFACE` | returned 1x1 pbuffer enters partial owner; no exact-storage claim |
| bind | `eglMakeCurrent(display,pbuffer,pbuffer,context)` true, then initial identity check | Session GL ownership publishes only after both succeed |

The pbuffer exists only to keep the context current; render and readback use the output FBO. `eglMakeCurrent` is
called nowhere else except the one teardown unbind.

EGL error-read cardinality is exact:

| Observed result | Immediate `eglGetError()` calls |
| --- | ---: |
| ordinary true/non-sentinel result, including structurally invalid success-shaped output | `0` |
| ordinary false/sentinel result | `1` |
| initial or inverse `eglGetCurrentContext()` identity match | `0` |
| initial or inverse identity mismatch | `1` |
| `eglReleaseThread()==true` | `0` |
| `eglReleaseThread()==false` | `1` |

Each required read is immediate and same-thread; there is no retry. `EGL_BAD_ALLOC` is `ResourceExhausted` only
when coherent evidence from exact display/config/context/pbuffer construction retains safe partial ownership.
Every other code, missing/inconsistent code, malformed success-shaped output, currentness mismatch, or teardown
failure is `InternalFailure`.

After the initial bind and before every GL construction, frame, or destruction occurrence, the helper calls only
`eglGetCurrentContext()` and requires referential identity with the Session context. It never queries current
display/surfaces. Mismatch reads one error, marks integrity `Unknown`, and fails closed without repair/rebind.

## GL-030 — Capability and admission

Immediately after a valid initial bind/currentness check and before size-dependent GL construction, one
fail-closed group queries into precreated owner arrays:

```text
glGetIntegerv(GL_MAX_TEXTURE_SIZE, scalar)
glGetIntegerv(GL_MAX_VIEWPORT_DIMS, int[2])
glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER, GL_HIGH_FLOAT, range[2], precision[1])
```

A clean result requires positive max texture size and both viewport axes. Highp is selected only when range low,
range high and precision are all positive; the all-zero triple selects mediump. Mixed/otherwise invalid precision
or nonpositive capacity is `InternalFailure`. The query runs once per Session. Absence of highp neither rejects
nor selects a different product path; selected precision is diagnostic.

Before Target or render construction, compare requested width and height independently to texture maximum and
the matching viewport axis. Deterministic denial occurs before construction, retirement, or allocation and
follows [CTRL-110](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision); the GL owner neither
predicts memory nor derives a GPU-byte estimate. Checked
`B=4*Ow*Oh` is only the exact CPU transfer/carrier byte count.

## GL-040 — Fail-closed GLES groups and context integrity

Every capability, construction, frame, and destruction GLES group obeys one protocol:

| Phase | Required calls/result |
| --- | --- |
| preprobe | exactly one scalar `glGetError()`; nonzero executes zero group commands |
| commands | the fixed group calls run only after `GL_NO_ERROR`; abrupt/nonreturning commands yield no postprobe or receipt |
| postprobe | after normally completed fixed commands, exactly one scalar `glGetError()`; clean result is required for any receipt |
| stop | any nonzero probe permits zero later GLES calls in this context |

There is no drain, confirmation probe, retry, continuation probe, error array, or cap. The first observed
`GL_OUT_OF_MEMORY`, including stale, late, or deletion evidence, has precedence and irreversibly changes the
whole private unshared context to `PoisonedByOutOfMemory`. Any other nonzero probe changes it to `Unknown` and is
`InternalFailure`. Both states prohibit every later GLES probe, command, deletion, fallback, render, readback,
pixel publication, per-object receipt, and context reuse.

`glEnteredOperationSafetyNanos = 10_000_000_000L`. One interval covers each finite preterminal EGL/GL
construction-validation occurrence, Target/render construction, complete materialized frame from currentness and
acquisition through groups/readback, or TargetScope destruction after its separate Surface prerequisite. It is
not per call or group. The checked rule in
[CORE-SET-3](03-shared-runtime.md#core-set-3--finite-deadline-arbitration) applies: timely is `T < D`; expiry or
`T >= D` is unsafe
`InternalFailure`, cannot publish pixels/authorize reuse, and reduces only cleanup if it later returns. A
terminal-origin or terminal-converted destruction occurrence keeps the same identity and has no watchdog.

## GL-050 — Target OES bridge and GL construction

After per-axis preflight, one `PreparedTarget` construction occurrence on the GL lane performs:

```text
glGenTextures -> bind GL_TEXTURE_EXTERNAL_OES
min/mag GL_LINEAR; wrap S/T GL_CLAMP_TO_EDGE
SurfaceTexture(exactNonzeroOesName, false)
setDefaultBufferSize(Tw,Th) -> Surface(surfaceTexture)
```

The Target owner adopts each returned OES/`SurfaceTexture`/`Surface` immediately as specified by
[TGT-020](07-domain-target.md#tgt-020--preparation-and-single-disposition). GL receives raw handles only through
registered ports/leases after installation; it never owns the Android `SurfaceTexture` or `Surface` lifetime.
Direct `Surface.OutOfResourcesException` is mandatory-allocation `ResourceExhausted`; another constructor/
default-size failure is `InternalFailure`. Partial construction retains exactly the owners already returned.

Pipeline construction creates one ESSL 1.00 program using `GL_OES_EGL_image_external`. It compiles the vertex and
selected highp/mediump fragment shaders, requires compile status, creates/links a nonzero program, binds
`aPosition=0` and `aTexCoord=1`, requires link status and all used uniform locations nonnegative, then
detaches/deletes the shaders. Partial objects remain owned until a clean deletion receipt or aggregate namespace
retirement.

One `GlRenderTargetOwner` contains exactly one output-sized 2D texture and one FBO:

```text
2D texture: min/mag GL_NEAREST; wrap S/T GL_CLAMP_TO_EDGE
glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,Ow,Oh,0,GL_RGBA,GL_UNSIGNED_BYTE,null)
FBO: attach texture at GL_COLOR_ATTACHMENT0
glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
```

The format does not promise physical component storage width. Zero/bad name, shader/program/status/location
failure, incomplete FBO, malformed clean result, or non-OOM GL error is `InternalFailure`; deterministic
preflight denial follows [CTRL-110](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision);
probe OOM follows [GL-040](#gl-040--fail-closed-gles-groups-and-context-integrity).
Installation requires the exact complete owner identity and current reconciliation key.
An installed healthy owner is reused while its image size, checked byte count, fixed format and pipeline identity
remain exactly compatible; Target replacement alone does not retire it.

Small vertex/texture-coordinate and OES-transform client storage is retained and reused. Frame processing creates
no per-frame dependent buffer/transform object, PBO, or second raw-frame-sized copy; its storage representation is
private.

## GL-060 — Geometry, shader, color, and readback

Retained reconciliation applies one immutable transform/color state bound to the exact retained occurrence,
topology, owner/Target identities, and `TargetFrameQuiescedFact`. Under `glGate` it atomically records actual
installed state; wrong identity, absent quiescence, or repetition is inertly rejected. Each frame uses a matching
immutable snapshot. Application makes
zero EGL/GLES calls and changes no texture, FBO, carrier, JPEG owner, context health, lifecycle, or currentness.
A stale `Applied` result still reports actual mechanical state but cannot reopen or publish; `CTRL-120` keeps
admission sealed and compensates the latest state. Density-only skips application only when recorded GL state
already matches; retained output-size-compatible region, crop, rotation, mirror, or color change requires it.

The shader implements [PROD-031](02-product-contract.md#prod-031--geometry-and-visible-transform) once, with the
following exact GL mapping. For source `(W,H)`, selected region is Full `(0,0,W,H)`, LeftHalf `(0,0,W/2,H)`, or
RightHalf `(W/2,0,W-W/2,H)`. After crop:

```text
x0=rx0+left; y0=ry0+top; Sw=rw-left-right; Sh=rh-top-bottom
(Rw,Rh)=(Sw,Sh) for 0/180; (Sh,Sw) for 90/270
u0=(i+0.5)*Rw/Ow; v0=(j+0.5)*Rh/Oh
```

Undo oriented mirror (`Horizontal: u=Rw-u0`; `Vertical: v=Rh-v0`), then undo clockwise rotation:

```text
0: xs=u, ys=v        90: xs=v, ys=Sh-u
180: xs=Sw-u, ys=Sh-v    270: xs=Sw-v, ys=u
```

Full uses `Tw=W,Th=H`; Downscaled preserves `Tw:Th=W:H` and active content fills its target. Normalized
pre-OES coordinates are `((x0+xs)/W,(y0+ys)/H)`. All calculations use binary64 in the CPU oracle without
intermediate integer rounding. The copied 4x4 OES matrix multiplies `(preOesX,preOesY,0,1)` exactly once;
framebuffer row inversion is separate and occurs once. External sampling is linear/clamp-to-edge. The CPU oracle
maps `qx=a*Tw-0.5`, `qy=b*Th-0.5`, clamps them to `[0,Tw-1]` and `[0,Th-1]`, then uses
`x0=floor(qx)`, `x1=min(x0+1,Tw-1)` and the equivalent `y0/y1` with the remaining fractional parts for bilinear
interpolation. It clamps each final normalized channel to `[0,1]` and quantizes
`min(255,max(0,floor(255*c+0.5)))`.

After every `updateTexImage()`, the owner copies and validates all 16 finite values from
`getTransformMatrix(precreatedFloat16)`. API 24–32 then uses nominal SDR. API 33+ additionally reads
`SurfaceTexture.getDataSpace()` and classifies in first-match order:

| Evidence | Action |
| --- | --- |
| exact `DATASPACE_SRGB` | nominal sRGB |
| exact `DATASPACE_DISPLAY_P3` | fused Display-P3 to sRGB |
| exact `DATASPACE_SCRGB` or `DATASPACE_SCRGB_LINEAR` | system-composited 8-bit best effort |
| remaining transfer exactly `TRANSFER_ST2084` or `TRANSFER_HLG` | system-composited 8-bit best effort |
| every other integer | nominal-SDR best effort |

Exact dataspace equality precedes transfer extraction. Display capability is never source-buffer evidence. One
`ColorAction` fact is offered for the target, then only when classification/action changes.
[CTRL-200](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority) and
[CTRL-300](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules) own acceptance/commit;
[DEL-OBS-020](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission) owns unlocked
construction/emission. Per-frame reads offer no routine event. V1 exposes no HDR flag or HDR colorimetric guarantee.

The exact Display-P3 action in [PROD-031](02-product-contract.md#prod-031--geometry-and-visible-transform) is fused
into the selected shader with no extra FBO. For each sampled gamma-coded channel
`c`, decode sRGB, multiply D65 linear values in this written order, clamp each linear result, then encode sRGB:

```text
decode(c) = c/12.92                         if c <= 0.04045
            ((c+0.055)/1.055)^2.4          otherwise
R' =  1.2247452668R - 0.2249043652G
G' = -0.0420579309R + 1.0420810013G
B' = -0.0196422806R - 0.0786549180G + 1.0985371988B
encode(c) = 12.92c                          if c <= 0.0031308
            1.055*c^(1/2.4)-0.055           otherwise
```

The CPU P3 oracle starts exact 8-bit input as `c8/255.0`, evaluates binary64 in written order, clamps before
encoding, and quantizes like the sampling oracle. After source color handling, the shader applies the exact
grayscale transform owned by [PROD-031](02-product-contract.md#prod-031--geometry-and-visible-transform) and
forces alpha `255`.

For every frame, canonical state binds the exact FBO/program/viewport, fixed OES texture unit and matching
sampler, uploads the reconciled transform/color uniforms, binds the two retained client arrays, enables their
attributes, sets full color mask and pack alignment `1`, and disables blend, depth, stencil, scissor, cull and
dither. One fail-closed group installs state. A second draws `glDrawArrays(GL_TRIANGLE_STRIP,0,4)` and calls
`glReadPixels(0,0,Ow,Oh,GL_RGBA,GL_UNSIGNED_BYTE,carrier)` into the leased exact range `[0,B)`. Clean postprobes
and timely settlement are required before pixels publish. Output is tight, opaque, top-down RGBA; repeat work
admitted under [CTRL-200](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority)
performs no GL call.

## GL-070 — Destruction, namespace poison, and cleanup suffix

After [TGT-060](07-domain-target.md#tgt-060--mutually-exclusive-retirement-readiness) establishes release
readiness, GL accepts the exact registered `SurfaceReleasePort` and counted lease, submits its occurrence through
[GL-010](#gl-010--private-lane-admission-fatal-fence-and-termination), and invokes `Surface.release()` exactly
once on the private GL lane. Synchronous submission rejection follows the generic occurrence partition; a
terminal-origin or pre-entry terminal-converted occurrence keeps the same identity without a watchdog, and
terminal after entry transfers the intact occurrence. [AND-CAP-040](06-domain-android-capture-metrics.md#and-cap-040--android-deadlines-and-call-policy)
owns the preterminal deadline and result classification; [TGT-070](07-domain-target.md#tgt-070--surface-and-targetscope-destruction)
alone constructs and consumes the matching normal-return receipt. A normal late return supplies the same real
receipt for cleanup only; throw supplies none. Rejection, throw, expiry, or nonreturn retains the occurrence,
port/lease, Surface, and dependent TargetScope resources under [CORE-CLEAN-1](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph)
and [CORE-CLEAN-2](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction).

Healthy destruction uses owner-derived groups only: output FBO/texture; program and partial shaders; then, only
after the exact `SurfaceTexture.release()` receipt, TargetScope OES unbind/delete. Each structurally present group
receives a receipt only after its clean postprobe. There is no global deletion-count constant. A dirty probe stops
all later GLES cleanup and switches to aggregate EGL retirement.

On first OOM or unknown integrity, one `ContextNamespace` bag retains the context, pbuffer, every known or
possibly affected GL object, and the exact TargetScope OES obligation transferred once under
[TGT-080](07-domain-target.md#tgt-080--context-poison-transfer). It creates no ordinary OES receipt. Non-GLES
producer/detach/listener/zero-lease, `Surface.release()` and `SurfaceTexture.release()` prerequisites continue.
Running publishes `Suspended(Reconfiguring)` while classification/retirement is pending; startup stays
`Starting`; [CTRL-130](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration)
alone commits that state or terminal result. Neither path retries, falls back, renders, or returns Active.

After prerequisites, aggregate retirement is:

| Ordered prefix | Success evidence | Failure effect |
| --- | --- | --- |
| one unbind `eglMakeCurrent(display,NO_SURFACE,NO_SURFACE,NO_CONTEXT)` | true | false/error/expiry/nonreturn: `InternalFailure`, retain exact suffix |
| one inverse `eglGetCurrentContext()` | exact `EGL_NO_CONTEXT` | mismatch plus one error read: `InternalFailure`; no context-destroy receipt |
| one `eglDestroyContext(display,context)` | true publishes `GlDestructionSuccessReceipt(ContextNamespace)` | false/error/expiry/nonreturn: `InternalFailure`; retain exact suffix |

The aggregate receipt retires the transferred Target OES obligation transitively and means namespace
retirement/mark-for-deletion, not immediate physical-byte reclamation. For OOM only, this complete prefix permits
terminal `ResourceExhausted`; unknown integrity remains `InternalFailure`. Failure before the context receipt
selects `InternalFailure`. A stale/late poison cannot rewrite an earlier terminal winner.

After successful unbind, context and reachable pbuffer destruction are independent one-shot attempts: a normally
returned context-destroy failure does not suppress `eglDestroySurface(display,pbuffer)`, though a same-lane
nonreturn may block the suffix. After the context receipt fixes the OOM terminal candidate, the pbuffer attempt
and one `eglReleaseThread()` continue independently. Their later failures root only their exact suffix and never
rewrite the terminal winner. Teardown has at most one unbind, inverse check, context destroy, pbuffer destroy and
release-thread call, no retry or repair bind, and zero `eglTerminate` calls. Healthy Session teardown uses the
same one-shot EGL suffix after clean per-object destruction. Fatal JVM `Error` retains its stronger no-promised-
teardown rule in [GL-010](#gl-010--private-lane-admission-fatal-fence-and-termination).
The intact residue and late facts follow
[CORE-CLEAN-1](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph),
[CORE-CLEAN-2](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction), and controller handoff
in [CTRL-400](05-domain-controller-reconciliation.md#ctrl-400--cleanuproot-handoff); this section adds no generic
cleanup state.

## GL-080 — Exact verification matrix

Closed packet membership is in [router §5](01-authority-router.md#5-closed-implementation-packets); runners,
shared closure/routing and test namespaces are in [Document 04](04-verification.md), and canonical test source sets are in
[router §7](01-authority-router.md#7-test-manifest). The matrix below retains GL-specific proof through `H-GM`,
`H-RC`, `H-OS`, `A-GL`, and `A-CL`:

| Test | Exact GL proof |
| --- | --- |
| `H-GM` | checked geometry/oracle arithmetic; per-axis capacity; P3 binary64 conversion; color/grayscale classifications; raw vector expectations and tolerances below |
| `H-RC` | exact retained-command bindings; closed `Applied | Rejected`; same-`glGate` allocation-free swap/frame snapshot; stale actual-state report and A-to-B-to-A compensation input; zero EGL/GLES, Session-currentness, lifecycle, or health authority |
| `H-OS` | GL interval literal and `D-1/D/D+1`; precreated evidence/no duration; submission/entry/return/rejection/expiry/late/terminal transfer; first-action Target entry and carrier-release-before-port-retire order; allocation-free return and reconciliation swap/snapshot paths |
| `A-GL` | real EGL/GLES2, OES acquisition, GL-lane Surface release, transforms, FBO-only Direct readback and image; first-action Target entry and reserved rejection with zero outward work; closed retained CPU reconciliation apply/reject, synchronized frame snapshot, stale actual-state/A-to-B-to-A compensation and zero EGL/GLES; injected exact EGL/GLES classifier inputs; partial adoption, currentness, poison, fatal fence and destruction receipts |
| `A-CL` | namespace/OES transfer, prerequisite holds, unbind/context/pbuffer/release-thread residues, lane shutdown/nontermination, independent-root and pre-driver cross-Session progress |

Every GL operation is crossed with current/stale/terminal keys, timely/equality/late settlement, throw,
rejection, nonreturn, and each owner held separately where applicable. Exact classifier cases are:

- executor construction, successful prestart, early entry, one-slot saturation, exact rejection, raw fatal-slot
  identity/visibility and engine-boundary rethrow, direct-Error thread-top identity, permitted runtime-shaped
  custom-Throwable thread top, replacement-worker fence checks, one shutdown, zero forceful shutdown, termination and
  nontermination;
- each EGL true/non-sentinel, malformed-success, false/sentinel, current-context match/mismatch and
  release-thread result with [GL-020](#gl-020--egl-construction-and-exact-result-reads) call counts;
- capability highp positive, mediump all-zero, mixed malformed, nonpositive/per-axis limit, and clean
  pre-retirement denial;
- every group with dirty preprobe, normal commands plus clean/dirty postprobe, command throw/nonreturn, OOM at
  either probe, stale/late OOM, and zero later GLES calls/receipts;
- partial target/program/render construction; exact GL ports/leases, reversible seal entry orders and retained reconciliation application; state/draw/readback; clean structural
  deletion groups; exact Surface-release submission/invocation and deadline outcomes; TargetScope receipt versus
  exclusive namespace transfer; each release/poison prerequisite and cleanup suffix failure/nonreturn;
- deterministic isolation at the injected immediately-pre-driver seam; real-driver concurrency only as an
  observation.

### GL-081 — Raw image vector and tolerances

Tests generate this top-down opaque `5 x 3` RGBA vector; production contains no test asset:

```text
#FF0000  #B34D26  #000000  #00FFFF  #00FF00
#FF00FF  #404040  #808080  #C0C0C0  #FFFFFF
#0000FF  #7030B0  #26994D  #008080  #FFFF00
```

All 15 pixels verify row/channel order. Landmarks are four corners, `(0,1)`, and `(2,1)`. Derived tight row is
`20` bytes and range is `60` bytes. Geometry cases are LeftHalf columns `0,1`; RightHalf `2,3,4`; crop
`(1,0,1,1)` leaving `3x2`; all rotations and both mirrors; `ScaleFactor(2.0)` giving `10x6`;
`TargetSize(8,8)` giving Stretch `8x8` and AspectFit `8x5`; and eligible Downscale from a 2x-expanded `10x6`
source to `5x3`, plus every closed Full case.

Exact dimensions, bounds, mapped landmarks, top-down orientation and raw alpha are required. Sampled RGB uses
maximum absolute eight-bit channel error:

| Path | Maximum error |
| --- | ---: |
| nominal sRGB highp | `2` |
| nominal sRGB mediump | `6` |
| Display-P3 highp | `3` |
| Display-P3 mediump | `8` |
| grayscale highp vs integer-Y | `2` |
| grayscale mediump vs integer-Y | `6` |
| Early Downscale at each exact landmark vs CPU/source oracle | `12` |

Raw grayscale additionally requires exact `R==G==B`; raw and decoded alpha is exactly `255`. P3 classification
and `ColorAction` are exact independent of tolerance. Unsupported wide/HDR checks its best-effort action and
diagnostic, not a new colorimetric claim. These tolerances never select precision, target mode, fallback, or any
runtime path.

## GL-090 — Forbidden alternatives

- shared/application GL context, process-global GL owner, `eglTerminate`, repair rebind, retry, or error drain;
- `submit`, unbounded queue, more than one GL worker, `shutdownNow`, shutdown watchdog, or transfer-as-termination;
- generic fatal settlement state, swallowed/replaced fatal `Error`, or useful work after fatal publication;
- raw handle/name as owner/currentness/receipt, general Target getter, lease-free access, or duplicate GL receipt;
- GL work after a dirty probe, per-object deletion after poison, fabricated deletion/namespace receipt, or OOM
  fallback/retry;
- pbuffer/default-framebuffer readback, PBO, second B-sized raw copy, or per-frame dependent storage allocation;
- GPU storage accounting inferred from RGBA transfer format, predictive memory accounting, device allowlist,
  image score, measured performance, or tests as runtime selection axes;
- extra color framebuffer, Display capability as buffer color evidence, HDR guarantee, or linear-light claim for
  V1 grayscale.

## GL-100 — Official sources

- Android [EGL14](https://developer.android.com/reference/android/opengl/EGL14),
  [GLES20](https://developer.android.com/reference/android/opengl/GLES20), and
  [OpenGL ES on Android](https://developer.android.com/develop/ui/views/graphics/opengl/about-opengl);
- Khronos [OpenGL ES 2.0 specification](https://registry.khronos.org/OpenGL/specs/es/2.0/es_full_spec_2.0.pdf)
  and [`GL_OES_EGL_image_external`](https://registry.khronos.org/OpenGL/extensions/OES/OES_EGL_image_external.txt);
- Android [`SurfaceTexture`](https://developer.android.com/reference/android/graphics/SurfaceTexture),
  [transform matrix](https://developer.android.com/reference/android/graphics/SurfaceTexture#getTransformMatrix(float[])),
  [dataspace](https://developer.android.com/reference/android/graphics/SurfaceTexture#getDataSpace()), and
  [`DataSpace`](https://developer.android.com/reference/android/hardware/DataSpace);
- Android [color management](https://source.android.com/docs/core/display/color-mgmt) and
  [HDR tone mapping](https://source.android.com/docs/core/display/tone-mapping).

Official API/specification contracts are authoritative. Platform implementation detail may explain behavior but
does not create a public guarantee; absent guarantees use the fail-closed rules above.
