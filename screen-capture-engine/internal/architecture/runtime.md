# Runtime flows

These flows apply the [internal ownership model](overview.md) to a capture run. Caller-visible lifecycle behavior is in [Session lifecycle](../../docs/architecture.md#session-lifecycle).

## Session creation and projection ownership

The synchronous session factory accepts a fresh projection. Normal return transfers ownership to the returned session; a throwing factory leaves ownership with the caller. Factory acceptance establishes a retained owner without starting capture or doing heavy bootstrap work. Later `start` selects the initial parameters, takes no projection, and does not transfer ownership. The application's lifecycle owner must call `stop()` even if `start` never enters; an unentered suspending call cannot perform cleanup. Cancellation of an entered start wait requests session stop, and cancellation never returns the projection to the caller. Public signatures and lifecycle examples belong in [Usage](../../docs/usage.md#start-a-capture-run).

The session must retain cleanup responsibility continuously from factory acceptance through Capture adoption and eventual retirement. `stop()` before start must also retire the owned projection; neither first Control entry nor a running Capture owner is a prerequisite for accepting that stop.

## Start and first Active

Start combines semantic admission and physical bootstrap; the session already owns the projection.

1. The caller path observes coroutine cancellation and obtains the elapsed-realtime evidence for the single ten-second startup cutoff. Cancellation requests stop without relinquishing the session-owned projection.
2. Under `publicationGate -> sessionGate`, Lifecycle admits one start, Topology installs the initial desire, and Coordinator reserves `Starting`. The Flow assignment occurs after both gates are released.
3. Runtime submits Bootstrap to its shared non-inline worker. Bootstrap starts the Control and Capture lanes, constructs the fixed graph, and posts one exact first Control task while retaining every untransferred root.
4. Entry of that task lets Capture adopt the session-owned projection, transfers the lanes to their owners, installs the three Links and Metrics owner, and opens the first Coordinator turn. If terminal cutoff wins first, the task is inert and the owners retain every untransferred root for safe retirement.
5. Coordinator attaches Metrics and opens Capture once. On API 34+, provisional capture setup uses the positive Metrics dimensions and density independently of requested source selection, crop, and final-output demand, so capture can begin and report authoritative dimensions. Definitive geometry-dependent output validation and preparation wait for the first valid captured-content resize. Coordinator then resolves current Capture and Encoding plans and waits for all readiness evidence. Android documents resize and projection-stop callbacks in its [Media projection guide](https://developer.android.com/media/grow/media-projection#customization).
6. First `Active` is reserved only when the current immutable Metrics snapshot, topology, leaf readiness, Bootstrap acceptance facts, and strict startup cutoff all agree. After unlocked assignment, Coordinator revalidates the Metrics snapshot before opening output and completing `start`.

The two normal-true Bootstrap submission results are readiness facts, but neither proves task entry or plan readiness. Definite shared-worker rejection is an internal failure. A normally returned `false` from the first Control post is offered as `InternalFailure` through the pre-Control terminal authority, subject to currentness and terminal priority. Android's [`Handler.post()`](https://developer.android.com/reference/android/os/Handler#post(java.lang.Runnable)) distinguishes definite rejection from accepted work. Known rejection cannot leave startup pending or authorize retry, a replacement lane, or inline Control execution. Accepted work remains subject to the [progress limits](../contracts/concurrency-and-liveness.md#progress-limits).

Provisional setup cannot reject requested output merely because it does not fit the provisional geometry, and it publishes neither effective output nor frames. Required setup resources can still fail through their ordinary classification. Once authoritative geometry is available, a request that cannot produce valid output follows the normal startup failure rules; startup never suspends.

Normal stop before first Active cancels the pending start operation; real startup failure retains its stable failure problem. The outcome and coroutine rules are defined in [Cancellation and operation outcomes](../contracts/failures-and-terminal-semantics.md#cancellation-and-operation-outcomes). First-Active Metrics readiness is defined in [Metrics](../components/metrics.md#readiness-and-cross-component-flow).

## Reconfiguration and recovery

An unequal parameter update durably replaces the desired parameters and advances `configRevision`. Metrics changes, adoption of an authoritative resize, or a Session-wide Native-health change can invalidate the current plan without a caller update.

Captured-content resize callbacks, Link facts, and Topology's stored geometry stage authoritative evidence. The atomic pending resize-revision commit, together with production pause and current-output invalidation, adopts that geometry for output currentness. Correctly described output from the old plan may commit before this transaction; after it, old-revision work cannot newly commit as current output. This cutoff does not revoke an already-admitted callback or establish when source pixels were captured.

When an already-active plan becomes invalid, Coordinator atomically pauses affected output/work admission, invalidates incompatible cache, and commits `Reconfiguring` before the first resulting physical effect. Topology resolves the latest desire and current geometry, then Coordinator drives Capture Apply and Encoding reconcile through their Links. Each return is correlated to its exact request and checked against the current revision and plan. Only the current converged plan can reserve a new `Active` value and reopen production.

Intermediate desires may conflate before Control consumes them. An equal desire is a no-op after admission. Work started for an older revision keeps its old descriptor; if it becomes obsolete before output commit, Production accounts and discards it rather than relabeling it.

A recoverable current problem produces `Suspended` only after the Session has previously become Active. Recovery re-enters the same resolution and convergence path; there is no separate recovery controller. See [Failures and terminal semantics](../contracts/failures-and-terminal-semantics.md) for the recoverable/terminal boundary.

A denied current Apply suspends that desired revision without immediately retrying it. Equal desired parameters and unrelated evidence leave the suspended revision quiescent; a relevant desired, geometry, or Metrics-availability revision reopens convergence through the normal path while the previously healthy Target remains usable until replacement commits.

## Fresh production and delivery

Fresh production begins only while Lifecycle permits production and Topology supplies a current ready plan.

1. A source callback records latest source availability; it does not start GPU or JPEG work itself.
2. Coordinator allocates one exact `SessionProductionRecord`, installs the matching Encoding request, and asks Encoding to lend one exact RGBA input capability.
3. After revalidating currentness, Production constructs a `SessionReadBridge` around that record and input. The bridge and loan are not yet installed in Capture.
4. Production evaluates fresh pacing against the previously sampled time. Only a current grant installs the bridge in the Capture Link and commits the pacing phase before dispatching the read. Deferred, denied, or stale pacing discards the uninstalled bridge and its exact input loan and advances no pacing phase.
5. A real Capture return allows Coordinator to authorize either encode or discard of the exact loan. Encoding alone settles the input and its tentative transaction before returning a result.
6. Coordinator accounts the settled result and revalidates the production record, revision, and plan. Mechanical readback and encoding samples remain eligible when a stop offer closes ordinary admission after the result was selected but before it is consumed. Admission closure suppresses further encoding or output and discards the exact resources without classifying semantically current work as stale. Revision, geometry, plan, or production-identity obsolescence still classifies an otherwise successful result as stale. A successful current and admitted result becomes one immutable `PublishedFrame`.
7. Session Delivery decides whether the current registration can receive the frame. `SessionDeliveryLink` installs a pre-minted handoff token before invoking physical Delivery. Delivery opens the borrowed frame only for the exact callback thread and revokes it on callback exit.

At most one materialized production spans read construction, Capture read, Encoding loan/transaction, and unpublished output. At most one callback invocation or unresolved submission exists. A busy consumer turns a later opportunity into a delivery-drop count rather than backlog. Cached-first delivery reuses the existing immutable frame identity; repeat output reuses its bytes but commits a new sequence and timestamp.

## Production schedules, cache, and statistics

Production is the semantic owner of fresh and all-output pacing history, cache and repeat eligibility, output identity, schedule identities, and accumulated Stats. Coordinator combines its immutable candidates with current Lifecycle, Topology, and Session Delivery evidence; only a revalidated committed grant advances pacing or publishes output. Fresh pacing and all-output pacing remain independent; repeat must satisfy both its quiet-period eligibility and all-output pacing, and fresh output wins a simultaneous opportunity. There is no catch-up output. Cache reuse likewise requires the exact current image-compatible plan and backend health. A change that invalidates that compatibility clears the cache before affected output admission can reopen.

Pacing and repeat each have at most one current logical wake and one stable Control callback. A pacing wake is replaced only by a strictly earlier target; replacement removes and reposts that callback. A repeat wake is never replaced: entry or suppression clears its one pending identity before a later turn may rearm it. Exact identity makes a stale callback entry inert. Dispatch rejection is a session failure, while accepted work that never enters remains subject to the shared liveness limits rather than authorizing a retry or replacement.

After a successful repeat commit, Coordinator requests one coalesced immediate successor Control turn. That later turn recalculates current quiet-period and all-output eligibility and, when needed, arms the existing repeat wake. The commit does not directly rearm the wake, add catch-up output, or create a periodic ticker; a missing consumer or an occupied callback therefore does not stop autonomous repeat scheduling.

Stats publication is activity-driven, not a heartbeat. Ordinary changed Stats are eligible only on a naturally entered eligible `Active` Control turn whose elapsed-realtime sample is at least 1,000 ms after the sample used for the previous committed ordinary Stats snapshot, initially the Session-creation sample. Physical assignment and collector observation may occur later and have no minimum spacing guarantee. There is no Stats-only wake or catch-up; changes remain pending through ineligible turns and `Suspended` until a later eligible turn. Terminal publication bypasses ordinary cadence and assigns the final complete Stats before terminal State. Observation only publishes the already-selected values and owns none of this cadence or accounting policy.

## Stop and terminal transition

`stop()` offers `Requested` and closes ordinary public work before returning. Projection callback evidence offers the higher-priority `ProjectionStopped`; a contained fatal boundary offers `Failed(problem)`. An offer starts terminal contention but does not itself publish terminal State.

Coordinator makes progress toward one irreversible claim. Registration completion remains independent of that claim, as defined in [Consumer replacement and unregister](../components/delivery-observation.md#consumer-replacement-and-unregister):

1. Physical Delivery retirement establishes an entry fence: queued callback work cannot newly enter, while an already-entered callback may return later or never return.
2. Coordinator consumes already-selected Delivery or Encoding facts and real Capture read returns that are eligible to affect final accounting. A selected mechanically returned Encoding rejection contributes its failure sample before future readiness, cache, reconfiguration, or output effects are guarded by ordinary admission. Stop does not select an unselected returned Encoding fact, and the terminal claim does not harvest one later.
3. With no ordinary publication in flight, Coordinator prepares and commits the sole terminal claim under the [canonical terminal transaction](../contracts/failures-and-terminal-semantics.md#terminal-contenders-priority-and-claim).
4. The unlocked monotone suffix publishes final Stats, attempts the optional diagnostic, publishes terminal State, settles the pending start outcome, removes pacing/repeat callbacks, requests leaf retirement, and asks the Control thread to quit safely. Session termination does not cancel or fail registration completion.

Terminal publication never waits for a callback, an accepted task, a Capture/Encoding operation, or resource retirement. Late real returns follow owner-local settlement, can establish independent registration completion, and cannot revive frozen Session work. The public [run outcome](../../docs/architecture.md#run-outcome-and-resource-release) is not a cleanup receipt.

Before first Control entry, the same claim and publication rules run through the pre-Control arbitration path. That path does not create a replacement executor or a second publication authority.

## Starting another run

After `stop()` returns, the host may start a fresh session with fresh projection authority. Old allocations and callbacks may outlive that boundary, and callbacks from the two sessions may overlap. Each session owns its own registration; the host coordinates any application resources the callbacks share. Late old-session work must not alter the new session's ownership or output. This boundary does not certify physical resource release or foreground-service cleanup, and it does not authorize overlapping runs before the prior `stop()` returns.
