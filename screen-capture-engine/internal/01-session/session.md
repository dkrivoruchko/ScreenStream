# Session lifecycle and configuration

The public [Architecture](../../docs/architecture.md#session-lifecycle) explains the run model; [Usage](../../docs/usage.md#run-a-capture-session) defines caller operations, cancellation, and recovery. This page extends that foundation with the exact admission, bootstrap, topology, and terminal decisions used by maintainers. [Coordination](coordination.md) owns locking, result correlation, and publication mechanics; [Production](production.md) owns frame progression and accounting.

## Contents

- [Session responsibilities](#session-responsibilities)
- [Factory and start admission](#factory-and-start-admission)
- [Bootstrap and first Control entry](#bootstrap-and-first-control-entry)
- [First Active and start settlement](#first-active-and-start-settlement)
- [Configuration and geometry adoption](#configuration-and-geometry-adoption)
- [Recovery](#recovery)
- [Control-turn order](#control-turn-order)
- [Stable problem mapping](#stable-problem-mapping)
- [Stale results and owner health](#stale-results-and-owner-health)
- [Exception and cancellation boundaries](#exception-and-cancellation-boundaries)
- [Terminal contenders, priority, and claim](#terminal-contenders-priority-and-claim)
- [Retirement and later sessions](#retirement-and-later-sessions)
- [Implementation and verification](#implementation-and-verification)

## Session responsibilities

`SessionLifecycle` owns start admission, first-Active eligibility, running/paused production admission, terminal contenders, and start settlement. `SessionTopology` owns the newest accepted parameters, the reconciled desired parameters, revisions, Metrics and captured geometry, Capture/Encoding convergence, applied and historical output information, suspension, and visibility.

Coordinator joins those owners with [Production](production.md) and [Session Delivery](../03-output/delivery.md#consumer-replacement-and-unregister). It authorizes physical effects through Links and reserves public values. Capture, Encoding, Delivery, and Metrics retain their resource obligations; choosing a session outcome does not settle those obligations.

## Factory and start admission

`ScreenCaptureEngine.createSession()` selects the Metrics source, constructs Coordinator and the public session, and finally adopts the accepted projection into `BootstrapOwnership`. Fallible construction precedes that adoption, so a throwing factory leaves the projection with the caller. With an explicit source, the factory retains its identity and does not access or retain the supplied context; with no source, it constructs the built-in default-display source. [Usage](../../docs/usage.md#select-capture-metrics) owns the caller-facing selection rules.

Successful return establishes continuous session ownership without starting capture. `start()` selects initial parameters and transfers no projection. Stop before start or before first Control entry must still arrange retirement of that owned projection.

The start path checks the caller's coroutine cancellation before clock sampling and again under admission locks. Before acceptance, observed cancellation requests stop only if this session is still eligible for a fresh start. A losing or otherwise unaccepted start invocation must not stop another invocation's accepted run. A valid second start fails its one-start admission check.

The caller path samples elapsed realtime and computes the ten-second deadline with checked addition. Under `publicationGate → sessionGate`, Lifecycle accepts start, Topology installs revision 1 and the initial parameters, and Coordinator reserves `Starting`. Flow assignment occurs unlocked. If its settlement still permits ordinary work, Coordinator arms the startup check and dispatches Bootstrap, then awaits the start outcome. Clock/checked-deadline failure before acceptance is an operation failure; it is not evidence that capture began or that projection ownership returned to the caller.

Parameter updates require an installed Control executor, Lifecycle's `Running` phase, and no terminal contender. First-Active reservation enters that phase before its public assignment, so an update can be admitted while the latest Flow value is still `Starting`. The state snapshot does not perform admission. Accepted updates store pending intent and pause production synchronously; they do not wait for reconciliation or publication.

## Bootstrap and first Control entry

`SessionBootstrap` builds the fixed graph on the shared non-inline worker. `BootstrapOwnership` retains the projection and every constructed, untransferred Control/Capture lane throughout these steps:

1. Construct the Control thread, record it, claim its start through Bootstrap ownership, start it, and obtain its Looper and Handler.
2. Do the corresponding construction for Capture, checking cutoff between potentially blocking steps.
3. Construct the executor, Capture owner and Link, Metrics owner, Encoding Link, and Delivery Link.
4. Retain the exact first Control runnable before posting it.
5. On that runnable's real entry, check startup time and cutoff, adopt the projection into Capture, install the graph, and commit the ownership transfer under the permitted gates.

A winning cutoff leaves untransferred resources with Bootstrap for retirement. The first task becomes inert; it does not install a replacement graph or acquire a second publication authority. A late lane-start or platform return still belongs to its exact retained ownership record.

Normally returned `true` from both shared-worker dispatch and first Control posting are separate readiness facts. Neither implies task entry or plan readiness; actual first entry can race the submission-return reports. First Active waits for both reports.

Definite worker rejection, first Control `post() == false`, or a contained Bootstrap failure offers `InternalFailure` through the existing pre-Control terminal arbitration. A known rejected post cannot be treated as an accepted task whose entry is still pending. It authorizes no retry, inline Control execution, or replacement lane. Accepted work retains the [progress limits](coordination.md#progress-limits).

## First Active and start settlement

After graph transfer, Coordinator attaches Metrics and opens Capture once. Before the first authoritative API 34+ captured-content resize, plan resolution uses positive Metrics dimensions and density solely for neutral provisional capture setup: full source, no requested crop/rotation/mirror, and bounded `1 × 1` renderer output. Requested geometry-dependent validation and Encoding preparation wait for authoritative dimensions. This setup cannot publish effective output or frames; its own physical allocation/open failures still apply. [Capture](../02-capture/capture.md#projection-and-virtualdisplay-lifecycle) owns those resources, and [image resolution](../02-capture/image-pipeline.md#output-sizing-and-addressability) owns the formulas.

The first Active candidate requires agreement between the exact immutable Metrics snapshot, resolved definitive topology, Capture readiness, Encoding readiness, and both Bootstrap acceptance facts. Positive Metrics reported before `subscribe()` returns are insufficient until the exact handle is adopted. A positive snapshot frozen by normal completion can remain ready while its close is pending; unavailable completed Metrics can fail startup without waiting for close. See [Metrics readiness](../02-capture/metrics.md#readiness-and-cross-component-flow) and its [failure/completion mapping](../02-capture/metrics.md#failure-and-completion-mapping).

The deadline window starts at the pre-admission elapsed-realtime sample. Current checks run when the delayed task enters, at first Control entry, during startup Control work, and immediately before first-Active reservation. A sample before the accepted-start sample is `InternalFailure`; a current sample at or beyond the deadline is `CaptureUnavailable`. The delayed task may rearm if it enters before expiry. An obsolete check is inert. Delayed scheduling rejection is a failure, not proof that its task entered.

First-Active reservation consumes the deadline and changes Lifecycle to `Running`, with start success still pending. It commits the applied topology and reserves `Active`. After unlocked Flow assignment, Coordinator rechecks ordinary admission, pending Control work, and the exact topology/Metrics evidence. Only a successful settlement opens production and issues the one success entitlement for `start()`.

If that settlement is invalidated, the assigned `Active` may already have been observed. Production stays paused and start remains pending for a later usable Active or terminal outcome. The first-Active deadline is not restarted. A recoverable pause is then possible even while `start()` remains pending; the boundary is first Active, not return from the caller's start operation. First Active requires neither a source frame nor JPEG delivery.

## Configuration and geometry adoption

Topology distinguishes three descriptions:

- **Pending:** the newest parameters/revision accepted by an update.
- **Desired:** the parameters/revision adopted by Control for reconciliation.
- **Applied/historical:** the currently public effective plan and the last applied output information retained after it becomes unavailable.

An unequal update allocates a checked revision and durably replaces pending intent before requesting Control. Intermediate updates may conflate. Control adopts the exact pending candidate, invalidates old revision output, pauses affected production, suppresses obsolete wakes, and reserves the appropriate paused state before physical convergence. Timing-only changes may preserve image-compatible cache; [Production](production.md#output-identity-and-cache-compatibility) owns the exact comparison and history-reset rules.

Metrics availability, density, or authoritative geometry changes can require a revision without a parameter update. On API 24–33, Metrics supplies dimensions and density. On API 34+, accepted captured-content resize supplies dimensions, while Metrics still supplies density and availability. Once resize is authoritative, a change only to Metrics width/height does not replace those dimensions. Metrics and resize revision adoption defer behind pending parameter ingress rather than overwriting it.

Resize callback arrival, Link storage, and Topology's staged resize are not yet the output-currentness cutoff. The atomic pending resize-revision commit adopts the geometry, pauses production, and invalidates affected output. Coordinator deliberately processes already-completed fresh output before this transaction: an old plan can still commit when it matches both pending intent and the applied plan. After the transaction, old-revision output cannot newly commit as current. This does not revoke a previously admitted callback or identify when source pixels were captured.

Convergence resolves the newest desired parameters against current geometry, dispatches one-shot Capture Open or an Apply as needed, and reconciles Encoding. Each result first matches its exact Link request; current readiness then depends on the desired revision and physical plan. Physically equivalent Capture plans can reuse resources even when raw parameter values differ. Applied output information still describes the actual desired configuration; it must not be reconstructed from a later request.

Visibility is informational. It is correlated to the adopted projection and published with the appropriate Active or paused state; it neither grants geometry authority nor itself pauses production. The public [requested/applied model](../../docs/architecture.md#requested-and-applied-output) explains how these internal distinctions appear to callers.

## Recovery

Before first Active, a current problem that prevents readiness fails startup. Once first Active has been committed, these current problems may suspend:

| Problem and evidence | Recoverable boundary |
| --- | --- |
| `InvalidRequest` | Current desired parameters and geometry cannot resolve valid output. |
| `CaptureUnavailable` | Required current Metrics or explicit source-unavailability evidence is unavailable. |
| `ResourceExhausted` | Checked RGBA addressability rejection before allocation/mutation, or deterministic Target-candidate denial with complete rollback preserving the healthy prior Target. |

Having no waiting source image is normal capture behavior; it does not establish unavailability or a timeout. Unsafe/ambiguous rollback, invalidated Capture ownership, unsupported color space, ownership inconsistency, and other required unsafe allocation/capacity failures are terminal. A problem name alone does not establish recoverability; the responsible owner's settlement evidence is required.

Suspension gates both resolution and convergence dispatch for its desired revision. A settled `ResourceDenied` Apply does not immediately retry the same demand. An unequal request, relevant geometry/availability revision, or eligible explicit equal resubmission reopens the normal convergence path. An equal resubmission allocates one new revision only when the settled current desired revision is suspended and no newer pending revision exists; it reuses the exact stored parameter object. Further equal calls while that reevaluation is pending are inert.

There is no separate recovery controller, retry timer, or loop. Persistent failure can suspend the new revision again. A retained healthy old Target establishes physical reuse/rollback capability, not permission to output for a suspended request. Current Capture and Encoding readiness must converge before a new Active settlement reopens production. [Usage](../../docs/usage.md#retry-suspended-capture) owns the caller retry procedure.

## Control-turn order

`runControlTurn` first checks startup expiry and any pending terminal outcome. Ordinary work then follows this order, with terminal checks between the major stages:

1. Attach Metrics if needed; consume pending parameters and Metrics.
2. Consume exact returned Link facts and perform pending unregister actions.
3. Stage resize/visibility signals, process completed fresh output, then adopt a pending resize revision.
4. Resolve/converge plans; reserve Active or visibility updates when eligible.
5. Offer cached-first delivery, then try fresh production.
6. Publish due changed statistics, handle terminal progress, and synchronize the pacing callback.

Link-fact selection prioritizes a returned Read, Open, Apply, Encoding reconcile, Encoding production, callback failure, then ready Delivery closure. Each bounded slot is correlated before policy is applied. Once terminal handling takes over, its narrower fact selection below applies; ordinary draining must not be assumed to continue.

## Stable problem mapping

`ScreenCaptureProblem` is the stable semantic result. Throwable details and diagnostic delivery are context, not another classifier.

| Problem | Internal semantic boundary |
| --- | --- |
| `InvalidRequest` | Current geometry and requested parameters cannot resolve a valid output plan. |
| `CaptureUnavailable` | Required Metrics or projection authority is unavailable, explicit source-unavailability evidence is current, or an entered current startup check reaches expiry. Mere absence of a source frame is not unavailability. |
| `ResourceExhausted` | A named deterministic capacity or required creation/allocation boundary safely denies the request. |
| `InternalFailure` | Platform, rendering/JPEG, dispatch, ownership, identity, arithmetic, or cleanup evidence is unsafe or inconsistent. |
| `UnsupportedColorSpace` | An API 33+ read reports exact Display P3 dataspace under the supported-input policy. Currentness determines its session consequence; the classification does not prove general SDR/sRGB conversion for other inputs. |

The physical owner supplies the typed outcome it can justify. Coordinator applies currentness and lifecycle policy after correlation; Runtime adds no generic catch-and-relay classifier. Exact [Capture classifiers](../02-capture/capture.md#gles-validity-and-failure-scope), [Encoding settlement](../03-output/encoding.md#failure-containment-and-retirement), [Metrics mapping](../02-capture/metrics.md#failure-and-completion-mapping), and [native wire rules](../03-output/native-abi.md#error-boundaries) remain authoritative at their boundaries.

## Stale results and owner health

An obsolete operation-local failure cannot suspend or fail the current desired configuration merely because it failed. It still settles its resources and may contribute [settled production accounting](production.md#accounting-before-terminal-freeze).

Owner-wide evidence has different scope. A settled Capture Open failure or Apply/Read failure marked `OwnerInvalidated` can offer terminal failure despite an obsolete request revision. Terminal priority and claim still govern whether that offer can affect the result. Healthy ownership cannot be inferred from revision currentness or a problem category.

A safely settled Native compressor rejection monotonically disables Native for the session even if its frame became stale. Its consumed result records the frame failure; while ordinary admission remains open, it invalidates affected Encoding readiness/cache and drives later reconciliation. It permits no same-frame retry. A stop-only admission change does not itself make otherwise-current successful work stale. After final freeze, late facts cannot change statistics, readiness, cache, or public state.

## Exception and cancellation boundaries

An owner catches `Exception` only where it can preserve its owned invariants and return an ordinary typed result. Only specifically named safe exhaustion boundaries may translate their exact allocation denial, including a named `OutOfMemoryError`, to `ResourceExhausted`. An arbitrary throwable type, message, identity, or cause graph does not establish one of those boundaries.

Other `Error` and non-`Exception` throwables propagate unchanged through ordinary Kotlin/JVM behavior. Minimal nonthrowing borrow revocation, fencing, or quarantine can protect an acquired resource, but is not full settlement: it cannot invent reporting, accounting, fallback, retry, or successor work. Runtime hosting may deliver an uncaught exception or retain a throwable in a Future; neither creates a session failure or cleanup receipt.

Preserve cancellation control flow at an owning coroutine's boundary and retain cleanup responsibility already acquired. Cancellation observed by an accepted start wait requests stop. Before acceptance, only the still-fresh invocation has that authority, as described under [admission](#factory-and-start-admission).

If requested stop or projection stop wins while start success has not been settled, the pending start outcome is cancellation without an invented problem. A real startup failure supplies `ScreenCaptureException` with its stable problem. The cancellation outcome can throw while the caller's Job remains active, analogous to [`Deferred.await()`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-deferred/await.html) observing a cancelled operation. The start path distinguishes this from cancellation of its owning caller before deciding to request stop.

An unrelated consumer or Metrics-source callback throwing `CancellationException` is not, by type alone, proof that the engine or waiting caller's Job was cancelled. Apply the callback's exception policy without swallowing actual coroutine cancellation. `Observer.onFailure(cause)` is opaque error-as-data, including when `cause` is an `Error`. The [coroutine cancellation/resource guidance](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html) does not return an already-owned projection to the caller.

Session termination does not cancel or fail independent [registration completion](../03-output/delivery.md#consumer-replacement-and-unregister). Cancelling one unregister waiter neither proves callback drain nor cancels the underlying completion obligation.

## Terminal contenders, priority, and claim

Lifecycle retains one upgradeable contender:

```text
ProjectionStopped > Requested > first Failed(problem)
```

Only an identity-matched projection-stop callback can offer `ProjectionStopped`. `Requested` comes from owner stop, including eligible start cancellation. The first failure retains its problem unless a higher-priority stop arrives before claim. A final `Stopped` result therefore does not prove no failure was offered earlier.

The first accepted contender immediately closes ordinary admission and pauses production. `stop()` requests that cutoff before awaiting completion; the nonawaiting `requestStop()` returns after the admission fence. The offer does not yet freeze priority, statistics, or public state. Coordinator reaches the irreversible claim as follows:

1. Outside session locks, Delivery retirement establishes an entry fence: queued callback work cannot newly enter, while an already-entered callback can return later or never return.
2. Handle recorded callback-failure/ready-closure facts and exact returned Capture reads that remain eligible before freeze. A previously selected Encoding result is also consumed by its ordinary result handler, even if stop arrives between selection and consumption. Terminal handling does not select an unselected Encoding result or drain every outstanding encode. [Production](production.md#accounting-before-terminal-freeze) owns exact sample membership.
3. With no ordinary publication in flight, prepare and revalidate Lifecycle, Topology, Production, and Delivery candidates under `publicationGate → sessionGate`. Prevalidate Capture/Encoding freeze, freeze session-facing Link correlation, commit the semantic owners, invalidate active topology, and record the sole claim.
4. Outside the gates, publish final Stats, attempt the optional terminal diagnostic, publish terminal State, settle pending start, signal terminal publication completion, settle eligible registration completion, remove the pacing callback, request Bootstrap/Metrics/Capture/Encoding retirement, and request Control quit.

Preparation performs no clock read, Android/codec call, callback, dispatch, Flow assignment, payload copy, I/O, cleanup, or wait. The terminal suffix has no alternate publisher; an uncontained throwable can interrupt it under the [shared exception rule](#exception-and-cancellation-boundaries). [Coordination](coordination.md#terminal-publication) defines its publication mechanics and diagnostic containment.

After claim, no contender or late fact can revise the outcome, final statistics, delivery admission, cache, pacing, or session Encoding readiness. Independently retained cutoff/callback-exit evidence can still complete unregister without reopening frozen session work. Before first Control entry, the same outcome/claim protocol uses pre-Control arbitration; there is no second session or publication authority.

## Retirement and later sessions

Session completion combines terminal publication, startup settlement, and projection retirement. `stop()` requests terminal cutoff, checks caller cancellation, then awaits terminal-publication completion, the existing shared startup outcome, and the shared projection-stop result. Waiting on startup separately is necessary: Active can reserve success before its off-gate completion, leaving a racing terminal with no startup settlement of its own. Normal return establishes assigned/frozen terminal values and closed ordinary production/delivery authority plus normally returned projection stop. A Failed run can stop normally. Cancellation cancels only one waiter; `requestStop()` exposes the cutoff alone for callers that cannot await.

Before ownership transfer, Bootstrap records the projection-stop return/failure. Rejected prefix cleanup fails that result only while an untransferred stop obligation is still unclaimed; irrelevant cleanup after transfer cannot fail Capture-owned completion. Capture records its at-most-once stop outcome and notifies before callback unregister and graphics cleanup. Android `onStop` supplies a terminal cause, not this stop-return evidence.

Ordinary required-dispatch or projection-stop failure fails the shared shutdown wait with `InternalFailure` without revising terminal State/Stats. Retained work may still enter later, but cannot retry the stop or upgrade a failed result. [Coordination](coordination.md#logical-stop-completion) owns completion signaling and its [progress limits](coordination.md#progress-limits).

Neither terminal publication nor successful `stop()` proves complete callback, graphics, encoding, or thread retirement. Each unresolved operation retains its exact resources until real return permits owner-local settlement; timeout, cancellation, or reference loss cannot create release evidence. Late returns may complete a registration but cannot publish a session successor. Bootstrap retains untransferred resources and physical owners retain adopted ones under their own dependency rules.

A later session has independent projection, revision, registration, and frame identities. Old work may overlap the new run but can settle only its own retained resources. The host coordinates any application-owned resources shared by old and new callbacks. See [Usage: stop a capture run](../../docs/usage.md#stop-a-capture-run) and [Architecture: resource release](../../docs/architecture.md#run-outcome-and-resource-release).

## Implementation and verification

- [SessionLifecycle](../../src/main/kotlin/io/screenstream/capture/internal/session/lifecycle/SessionLifecycle.kt), [SessionTopology](../../src/main/kotlin/io/screenstream/capture/internal/session/topology/SessionTopology.kt), and [SessionCoordinator](../../src/main/kotlin/io/screenstream/capture/internal/session/SessionCoordinator.kt): admission, readiness, convergence, and terminal decisions.
- [SessionBootstrap](../../src/main/kotlin/io/screenstream/capture/internal/session/SessionBootstrap.kt) and [BootstrapOwnership](../../src/main/kotlin/io/screenstream/capture/internal/session/BootstrapOwnership.kt): retained construction and transfer obligations.
- [Verification contracts](../04-testing/verification-contracts.md): `API-03`, `API-04`, `SES-01`–`SES-04`, `SES-07`, `SES-08`, `MET-03`, `BSP-01`–`BSP-05`, `DEL-02`, and `TERM-01` describe the relevant verification obligations.
