# Screen Capture Engine — Android Capture and Metrics Domain

This leaf owns built-in capture metrics, metrics observation, geometry-source arbitration, the Session Android
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
declares the public source/provider contracts and built-in source factories. `AND:CaptureMetricsContracts.kt` and
`AND:CaptureMetricsOwner.kt` own metrics facts and observation lifetime. `AND:AndroidCaptureContracts.kt` owns
capture evidence and occurrence bags; `AND:AndroidLane.kt` contains the single Session
`HandlerThread`/`Handler` runtime; `AND:MediaProjectionOperations.kt` and
`AND:VirtualDisplayOperations.kt` contain their named platform boundaries; and
`AND:AndroidCaptureOwner.kt` is the sole mutable domain facade. These files share one Android authority and do
not create an additional lane, gate, owner, or state machine.

## Navigation

- [AND-MET-001](#and-met-001--source-identity-and-observation-ownership)
- [AND-MET-010](#and-met-010--exact-api-band-reads-and-authority)
- [AND-MET-011](#and-met-011--epoch-invalidation-and-latest-geometry)
- [AND-MET-020](#and-met-020--provider-observation-and-active-ordering)
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

## AND-MET-001 — Source identity and observation ownership

Null configuration creates one Session-private built-in source for the default-display policy. Public
`CaptureMetricsSources.fromDisplay(context, display)` returns an immutable reusable built-in source fixed to the
exact supplied `Display` object and ID. Both retain a normalized application Context and its `DisplayManager`;
the explicit policy never substitutes another Display, including one with the same ID. The default policy may
select a different current `Display.DEFAULT_DISPLAY` object after the prior selection is removed. A configured
custom `CaptureMetricsProvider` retains its exact identity and owns its Activity/window/dynamic-display policy.

`CaptureMetricsProvider` is the externally implementable direct subtype of the sealed `CaptureMetricsSource`;
application implementations are therefore indirect source implementations. A built-in source is an
engine-defined direct subtype and cannot be confused with a custom provider by reflection, wrapper identity, or
factory provenance. The Session branches only on these closed source kinds and calls custom `subscribe` exactly
once.

Every built-in observation attachment creates an independent listener, coalesced-refresh bit, authority fence,
epoch-invalidated bit, selected epoch, window-object cache, and unregister obligation. Direct, repeated,
parallel, reused-source, and cross-Session observations inherit no fact or completion from one another.

One non-owned `Handler(Looper.getMainLooper())` routes built-in `DisplayListener` callbacks on API 24–37. A Main
callback performs only O(1), allocation-free fence/selected-ID checks, sets the sticky boundary bit for
add/remove when applicable, atomically changes the coalesced-refresh bit, and signals the Session Metrics lane.
It performs no Display/window read, application observer callback, publication, lifecycle decision, or platform
mutation. Registration, every complete read, and unregister execute serially on the prestarted Session-owned
Metrics endpoint defined by `CORE-EXEC-1`; at most one read and one coalesced pending refresh exist. The idle
refresh winner reserves the sole ticket; a callback during that ticket marks it dirty, and ticket settlement
submits one successor without requiring another source signal.

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

Each Metrics-lane refresh turn begins with the only consuming exchange:

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

Selected-source and projection facts update only the fields owned by the API table and publish one immutable combined
tuple or availability fact. [CTRL-100](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities)
alone assigns `geometryGeneration`; [CTRL-110](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision)
and [CTRL-130](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration) own latest-cell
claim, supersession, suspension, terminal classification, and rebuild selection.

The full authority key offered to that controller contains observation/source identity, selected Display and
continuous-validity epoch or projection owner, availability phase, and every authoritative field. Repeated
unavailable and an equal full key are no-ops after ingress consumption. First valid, availability transition,
new epoch/owner, or field change is real; unavailable followed by historically equal valid fields is recovery.

## AND-MET-020 — Provider observation and Active ordering

Accepted startup precreates the readiness occurrence, observer, bounded staging summary, handle-outcome/adoption
cell, close occurrence, owner bag, and attachment ticket, then submits that one ticket to the prestarted Metrics
endpoint under `CORE-EXEC-1`. After its Runnable commits `Entered`, the worker takes the attachment
`settlementGate`, samples `S = EngineClock.now()`, computes checked `D` from `AND-CAP-040`, and publishes both;
it releases the gate immediately before exactly one outward observation-start call. That call is custom
`provider.subscribe(observer)` or the built-in attach/register equivalent. Thus `S,D` are visible before any
inline callback. Observer calls may be synchronous before return, inline/reentrant, concurrent, or later on
arbitrary source-owned threads; no callback-thread ordering is assumed.

The bounded summary is sufficient and exhaustive: earliest validated positive value with ingress sequence,
sample time, and value; sticky first post-valid availability loss before first Active; latest nullable metrics
with sequence and value; first terminal kind/cause, sequence, and
`BeforeJointReadiness | AfterJointReadiness` phase; and the distinct handle-outcome/adoption cell. For each
contract-valid positive callback, the observer takes `sessionGate`, validates exact source/observation identity,
the open fence, and the value, reserves the next nonreused ingress sequence, samples `T = EngineClock.now()` in
that same critical section, and publishes the tuple before unlock. This internal clock sample is permitted while
no outward call, I/O, scheduling, or explicit wait occurs. Nullable and terminal callbacks use the same semantic
sequence authority without a readiness time sample. Sequence exhaustion fences ingress and publishes the
precreated `InternalFailure` before wrap or reuse. Each gated body is O(1), allocation-free, and nonthrowing for
contract-valid input; it may contend briefly on `sessionGate` and requests the lossless drain only after unlock.
`onComplete`/`onFailure` close ingress and the first terminal wins. Reported failure is source data, so its raw
cause is retained as `InternalFailure` regardless of subtype and is never thrown by the engine.

The controller folds summary cells by ingress sequence, not callback arrival, drain order, or sample-time order;
no event queue is needed. One-shot joint readiness commits under `sessionGate` only when the timely earliest
positive tuple (`T < D`) and a normally returned, nonnull, adopted exact close handle coexist before expiry and
before any prior availability loss or terminal occurrence. Handle outcome/adoption may occur after the positive
sample and is distinct from full attachment-ticket submission/Runnable-return settlement. Null handle or an
outward observation-start `Exception` while readiness is unresolved is authoritative `InternalFailure` over all
staged callbacks. Direct fatal follows `CORE-FATAL-1`; an already committed expiry or Session terminal remains
fixed. Nonreturn cannot be converted into success by staged callbacks.

Normal `onComplete` before joint readiness—including valid-before-handle—closes ingress and selects startup
`CaptureUnavailable` subject to the authoritative unresolved handle-outcome rule above; it requests no completion
diagnostic. If the observation-start call later returns a normal nonnull handle, the engine adopts it and makes
it the exact pending close obligation. Normal
`onComplete` after joint readiness, including after readiness but before first Active, closes ingress, retains
the last valid tuple, requests the one null-cause completion diagnostic, and does not prevent startup from
continuing. `onFailure` retains its ordinary pre-/post-readiness `InternalFailure` behavior. The first terminal
callback remains the only terminal occurrence.

Source ingress and first `Running(Active)` are ordered by the same `sessionGate`. Before Active, the controller
folds every represented earlier sequence and rechecks the observation fence, joint-readiness commit, sticky loss,
terminal phase, and latest tuple. After Active, the latest nullable cell and first terminal follow `PROD-020`.
Duplicate determination uses the full authority key in `CTRL-100`: a duplicate still consumes its ingress
sequence but produces no geometry/lifecycle generation, reconciliation, rebuild, State/Stats publication, or
diagnostic.

Observation completion, Session terminal, or another close trigger sets sticky `closePending/closeRequested`
under `sessionGate`; it does not immediately create a second Metrics ticket. The exact close ticket may be
submitted only after the attachment ticket has fully settled its submission, entry, and Runnable-return sides,
the exact handle is adopted, and the same endpoint remains healthy. `subscription.close()` or the built-in
equivalent then executes once on that endpoint outside all gates. Normal return is the exact close receipt.
Submission throw/endpoint poison, returned `Exception`, direct fatal, or nonreturn retains the handle, close
obligation, and exact dependency residue; no second endpoint, fabricated close, or duplicate ticket is allowed.
Terminal while attachment is unresolved transfers the attachment ticket, bounded summary, selected source,
observer, possible future handle, owner bag, and close obligation intact. Only exact fully settled attachment
evidence that no handle exists makes close structurally inapplicable. Metrics `terminated()` remains a distinct
endpoint/thread receipt.

## AND-MET-021 — Provider mechanical outcome partition

| Mechanical outcome | Exact outbound fact/ownership route |
| --- | --- |
| timely first valid positive tuple and its normal nonnull handle are jointly committed before prior loss/terminal/expiry | readiness success plus the complete tuple; full attachment-ticket settlement may be later, and observation remains owned |
| normal completion before joint readiness, including valid-before-handle | startup `CaptureUnavailable`; no completion diagnostic; a later normal nonnull handle is adopted for exact close |
| first-readiness expiry | readiness-expired fact with its fixed cause; observation ownership remains mechanical |
| observation-start throws `Exception` or returns a null interop handle while unresolved | authoritative source-observation `InternalFailure` over staged callbacks, with the raw cause when present |
| observer reports `onFailure` | source-observation failure with the raw cause and exact joint-readiness phase; first terminal wins |
| observation-start throws `Error` or another direct non-`Exception` throwable | exact raw fatal settlement and engine-boundary rethrow under `CORE-FATAL-1` |
| adopted-handle `close` throws `Exception` while still active | provider-close-failure fact with the raw cause; a prior terminal transfer remains authoritative |
| required tuple becomes null/invalid | availability-loss fact with readiness/Active phase and raw nullable cause |
| normal completion after joint readiness, including before first Active | one completion-after-readiness fact; ingress closes and last valid tuple remains mechanically available |
| later valid tuple while nonterminal | one new complete combined tuple fact |
| result after active-to-cleanup transfer | reduce only the same metrics residue; publish no geometry or lifecycle fact |

The completion-after-readiness fact requests exactly one normal observation-completion diagnostic for that
matching observation lifetime: source `MetricsProvider`, label `CapabilityCheck`, and `cause = null`. Pre-readiness
completion, provider failure, and duplicate terminal delivery create no such event; the one observation lifetime
can never request it twice. The request is routed through
[CTRL-300](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules), which retains diagnostic
sequence authority, and [DEL-OBS-020](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission)
for unlocked construction/emission.

[PROD-020](02-product-contract.md#prod-020--configuration-and-metrics) owns visible source-observation outcomes;
[CTRL-030](05-domain-controller-reconciliation.md#ctrl-030--lifecycle-and-terminal-application) and
[CTRL-130](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration) apply them.
A positive tuple incompatible with region/crop is routed to controller geometry validation, not reclassified by
this domain.

## AND-MET-030 — Built-in close and residue

Built-in close first fences Main-callback authority and clears refresh admission, then attempts exact
`unregisterDisplayListener(listener)` on the Metrics endpoint. Registration failure before the first value is
`InternalFailure` and still admits the matching unregister when registration may have escaped. Normal unregister
is the built-in observation-close receipt. A returned unregister throw settles that attempt and preserves its
failure; nonreturn retains the listener and prevents endpoint shutdown. The residue is limited to that
observation's listener, `DisplayManager`, Main-Handler reference, built-in source, epoch/window state, refresh
ticket, and dependencies still needed by entered work. It excludes the Session Android Handler/HandlerThread and
all other observations/Sessions.

A Main-queue callback whose framework snapshot predates close/unregister may enter after a normal unregister or
after Session Android-thread shutdown. The fence makes it the same O(1) inert callback: zero refresh submission,
read, publication, and lifecycle calls. A blocking metrics read is identity/domain-fenced; its late return is
cleanup-only.

## AND-CAP-001 — Android lane and startup sequence

Each accepted Session owns one `HandlerThread` and `Handler`. `AndroidCaptureOwner` alone uses that Handler for
every `MediaProjection` callback registration/unregistration and callback, VirtualDisplay create/resize/
setSurface/release, target-listener Android-side call, and projection stop. Callback bodies carry the Session
epoch and publish immutable facts; they do not mutate controller, Target, or GL state.

Every engine-originated Android Runnable has a typed post/entry ticket. `Handler.post(runnable) == true` is
acceptance only, not entry, execution, return, release, or thread-liveness receipt. `false` or an outward throw is
always recorded; entry wins the affected occurrence if its Runnable already committed entry, but any such active
post failure monotonically poisons the Android lane and initiates the allocation-free fail-close path. A thrown
`Exception` is `InternalFailure`; a direct `Error` or other non-`Exception` throwable follows `CORE-FATAL-1` and
is rethrown identically after settlement. A poisoned-lane Runnable can only settle inert. Framework callbacks
delivered through the registered Handler are callback occurrences, not engine `post` tickets, and do not consume
a private-executor ticket. The `CORE-EXEC-1` queue and one-outstanding-ticket rules do not apply to Android.

The HandlerThread outer run boundary owns a preallocated direct-fatal slot and a `finally` publication. It fences
new work and preserves/rethrows the identical fatal object after allocation-free settlement. `quitSafely()` is a
one-shot shutdown request only; it is neither acceptance of future work nor a thread-return receipt and may omit
future-due messages. The HandlerThread's actual `run` return through `finally` is the sole Android lane/thread
receipt.

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
| projection callback registration | Once, before display creation: `mediaProjection.registerCallback(callback, androidHandler)` | Normal return is the startup registration receipt; thrown `Exception` is `InternalFailure`; direct fatal follows `CORE-FATAL-1`. |
| VirtualDisplay creation | Once: `mediaProjection.createVirtualDisplay("ScreenCaptureEngine", W, H, D, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)` | Nonnull adopts the sole display owner; null or `SecurityException` is `CaptureUnavailable`; direct `OutOfMemoryError` is `ResourceExhausted`; `IllegalStateException` or another unexpected `Exception` is `InternalFailure`; other direct throwables follow `CORE-FATAL-1`. A late nonnull owner is adopted for cleanup and must be released. |
| density/size mutation | `virtualDisplay.resize(W,H,D)` once per admitted mutation occurrence | Normal return is the resize receipt; thrown `Exception` or ambiguity is `InternalFailure`; direct fatal follows `CORE-FATAL-1`. |
| target attach/detach | `virtualDisplay.setSurface(surface)` / `virtualDisplay.setSurface(null)` once per admitted occurrence | Normal attach/detach publishes the exact platform result consumed by `TGT-040`; throw/timeout supplies no producer, no-producer, or detach proof. |
| callback unregister | At most once in cleanup: `mediaProjection.unregisterCallback(callback)` | Normal return releases callback registration. Returned `Exception` is recorded and cleanup continues; direct fatal follows `CORE-FATAL-1`; nonreturn blocks this serial suffix. |
| display release | At most once in cleanup: `virtualDisplay.release()` | Normal return releases the display and may supply the matching `TGT-040` detach receipt. Returned `Exception` is recorded and cleanup continues; direct fatal follows `CORE-FATAL-1`; nonreturn blocks the suffix. |
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

A returned `Exception` is recorded and the next reachable step is attempted; direct fatal follows
`CORE-FATAL-1`, and nonreturn blocks only its serial suffix.
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
| `firstMetricsReadinessNanos` | `5_000_000_000L` | Under attachment `settlementGate`, arm `S,D` after attachment-Runnable entry and immediately before the one observation-start call. Joint readiness requires a positive observer sample `T < D` plus normal nonnull exact-handle adoption before prior loss/terminal/expiry. Long-lived observation and exact close have no readiness watchdog. |
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
| product -> metrics | exact `CaptureMetricsSource`, custom provider, built-in policy, and nullable observer values | `PROD-020` defines public meaning; `AND-MET-*` owns observation mechanics. |
| metrics -> controller | selected-source observation outcome, complete combined tuple, exact close receipt, and Metrics-endpoint termination receipt | `CTRL-030`, `CTRL-100`, and `CTRL-130` own lifecycle/currentness/application. |
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
   invalid/recover/same-ID-other-object/unrelated changes; custom identity. Assert one Session `subscribe` call,
   independent built-in observations, exact source/provider identity, normalized Context, and zero public default
   factory.
2. Reads: exact API-band calls, one epoch WindowContext pair, one fresh density Context per read, complete-tuple
   publication, zero frame-path reads, and no guarded post-24 API call outside its branch.
3. Epoch races: boundary before opening exchange; after exchange/between every read/before install; immediately
   after final validation; recovery; ordinary change during read; callback storm. Assert no mixed tuple, erased
   boundary, duplicate epoch pair, or unbounded pending work.
4. Observation lifetime: `S,D` publication before the exactly one custom/built-in observation-start call;
   inline/arbitrary-thread/concurrent callbacks before/during/after its return; semantic-sequence folding of the
   bounded earliest-positive/sticky-pre-Active-loss/latest-nullable/first-terminal-phase/handle-adoption summary;
   same-critical-section positive `T` sample/publication; ingress exhaustion before reuse; returned null, throw,
   nonreturn, expiry, or terminal never converted to success; `D-1/D/D+1` and concurrent reverse callback/drain
   order; earlier/later normal nonnull handle adoption; normal completion before joint readiness, including
   valid-before-handle, versus after joint readiness before/after first Active; late handle adoption and exact
   close; valid-loss-valid before Active remains startup unavailable; exact full-key duplicate/no-op and unavailable
   recovery; attachment submission/entry/Runnable-return settlement before one close ticket; closePending during
   attachment; poisoned/nonreturn residue with no second endpoint or fabricated close; callback versus first
   Active; raw fatal-slot identity, direct-Error thread-top identity, runtime-shaped custom-Throwable thread-top,
   and reported Error as data; late callback/return. Normal completion after joint readiness requests exactly one
   `MetricsProvider`/`CapabilityCheck` event with null cause for that observation lifetime; pre-readiness
   completion, provider failure, and duplicates request none.
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

Tests use deterministic barriers and injected elapsed time, never sleeps or log parsing. They assert only the
documented Main-Looper routing plus the dedicated Metrics endpoint/thread, and no device matrix. This domain
introduces no image vector or numeric tolerance.

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
- [HandlerThread](https://developer.android.com/reference/android/os/HandlerThread) and
  [Looper](https://developer.android.com/reference/android/os/Looper)
