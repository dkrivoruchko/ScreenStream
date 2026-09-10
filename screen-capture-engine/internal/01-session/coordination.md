# Coordination and publication

Read the public [Architecture](../../docs/architecture.md#execution-and-coordination) for the execution and ownership model and [Usage](../../docs/usage.md#monitor-capture) for observable behavior. This page defines the internal protocol for committing decisions, calling resource owners, and publishing values safely. [Session](session.md) owns lifecycle and outcome policy; [Production](production.md) owns frame and statistics policy.

## Contents

- [Responsibilities and dependency direction](#responsibilities-and-dependency-direction)
- [Identities and currentness](#identities-and-currentness)
- [Execution lanes](#execution-lanes)
- [Session gates](#session-gates)
- [Bounded ingress and Control turns](#bounded-ingress-and-control-turns)
- [Dispatch and completion evidence](#dispatch-and-completion-evidence)
- [Ordinary publication](#ordinary-publication)
- [Observation and diagnostics](#observation-and-diagnostics)
- [Terminal publication](#terminal-publication)
- [Logical stop completion](#logical-stop-completion)
- [Progress limits](#progress-limits)
- [Implementation and verification](#implementation-and-verification)

## Responsibilities and dependency direction

An internal component is an ownership and responsibility boundary; it need not coincide with one class, package, lock, or thread.

`SessionCoordinator` combines decisions from Lifecycle, Topology, Production, and Session Delivery with facts returned by physical owners. Each semantic owner keeps its own policy state. Coordinator correlates results, revalidates candidates, reserves publication, and authorizes effects; it must not mirror those owners' state in another semantic store.

The three Links separate physical results from session decisions:

| Link | Correlation it owns |
| --- | --- |
| `SessionCaptureLink` | Bounded Open, Apply, and Read requests; projection, Target, resize, visibility, source, and read-return facts. |
| `SessionEncodingLink` | Reconcile and production requests, including the exact carrier-loan and settlement phase. |
| `SessionDeliveryLink` | One handoff token installed before offer, its offer return, callback failure, and staged/ready closure. |

A Link uses bounded correlation slots. It does not choose lifecycle, currentness, statistics, terminal outcome, or successor work. Physical owners release their private synchronization before invoking typed return ports.

Public values are dependency roots. Session owners operate on immutable candidates and facts; Links use narrow physical-owner contracts. Physical owners may use Runtime mechanisms. Encoding, Production, Coordinator, and Delivery may use immutable Storage values. Runtime and Storage contain no session policy. Capture imports neither Encoding nor Storage and never calls Encoding directly: [the read bridge](production.md#read-bridge-and-production-progression) connects their two sides through Coordinator.

Physical resources remain with the owner that can establish when their use has ended. A session decision cannot transfer, release, or forget a resource merely because its output became obsolete. The normal interaction is:

```text
prepare an immutable candidate or record a physical result
→ correlate its exact request and recheck currentness
→ commit the responsible semantic owner's decision
→ publish or perform the physical effect outside the session gates
→ record the typed return and revalidate before further work
```

## Identities and currentness

Use each identity for its own boundary:

- `configRevision` is a positive, checked session-local configuration revision. Pending and reconciled desired revisions are distinct until Control adopts ingress.
- `registrationId` is a separate positive, checked consumer-registration identity.
- A `SessionProductionRecord` is an exact object carrying its Production owner, revision, and JPEG quality.
- Requests, return ports, projection and source identities, Metrics snapshots, input loans, payloads, frames, publication reservations, wake identities, and handoff tokens are correlated by object identity.

Configuration and registration identities cannot wrap or repeat; exhausted allocation makes no partial identity update and is classified as `InternalFailure`. Output sequence exhaustion has the same no-wrap requirement in [Production](production.md#output-identity-and-cache-compatibility). Diagnostic sequence exhaustion silently stops diagnostics. Internal candidate generations are separate implementation counters; these public/correlation exhaustion rules do not imply checked arithmetic for every counter in the implementation.

A matching return proves which request finished, not whether its output is useful. Coordinator also checks the relevant revision, desired/applied plan, owner health, admission, and candidate generation or reservation. Candidates are provisional: revalidate immediately before commitment. Value equality cannot replace object identity at a request/settlement boundary. Conversely, [cache compatibility](production.md#output-identity-and-cache-compatibility) and physical plan equivalence deliberately compare selected values.

Stale evidence still settles its exact resources. It may also contribute the explicitly defined [mechanical accounting](production.md#accounting-before-terminal-freeze) or owner-wide health evidence. In particular, stale Capture owner invalidation and a settled Native-health disable cannot be dismissed as ordinary obsolete output; [Session failure policy](session.md#stale-results-and-owner-health) defines their consequences. None of these exceptions permits relabeling old pixels with a new plan.

## Execution lanes

- Caller threads perform bounded validation, admission, and snapshot access. Heavy Capture, Encoding, callback, and cleanup work runs elsewhere.
- Bootstrap performs potentially blocking lane start and Looper acquisition on the shared non-inline worker.
- The Control `Handler` enters Coordinator turns. `SessionControlExecutor` owns Handler mechanics, not lifecycle, currentness, plans, schedules, statistics, or publication policy.
- The Capture `Handler` serializes projection, Target, EGL/GLES, readback, and Capture retirement.
- Encoding and Delivery use the shared non-inline worker through owner-local `SerialTaskSlot` instances. Metrics likewise coalesces its attachment, refresh, close, and notification work through its owner.
- Startup and pacing delayed tasks are scheduling mechanisms. Lifecycle or Production owns their semantic identity.

`MediaProjection.Callback` is hosted on the Control Handler but remains a Capture platform callback. Its entry is not a Coordinator turn and cannot directly change session semantics; it emits typed Capture evidence.

## Session gates

Coordinator permits this lock nesting:

```text
publicationGate → sessionGate
                    ├─ Metrics snapshot gate
                    ├─ BootstrapOwnership.gate
                    ├─ SessionCaptureOwner.ownerGate
                    └─ registration completion gate
```

`publicationGate` orders admission and publication reservations. `sessionGate` protects shared admission, currentness, terminal contenders, coalesced Control requests, and Link correlation. No path holding `sessionGate` may acquire `publicationGate`.

The nested private-gate exceptions are narrow:

- Metrics: a bounded immutable snapshot read.
- Bootstrap: bounded ownership transfer, lane-start admission, and cutoff bookkeeping.
- Capture: projection adoption and identity installation.
- Registration completion: bounded identity and callback-lifetime proof updates.

Metrics, Bootstrap, and Capture gates are acquired separately, never inside one another. While holding any of them, no path may acquire a session gate or perform an outward effect. The registration completion gate is also a sibling: it never acquires a session, publication, or physical-owner gate. Physical Delivery releases its owner gate before recording callback-return or queued-cutoff proof. Completion actions, semantic detach, and `CompletableDeferred` notification run after the completion gate is released.

Neither session gate encloses clock reads, Android or codec calls, callbacks, dispatch/Handler posts, payload copies, Flow assignment, cleanup, blocking, or waiting. Private proof bookkeeping must not introduce fallible reporting under a gate. Monitor release/acquire makes the committed state visible between the locked snapshot/commit and unlocked effect phases.

## Bounded ingress and Control turns

Ingress requiring deferred reconciliation first stores its bounded fact or intent, marks Control work pending, and requests the sole immediate Control wake after releasing synchronization. A pending or entered wake absorbs further writes. A racing ingress is either included in the entered turn or leaves a successor request; it must not disappear between consuming work and releasing the wake.

The bounded stores are:

- latest pending parameters and one current immutable Metrics snapshot;
- latest captured-content resize and visibility facts;
- one source-opportunity bit;
- one pending/fact slot for each bounded Capture or Encoding operation;
- one callback-failure fact and one staged/ready `Closed` fact for the handoff;
- one logical pacing wake; and
- one ordinary publication reservation.

There is no general event or frame queue. [Production](production.md) retains at most one materialized fresh production. [Delivery](../03-output/delivery.md) retains one unresolved semantic offer and one physical handoff; later eligible delivery opportunities count as busy instead of building a backlog.

Control may consume Delivery closure only after the [stage/release/ready protocol](../03-output/delivery.md#closure-handoff-and-queue-less-progress) establishes physical handoff release. Session freeze may detach accounting facts and make their late wakes inert. It must preserve the independent callback-exit or queued-cutoff evidence needed to complete unregister.

## Dispatch and completion evidence

Keep four boundaries separate: submission acceptance, task entry, an operation's returned result, and physical slot release. Android's [`Handler.post()`](https://developer.android.com/reference/kotlin/android/os/Handler#post) distinguishes acceptance from definite rejection; acceptance is not a task-entry or progress receipt.

Install the exact runnable and retained resources before submission can expose entry. Definite rejection releases only the exact never-entered request. Accepted work remains retained until real entry and the owner's release condition. A queued task fenced by retirement may later enter an inert path; it must not be treated as an entered platform operation that returned.

`SerialTaskSlot` has one attempt and no queue. Its task body waits for the underlying dispatch call to resolve as accepted. Same-thread reentrant dispatch is a contract violation. Once acceptance is published, another thread may enter and release the task before `trySubmit()` returns. A normal task-body return clears the exact slot before invoking its release callback. A throwing or nonreturning body does not release the slot or authorize a successor; a throwing release callback is a later, distinct boundary.

The [first Control rejection path](session.md#bootstrap-and-first-control-entry) offers a known `false` through pre-Control failure arbitration. It is definite non-entry, not unresolved accepted work, and provides no reason to retry or create a replacement lane.

Physical owners settle their own resources and release private synchronization before returning facts. Delivery revokes a borrow on every actual callback exit; its ordinary callback-return proof and later physical closure are distinct, as specified in [Delivery](../03-output/delivery.md#borrowed-frame-lifetime). Capture return and Encoding loan settlement retain exact identity across terminal detach. Queue state, elapsed time, cancellation, terminal state, reference loss, and garbage collection cannot substitute for a real return.

## Ordinary publication

Coordinator commits semantic state and installs one exact State or Stats reservation under `publicationGate → sessionGate`. It assigns the complete Flow value outside both gates, then reacquires the same lock order and settles that reservation. Only one ordinary publication can be in flight, so later ordinary publication and terminal claim cannot overtake it.

For `Active`, settlement rechecks ordinary admission, pending Control work, and the committed topology against a fresh Metrics snapshot. Only a still-current settlement opens production and grants startup success. [Session](session.md#first-active-and-start-settlement) explains why public assignment and successful `start()` completion can therefore be separated.

Unlocked assignment does not mean asynchronous assignment. A collector may execute inline and delay reservation settlement. Collector presence, speed, cancellation, or reentry never supplies currentness or authorizes session work.

## Observation and diagnostics

`SessionObservationPublisher` creates the State, Stats, and diagnostic Flow facades once and preserves their identity for the session. It receives complete immutable values. It does not derive fields, read revisions, choose transitions, accumulate counters, or own the publication transaction. [Production](production.md#statistics-calculation-and-publication) owns statistics calculation and cadence.

State and Stats are separate equality-conflated `StateFlow`s. Each latest value is coherent; the pair is not atomic, and collector resumption is not a publication receipt. [`StateFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/) resumes collectors outside its own lock, but an [unconfined dispatcher](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-unconfined.html) can execute collector code on the assigning thread. Blocking there can delay assignment, callback admission, and cleanup behind it. Do not synchronously block a collector on session work that needs that assignment to finish. Ordinary suspending reentry is different: its safety depends on the particular operation's reservation and continuation path.

Diagnostics use `SharedFlow` with `replay = 0`, 128 extra buffer slots, and `DROP_OLDEST`. A short private gate reserves the next positive session-local sequence. Wall-clock sampling, immutable event construction, and `tryEmit` occur after releasing that gate. Consequently failed emissions can leave sequence gaps, concurrent emission can reorder reserved sequences, and wall-clock timestamps need not increase. Message construction enforces the internal 224-character limit; diagnostic text remains noncontractual for callers.

No subscribers, overflow, optional absence, silent sequence exhaustion, and caught ordinary `Exception`s are best-effort outcomes. They neither trigger retry nor change session policy. `Error` and other non-`Exception` throwables follow [the shared failure boundary](session.md#exception-and-cancellation-boundaries). Diagnostic delivery proves neither semantic commitment nor resource settlement.

## Terminal publication

The irreversible [terminal claim](session.md#terminal-contenders-priority-and-claim) ends ordinary publication authority. The publisher then receives the prepared final values and invokes:

```text
final Stats → optional best-effort diagnostic → terminal State
```

This is assignment invocation order, not atomic cross-flow observation or collector order. An ordinary diagnostic `Exception` is contained and cannot block terminal State. An uncontained throwable can interrupt the suffix; the protocol does not promise its remaining publication or cleanup steps then ran.

Observation closes no Flow, waits for no collector acknowledgment, and provides no alternate publisher or cleanup continuation. Late return evidence may complete an independent registration obligation without restoring session publication authority.

## Logical stop completion

Coordinator signals its parentless terminal-publication deferred after terminal Flow assignment and the terminal-owned startup settlement attempt, outside both session gates. It also creates one `ProjectionStopCompletion` and explicitly passes that identity through Bootstrap, Capture, and ProjectionOwner. The responsible owner completes it after recording the exact local stop return/failure or a required stop-dispatch failure. [Session retirement](session.md#retirement-and-later-sessions) defines the complete wait conjunction and outcome policy.

Notification runs outside ownership gates and the platform-call exception boundary: an immediate waiter can resume on the notifying thread, so ownership and public facts must already be recorded. These completion signals are independent of individual callers’ cancellation. Late owner results still settle their exact resources through the existing frozen correlation rules.

## Progress limits

Accepted dispatch or Handler work may enter later or never enter. An entered platform, codec, callback, or cleanup call may return later or never return. Startup expiry requires an entered, current deadline check; elapsed time alone executes nothing. Delayed-scheduling failure or exceptional completion does not invent entry. [`HandlerThread.quitSafely()`](https://developer.android.com/reference/kotlin/android/os/HandlerThread#quitsafely) requests quit; it does not join the thread or prove cleanup.

There is no watchdog, polling loop, inline Control fallback, replacement lane, emergency publisher, callback interruption, or timeout-generated settlement. If Control or its accepted wake never enters, startup or terminal publication may remain pending. Unregister still requires actual cutoff/callback-exit evidence; session termination neither supplies that evidence nor cancels the completion obligation. These limits extend the public [run-outcome model](../../docs/architecture.md#run-outcome-and-resource-release).

## Implementation and verification

- [SessionCoordinator](../../src/main/kotlin/io/screenstream/capture/internal/session/SessionCoordinator.kt): reservations, exact return ingress, gates, and Control scheduling.
- [SessionControlExecutor](../../src/main/kotlin/io/screenstream/capture/internal/session/SessionControlExecutor.kt), [SerialTaskSlot](../../src/main/kotlin/io/screenstream/capture/internal/runtime/SerialTaskSlot.kt), and [SessionObservationPublisher](../../src/main/kotlin/io/screenstream/capture/internal/session/SessionObservationPublisher.kt): execution and publication mechanics.
- [Verification contracts](../04-testing/verification-contracts.md): `RUN-01`, `OBS-01`, `DEL-01`–`DEL-03`, and owner-specific contracts. Runtime primitive evidence does not replace owner settlement evidence.
