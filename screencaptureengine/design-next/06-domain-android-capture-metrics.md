# Screen Capture Engine — Android Capture and Metrics Domain

This leaf owns built-in capture metrics, metrics collection, geometry-source arbitration, the Session Android
lane, `MediaProjection`, and `VirtualDisplay`. Application/platform obligations and public behavior belong to
[PROD-001](02-product-contract.md#prod-001--product-and-support-boundary) and
[PROD-020](02-product-contract.md#prod-020--configuration-and-metrics),
shared occurrences and cleanup belong to [CORE-SET-1](03-shared-runtime.md#core-set-1--generic-operation-occurrence)
through [CORE-CLEAN-2](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction). Target
construction, ports/leases, listener state, and release are owned respectively by
[TGT-020](07-domain-target.md#tgt-020--preparation-and-single-disposition),
[TGT-030](07-domain-target.md#tgt-030--currenttarget-ports-leases-and-provenance),
[TGT-050](07-domain-target.md#tgt-050--listener-source-bit-and-generation-fence), and
[TGT-060](07-domain-target.md#tgt-060--mutually-exclusive-retirement-readiness)/
[TGT-070](07-domain-target.md#tgt-070--surface-and-targetscope-destruction).

Canonical paths are in [router §6](01-authority-router.md#6-source-manifest). `PUB:ScreenCaptureConfig.kt`
declares the built-in provider definitions. `AND:CaptureMetricsContracts.kt` and
`AND:CaptureMetricsOwner.kt` own metrics facts and collection lifetime. `AND:AndroidCaptureContracts.kt` owns
capture evidence and occurrence bags; `AND:AndroidLane.kt` contains the single Session
`HandlerThread`/`Handler` runtime; `AND:MediaProjectionOperations.kt` and
`AND:VirtualDisplayOperations.kt` contain their named platform boundaries; and
`AND:AndroidCaptureOwner.kt` is the sole mutable domain facade. These files share one Android authority and do
not create an additional lane, gate, owner, or state machine.

## Navigation

- [AND-MET-001](#and-met-001--provider-identity-and-collection-ownership)
- [AND-MET-010](#and-met-010--exact-api-band-reads-and-authority)
- [AND-MET-011](#and-met-011--epoch-invalidation-and-latest-geometry)
- [AND-MET-020](#and-met-020--structured-metrics-lifetime)
- [AND-MET-021](#and-met-021--provider-mechanical-outcome-partition)
- [AND-MET-030](#and-met-030--built-in-close-and-residue)
- [AND-CAP-001](#and-cap-001--android-lane-and-startup-sequence)
- [AND-CAP-010](#and-cap-010--projection-and-virtualdisplay-calls)
- [AND-CAP-020](#and-cap-020--target-facing-typed-boundary)
- [AND-CAP-030](#and-cap-030--mutation-currentness-and-cleanup)
- [AND-CAP-040](#and-cap-040--android-deadlines-and-call-policy)
- [AND-CAP-050](#and-cap-050--domain-interfaces)
- [AND-CAP-060](#and-cap-060--exact-androidmetrics-verification)
- [AND-CAP-070](#and-cap-070--closed-alternatives)

## AND-MET-001 — Provider identity and collection ownership

The engine calls `observe()` exactly once on the exact configured provider. Null configuration creates and
attaches one Session-private `BuiltInCaptureMetricsDefinition` for the default-display policy. Public
`CaptureMetricsProviders.fromDisplay(context, display)` returns an immutable reusable definition fixed to the
exact supplied `Display` object and ID. Both retain a normalized application Context and its `DisplayManager`;
the explicit policy never substitutes another Display, including one with the same ID. The default policy may
select a different current `Display.DEFAULT_DISPLAY` object after the prior selection is removed. A custom
provider retains its exact identity and owns its Activity/window/dynamic-display policy.

Each built-in `observe()` returns a cold Flow. Direct, repeated, parallel, reused-definition, and cross-Session
collections have independent listener, conflated signal, authority fence, epoch-invalidated bit, selected epoch,
window-object cache, and unregister obligation. Collection starts no platform work before collection entry and
inherits no fact or completion from another collection.

On collection entry, the built-in registers one exact listener with the Handler, creates one
`Channel<Unit>(Channel.CONFLATED)`, seeds one token, and serially performs one complete read/emission per token.
One engine-private `Handler(Looper.getMainLooper())` routes every built-in `DisplayListener` callback on API
24–37. The Handler and process Main Looper are non-owned. Registration, reads, emission, signal close, and
unregister run serially in the Flow's upstream context; Session collection uses its distinct non-owned
`Dispatchers.IO.limitedParallelism(1)` metrics view. The callback only checks the collection fence and selected
ID, sets the sticky boundary bit for add/remove, and performs a conflated `trySend(Unit)`; it makes no read,
publication, lifecycle decision, or platform mutation.

## AND-MET-010 — Exact API-band reads and authority

One complete built-in read captures the exact selected Display and its continuous-validity epoch, verifies
`Display.isValid` before and after all field reads, produces width/height/density from that one read, then verifies
the same selection, epoch, validity, and sticky boundary before publication. Fields from different reads are
never combined.

| API band | Width/height read | Density read | Lifetime |
| --- | --- | --- | --- |
| 24–29 | `epochDisplay.getRealSize(Point)` | Fresh transient `applicationContext.createDisplayContext(epochDisplay)` and its Configuration | Both are repeated for every complete read; the transient Context is not retained. |
| 30 | `maximumWindowMetrics.bounds` from one epoch-cached WindowContext/`WindowManager` | Fresh transient display Context per complete read | Build the epoch WindowContext from that epoch's display Context via `createWindowContext(TYPE_APPLICATION, null)`. |
| 31–37 | Same maximum-window bounds | Fresh transient display Context per complete read | Build the epoch WindowContext with `applicationContext.createWindowContext(epochDisplay, TYPE_APPLICATION, null)`. |

The WindowContext/`WindowManager` pair exists once per continuous valid-display epoch. Ordinary selected-ID
change preserves it and requests a complete reread. Removal, detected invalidity, or remove/add ends the epoch
and drops the pair. Frame production performs zero metrics platform reads.

The API-band width/height, density, visibility, and provisional/admission authority is owned by
[PROD-020](02-product-contract.md#prod-020--configuration-and-metrics). This leaf supplies only the exact
platform reads above, continuous-validity epochs, and validated facts. Its metrics Display neither selects nor
verifies the MediaProjection capture source.

## AND-MET-011 — Epoch invalidation and latest geometry

Each collection token begins with the only consuming exchange:

```text
boundary = epochInvalidated.getAndSet(false)
```

When true, that token performs only a boundary pass: retire the current epoch and cached window pair, publish
unavailable once in latest-value state, queue one conflated recovery token, and do not resolve or install an
epoch. Installation occurs only on a later token and never clears `epochInvalidated`.

A read/recovery token resolves its policy's selected nonnull valid Display and, on API 30+, constructs the epoch
window pair before installation. Final validation uses a nonconsuming sticky-bit read. A changed selection,
invalid Display, or set boundary suppresses the complete candidate, retires the epoch, publishes unavailable
idempotently, and preserves one recovery wake. A boundary set after successful final validation remains sticky
and drives a later boundary pass. An ordinary `onDisplayChanged` only adds a reread token; if it races a valid
read, that candidate may publish and the pending token then rereads. At most one read and one pending refresh
exist, including storms.

Provider and projection facts update only the fields owned by the API table and publish one immutable combined
tuple or availability fact. [CTRL-100](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities)
alone assigns `geometryGeneration`; [CTRL-110](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision)
and [CTRL-130](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration) own latest-cell
claim, supersession, suspension, terminal classification, and rebuild selection.

## AND-MET-020 — Structured metrics lifetime

Accepted startup precreates the readiness occurrence, owner bag, empty Flow-return cell, entry/outcome cells,
parent-completion cell, one plain `parentJob = Job()`, one
`CoroutineScope(parentJob + metricsIoView)`, and exactly one
`launch(start = CoroutineStart.LAZY)` child. Child and parent completion handlers are attached before the
provider/scope/child/readiness `S,D` transfer under `sessionGate -> settlementGate`; `child.start()` runs after
unlock.

The child calls `observe()` once. It publishes the raw returned reference into the preattached Flow cell before
rechecking active versus cleanup authority. A null interop return is unusable; a nonnull Flow is retained with
the provider until final parent completion. Cleanup winning before collection entry prevents entry but does not
erase the returned reference.

`observe()` and `collect` have separate `catch (Throwable)` boundaries. On caught `CancellationException`,
`currentCoroutineContext().ensureActive()` distinguishes provider failure while the Job remains active from
terminal mechanics cancellation. Provider failure retains the original throwable as the `InternalFailure`
cause. Other throws receive the same classification. Normal completion or classified failure publishes one
fact and lets the child exit normally.

The child completion handler only and unconditionally calls `parentJob.complete()`; the parent completion handler only fills the
precreated completion cell and issues the nonblocking lossless controller wake. Both are fast, allocation-free,
nonthrowing, thread-safe, acquire no engine gate, and perform no outward call or lifecycle decision. Terminal calls
`parentJob.cancel()` outside gates. Final parent completion is the sole metrics-lifecycle receipt, including
cancel-before-entry; first valid metrics is readiness, not release. An entered nonreturn transfers the exact
parent/scope/child/provider/Flow/cells and collection dependencies under [CORE-CLEAN-1](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph).

## AND-MET-021 — Provider mechanical outcome partition

| Mechanical outcome | Exact outbound fact/ownership route |
| --- | --- |
| first valid positive tuple settles with `T < D` | readiness success plus the complete tuple; collection remains owned and active |
| normal completion before any valid tuple | completion-before-readiness fact |
| first-readiness expiry | readiness-expired fact with its fixed cause; collection ownership remains mechanical |
| `observe()` throw, null interop Flow, or collection throw | provider-failure fact with the raw cause when present and readiness phase |
| required tuple becomes null/invalid | availability-loss fact with readiness/Active phase and raw nullable cause |
| normal completion after readiness | completion-after-readiness fact; last valid tuple remains mechanically available |
| later valid tuple while nonterminal | one new complete combined tuple fact |
| result after active-to-cleanup transfer | reduce only the same metrics residue; publish no geometry or lifecycle fact |

The completion-after-readiness fact requests exactly one diagnostic for that matching provider lifetime
completion: source `MetricsProvider`, label `CapabilityCheck`, and `cause = null`. Completion before readiness
and provider failure do not create this post-readiness completion request. The request is routed through
[CTRL-300](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules), which retains diagnostic
sequence authority, and [DEL-OBS-020](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission)
for unlocked construction/emission.

[PROD-020](02-product-contract.md#prod-020--configuration-and-metrics) owns visible provider outcomes;
[CTRL-030](05-domain-controller-reconciliation.md#ctrl-030--lifecycle-and-terminal-application) and
[CTRL-130](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration) apply them.
A positive tuple incompatible with region/crop is routed to controller geometry validation, not reclassified by
this domain.

## AND-MET-030 — Built-in close and residue

Collection close first fences authority and closes the signal, then attempts exact
`unregisterDisplayListener(listener)` on the upstream/metrics-IO view. Registration failure before the first
value is `InternalFailure` and still attempts unregister. Normal unregister plus collection-body completion can
advance the parent receipt. A returned unregister throw settles that attempt and permits remaining collection
cleanup; nonreturn prevents child/final-parent completion. The retained residue is limited to that collection's
listener, `DisplayManager`, Main-Handler reference, provider/Flow/lifecycle cells, and dependencies still needed
by the entered work. It excludes the Session Android Handler/HandlerThread and all other collections/Sessions.

A Main-queue callback whose framework snapshot predates close/unregister may enter after a normal unregister or
after Session Android-thread shutdown. The fence makes it the same O(1) inert callback: zero publication, wake,
read, and lifecycle calls. A blocking metrics read is identity/domain-fenced; its late return is cleanup-only.

## AND-CAP-001 — Android lane and startup sequence

Each accepted Session owns one `HandlerThread` and `Handler`. `AndroidCaptureOwner` alone uses that Handler for
every `MediaProjection` callback registration/unregistration and callback, VirtualDisplay create/resize/
setSurface/release, target-listener Android-side call, and projection stop. Callback bodies carry the Session
epoch and post immutable facts; they do not mutate controller, Target, or GL state.

After the winning start commit in
[CTRL-020](05-domain-controller-reconciliation.md#ctrl-020--public-command-application), this domain settles its
startup prerequisites in this order:

1. attach metrics and obtain one timely valid tuple;
2. register and acknowledge one `MediaProjection.Callback` on `androidHandler`;
3. receive one installed CurrentTarget and call the sole VirtualDisplay creation boundary;
4. on API 34–37 obtain one timely valid captured-content resize and offer its geometry before domain readiness;
5. publish complete domain-readiness facts for controller validation and Running application.

VirtualDisplay creation uses the current valid `W,H,D` under the API-band geometry and provisional/admission
policy in [PROD-020](02-product-contract.md#prod-020--configuration-and-metrics). On API 34–37 the Android lane
settles the first resize before step 5. Initial-resize expiry publishes its fixed cause and
`MediaProjection/CapabilityCheck` diagnostic request; [CTRL-030](05-domain-controller-reconciliation.md#ctrl-030--lifecycle-and-terminal-application)
owns the startup result. An earlier resize retains its actual settlement time even if VirtualDisplay publication
occurs later.

## AND-CAP-010 — Projection and VirtualDisplay calls

All calls below are typed [CORE-SET-1](03-shared-runtime.md#core-set-1--generic-operation-occurrence) occurrences.
"Once" means exactly once when its prerequisites admit that boundary and zero otherwise. Only a timely current
normal result is eligible for active application by
[CTRL-100](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities) and
[CTRL-130](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration); late facts are cleanup-only.

| Boundary | Exact call and cardinality | Timely result partition / receipt |
| --- | --- | --- |
| projection callback registration | Once, before display creation: `mediaProjection.registerCallback(callback, androidHandler)` | Normal return is the startup registration receipt; throw is `InternalFailure`. |
| VirtualDisplay creation | Once: `mediaProjection.createVirtualDisplay("ScreenCaptureEngine", W, H, D, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)` | Nonnull adopts the sole display owner; null or `SecurityException` is `CaptureUnavailable`; direct `OutOfMemoryError` is `ResourceExhausted`; `IllegalStateException` or any other throwable is `InternalFailure`. A late nonnull owner is adopted for cleanup and must be released. |
| density/size mutation | `virtualDisplay.resize(W,H,D)` once per admitted mutation occurrence | Normal return is the resize receipt; throw or ambiguity is `InternalFailure`. |
| target attach/detach | `virtualDisplay.setSurface(surface)` / `virtualDisplay.setSurface(null)` once per admitted occurrence | Normal attach/detach publishes the exact platform result consumed by `TGT-040`; throw/timeout supplies no producer, no-producer, or detach proof. |
| callback unregister | At most once in cleanup: `mediaProjection.unregisterCallback(callback)` | Normal return releases callback registration. Returned throw is recorded and cleanup continues to display release; nonreturn blocks this serial suffix. |
| display release | At most once in cleanup: `virtualDisplay.release()` | Normal return releases the display and, where applicable, is eligible for the matching `TGT-040` detach receipt. Returned throw is recorded and cleanup continues to projection stop; nonreturn blocks the suffix. |
| projection stop | At most once after display cleanup outcome: `mediaProjection.stop()` | Normal return releases projection ownership. It creates no `CaptureEnded` fact. |

The VirtualDisplay always retains authoritative logical `W x H` and density `D`, uses automatic-mirroring, and
passes a nonnull Target Surface with null VirtualDisplay callback and Handler. The flag is a request, not a
receipt. [CTRL-110](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision) selects the plan;
[TGT-001](07-domain-target.md#tgt-001--files-and-private-declarations) carries its Full or API-32+ eligible
exact-aspect Downscaled target dimensions;
its physical buffer size never changes the VirtualDisplay logical dimensions or density. For an eligible smaller
exact-aspect Surface on API 32–37, Android's documented media-projection behavior uniformly fits and centers the
logical content into the full target bounds; the engine infers no content rectangle. `VirtualDisplay.setRotation`
and `ImageReader` are outside the V1 capture path.

`MediaProjection.Callback.onStop` is the sole platform callback authority for `CaptureEnded`. On API 34–37 the
same registered callback supplies positive resize facts and visibility facts. Every callback is Session-epoch
fenced; resize/visibility consumption and their readiness state exist only on API 34–37. Stale or terminal facts
are cleanup-only. Explicit `VirtualDisplay.release()` and `mediaProjection.stop()`
are cleanup operations and cannot fabricate callback/lifecycle facts.

## AND-CAP-020 — Target-facing typed boundary

This domain consumes only the inaccessible installed-Target interfaces in
[TGT-010](07-domain-target.md#tgt-010--typed-boundary-map) and
[TGT-030](07-domain-target.md#tgt-030--currenttarget-ports-leases-and-provenance); it never constructs a
`PreparedTarget`/`CurrentTarget` or creates Target authority.

| Android operation | Inbound Target authority | Android output to Target/controller |
| --- | --- | --- |
| listener install/remove | registered one-operation listener port bound to CurrentTarget, target generation, operation identity/kind, and provenance | exact platform settlement and sentinel-post/observation facts for `TGT-050` |
| display create/attach | registered counted one-shot Android Surface port/lease with the same bindings | exact platform result offered to `TGT-040` after complete settlement |
| display detach/release | registered one-shot detach port | exact normal/throw result offered to `TGT-040` |

Each Android occurrence owner bag retains the exact Target port and provenance it received until settlement and
invokes only that operation. Port binding, raw-handle exposure, lease accounting, and uninstalled-Target exclusion
remain solely [TGT-020](07-domain-target.md#tgt-020--preparation-and-single-disposition) and
[TGT-030](07-domain-target.md#tgt-030--currenttarget-ports-leases-and-provenance). After complete
platform settlement, Android offers the matching physical result to
[TGT-040](07-domain-target.md#tgt-040--producer-evidence); it neither synthesizes nor interprets producer authority.

Android executes listener installation before producer attach and, on removal, posts the same-Handler sentinel
before publishing the physical return fact. [TGT-050](07-domain-target.md#tgt-050--listener-source-bit-and-generation-fence)
alone owns the sentinel/generation meaning; [TGT-060](07-domain-target.md#tgt-060--mutually-exclusive-retirement-readiness)
and [TGT-070](07-domain-target.md#tgt-070--surface-and-targetscope-destruction) own release readiness and the
Surface chain. `Surface.release()` executes through [GL-070](08-domain-gl.md#gl-070--destruction-namespace-poison-and-cleanup-suffix);
this domain owns only the listed Android Handler calls and their physical outcomes.

## AND-CAP-030 — Mutation, currentness, and cleanup

After [CTRL-110](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision) closes/drains the
density-only scope, Android performs one current resize and offers its physical completion fact; controller owns
cache, retention, and reopened admission. A dimension or target-health change follows
[CTRL-120](05-domain-controller-reconciliation.md#ctrl-120--destructive-transition-order): detach and retire the
old Target before replacement. Android publishes only the matching typed physical facts;
[CTRL-100](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities),
[CTRL-120](05-domain-controller-reconciliation.md#ctrl-120--destructive-transition-order), and
[CTRL-130](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration) own currentness,
stale disposition, replacement, fallback, and terminal application.

Local Android cleanup order is fixed:

```text
projection callback unregister -> VirtualDisplay release -> MediaProjection stop
```

A returned throw is recorded and the next reachable step is attempted; nonreturn blocks only its serial suffix.
Target release remains the separate dependency graph in
[TGT-060](07-domain-target.md#tgt-060--mutually-exclusive-retirement-readiness),
[TGT-070](07-domain-target.md#tgt-070--surface-and-targetscope-destruction), and
[TGT-090](07-domain-target.md#tgt-090--successor-admission-cleanup-and-nonreturn), so unrelated cleanup roots, metrics
unregister, Android-thread shutdown eligibility, and other Sessions progress whenever their own prerequisites
permit. `AndroidCaptureOwner` calls `HandlerThread.quitSafely()` only after every Android-owned operation,
callback, Target Android-side dependency, and cleanup suffix settles. Its thread `finally` publishes the sole
lane-return cell. Lane shutdown and every terminal-origin or terminal-converted cleanup call have no watchdog.

## AND-CAP-040 — Android deadlines and call policy

These private `Long` nanosecond values are implementation policy, not public SLAs. They use
`EngineClock`, checked `D = Math.addExact(S,C)`, and the exact `T < D` rule in
[CORE-SET-3](03-shared-runtime.md#core-set-3--finite-deadline-arbitration).

| Constant | Value | One occurrence |
| --- | ---: | --- |
| `firstMetricsReadinessNanos` | `5_000_000_000L` | Accepted metrics attachment through first valid positive tuple. Long-lived collection and final parent completion have no readiness watchdog. |
| `initialCapturedResizeReadinessNanos` | `5_000_000_000L` | API 34–37 only, from atomic publication of `S,D` with the nonnull VirtualDisplay owner through first valid resize. API 24–33 create zero such occurrence. |
| `androidEnteredOperationSafetyNanos` | `5_000_000_000L` | One preterminal projection registration, VirtualDisplay create/resize/setSurface, target-listener install/remove, or typed Surface-release occurrence after its prerequisites. GL-070 executes the Surface call; this row owns only its deadline and result-classification policy. It is never one window for a compound sequence. |

For an occurrence governed by this policy, timely normal/throw keeps its exact boundary classification. Expiry
or late return is `InternalFailure` when it wins active arbitration; a real late receipt may advance only
physical cleanup. Terminal-before-entry converts a mandatory call to the same one-shot no-watchdog cleanup;
terminal after entry retires the deadline and transfers the intact occurrence. Queued work has no start
watchdog, scheduler nonprogress is not a return, and no boundary is retried.

When an active timeout wins, diagnostic source is `MediaProjection` for callback registration/create,
`VirtualDisplay` for resize/setSurface, and `SurfaceTarget` for Target listener/Surface calls; label is
`CapabilityCheck` and the request carries the fixed cause. [CTRL-300](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules)
owns acceptance/ordering, including any `SessionTerminal` request, and
[DEL-OBS-020](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission) owns construction/emission.
A higher-priority terminal winner leaves the timeout cleanup-only.

## AND-CAP-050 — Domain interfaces

| Direction | Typed interface/fact | Authority/use |
| --- | --- | --- |
| product -> metrics | exact `CaptureMetricsProvider`, built-in policy, nullable Flow values | `PROD-020` defines public meaning; `AND-MET-*` owns collection mechanics. |
| metrics -> controller | provider outcome, complete combined tuple, final-parent receipt | `CTRL-030`, `CTRL-100`, and `CTRL-130` own lifecycle/currentness/application. |
| Target -> Android | installed CurrentTarget Android-operation port/lease and provenance | `TGT-010`/`TGT-030` own identity, exposure, and lease authority. |
| Android -> Target | physical listener settlement/sentinel, producer-call result, detach result | `TGT-040`/`TGT-050`/`TGT-060` alone interpret matching Target evidence. |
| Android -> controller | callback registration/create/mutation facts, resize/visibility/stop facts, Android lane receipt | `CTRL-030`/`CTRL-100`/`CTRL-130`/`CTRL-300` arbitrate; this leaf owns physical result classification. |
| cleanup -> Android/metrics | intact occurrence/owner bag in Cleanup domain | `CORE-CLEAN-1`, `CORE-CLEAN-2`, and `CTRL-400` own transfer; this leaf owns local suffix and residue. |

## AND-CAP-060 — Exact Android/metrics verification

Closed packet membership is in [router §5](01-authority-router.md#5-closed-implementation-packets); shared
closure/routing and test namespaces are in [Document 04](04-verification.md). The matrix below retains only
Android/metrics-specific proof.

`A-CAP` injects SDK 24, 29, 30, 33, 34, and 37 facts through production adapter seams; this is an API-branch
matrix, not six devices. The complete required vectors are:

1. Factory/identity: null default present/missing/remove/same-ID replacement/add; explicit exact Display
   invalid/recover/same-ID-other-object/unrelated changes; custom identity. Assert one Session `observe()` call,
   independent cold collections, normalized Context, and zero public default factory.
2. Reads: exact API-band calls, one epoch WindowContext pair, one fresh density Context per read, complete-tuple
   publication, zero frame-path reads, and no guarded post-24 API call outside its branch.
3. Epoch races: boundary before opening exchange; after exchange/between every read/before install; immediately
   after final validation; recovery; ordinary change during read; callback storm. Assert no mixed tuple, erased
   boundary, duplicate epoch pair, or unbounded pending work.
4. Structured lifetime: cancel before lazy entry; child entry versus cancel; observe return versus cleanup;
   normal completion before/after readiness; active provider `CancellationException` versus parent cancellation;
   other throw; getter/collect/read/unregister nonreturn; late parent completion. Hold each engine gate while
   invoking completion handlers and assert their bounded behavior. Normal completion after readiness requests
   exactly one `MetricsProvider`/`CapabilityCheck` event with null cause for that provider lifetime; completion
   before readiness and provider failure request no duplicate post-readiness completion event.
5. Projection: exact registration order/Handler and sole create arguments; null, SecurityException, direct OOM,
   IllegalStateException, unexpected throw, nonreturn, and late owner; API 34–37 resize-before-display-return,
   readiness `D-1/D/D+1`, missing resize, runtime resize, visibility, onStop, stale epoch, and terminal facts.
6. Finite-call policy: each governed call at `D-1`, `D`, `D+1`, empty-at-D, rejection, throw, nonreturn,
   terminal-before/after-entry, and late return. Assert one call, exact receipt, current-only application, and no
   fabricated lifecycle/producer fact. For Surface release this row verifies only the `AND-CAP-040` deadline and
   result partition; `A-GL` verifies its GL-lane submission and invocation.
7. Cleanup/isolation: callback fence before signal close/unregister, captured late Main callback after unregister
   and after Android-thread shutdown, unregister throw/nonreturn, each Android suffix throw/nonreturn, lane
   completion, independent roots, and another Session.
8. Target integration: only installed CurrentTarget registered ports/leases reach Android calls; exact
   identity/provenance and one-shot counts; safe no-producer partition; detach receipt; listener sentinel;
   uninstalled Target has zero Android calls. Full release-chain acceptance remains in
   [TGT-100](07-domain-target.md#tgt-100--executable-obligations).

Tests use deterministic barriers and injected elapsed time, never sleeps or log parsing. They assert no Handler
object identity beyond Main-Looper routing, no physical metrics-IO thread identity, and no device matrix. This
domain introduces no image vector or numeric tolerance.

## AND-CAP-070 — Closed alternatives

- no second Android lane, per-call thread, Target listener on the metrics Main Handler, or built-in metrics
  callback on the Session HandlerThread;
- no `DisplayMetrics` snapshot, Resources cache, WindowMetricsCalculator policy, per-change WindowContext,
  cross-read tuple merge, event queue, parallel read, or frame-path metrics polling;
- no implicit Activity/window following in `fromDisplay`, Display-ID substitution for its exact Display, or use
  of metrics Display as capture-source proof;
- no provisional API 34–37 frame, visibility-driven policy, VirtualDisplay callback, `ImageReader`, rotation
  mutation, duplicate VirtualDisplay, or lifecycle inference from explicit release/stop;
- no Android access outside [TGT-030](07-domain-target.md#tgt-030--currenttarget-ports-leases-and-provenance) or
  interpretation outside [TGT-040](07-domain-target.md#tgt-040--producer-evidence); Target-specific forbidden
  alternatives remain solely [TGT-098](07-domain-target.md#tgt-098--forbidden-alternatives);
- no cancellation intent, first tuple, deadline expiry, thread quit request, or dropped reference treated as a
  mechanical completion receipt.

## Official platform references

- [Media projection guide](https://developer.android.com/media/grow/media-projection),
  [MediaProjection](https://developer.android.com/reference/android/media/projection/MediaProjection),
  [MediaProjection.Callback](https://developer.android.com/reference/android/media/projection/MediaProjection.Callback),
  and [VirtualDisplay](https://developer.android.com/reference/android/hardware/display/VirtualDisplay)
- [Android 12L projection scaling](https://developer.android.com/about/versions/12/12L/summary#media-projection),
  [Android 14 projection changes](https://developer.android.com/about/versions/14/behavior-changes-14#media-projection),
  [Android 15 projection changes](https://developer.android.com/about/versions/15/behavior-changes-all#media-projection-status-bar-chip),
  [media-projection foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection),
  and [foreground-service launch restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager),
  [Display](https://developer.android.com/reference/android/view/Display),
  [Context.createDisplayContext](https://developer.android.com/reference/android/content/Context#createDisplayContext(android.view.Display)),
  and [Context.createWindowContext](https://developer.android.com/reference/android/content/Context#createWindowContext(android.view.Display,int,android.os.Bundle))
- [Surface](https://developer.android.com/reference/android/view/Surface),
  [SurfaceTexture](https://developer.android.com/reference/android/graphics/SurfaceTexture),
  and [Handler](https://developer.android.com/reference/android/os/Handler)
- [CoroutineScope](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-scope/),
  [CoroutineStart](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-start/),
  [Job](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-job/),
  and [coroutine cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html)
