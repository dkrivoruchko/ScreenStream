# Screen Capture Engine — Target Domain Contract

This file solely owns Target preparation/install state, operation ports/leases/provenance/evidence, `targetGate`,
generation-fenced listener admission, retirement readiness, and the Target cleanup graph. Controller ordering,
currentness, and commits are [CTRL-100](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities)
through [CTRL-400](05-domain-controller-reconciliation.md#ctrl-400--cleanuproot-handoff). Android calls and their
classification are [AND-CAP-010](06-domain-android-capture-metrics.md#and-cap-010--projection-and-virtualdisplay-calls)
through [AND-CAP-040](06-domain-android-capture-metrics.md#and-cap-040--android-deadlines-and-call-policy).
GL owns OES operations and namespace retirement in [GL-050](08-domain-gl.md#gl-050--target-oes-bridge-and-gl-construction)
and [GL-070](08-domain-gl.md#gl-070--destruction-namespace-poison-and-cleanup-suffix). Shared occurrence,
deadline, ownership, and quarantine mechanics are [CORE-SET-1](03-shared-runtime.md#core-set-1--generic-operation-occurrence) through
[CORE-CLEAN-2](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction).

## Navigation

- [TGT-001](#tgt-001--files-and-private-declarations)
- [TGT-010](#tgt-010--typed-boundary-map)
- [TGT-020](#tgt-020--preparation-and-single-disposition)
- [TGT-030](#tgt-030--currenttarget-ports-leases-and-provenance)
- [TGT-040](#tgt-040--producer-evidence)
- [TGT-050](#tgt-050--listener-source-bit-and-generation-fence)
- [TGT-060](#tgt-060--mutually-exclusive-retirement-readiness)
- [TGT-070](#tgt-070--surface-and-targetscope-destruction)
- [TGT-080](#tgt-080--context-poison-transfer)
- [TGT-090](#tgt-090--successor-admission-cleanup-and-nonreturn)
- [TGT-095](#tgt-095--concurrency-and-publication)
- [TGT-098](#tgt-098--forbidden-alternatives)
- [TGT-100](#tgt-100--executable-obligations)

## TGT-001 — Files and private declarations

Target is one identity-based mutable authority under `targetGate`. It alone prepares and promotes Targets,
registers ports and leases, binds provenance, and issues Target receipts; private collaborators create no second
Target state machine. `TargetMode` remains exactly `Full | Downscaled` with positive planned dimensions, while
plan selection, geometry, reuse, and fallback remain controller authority.

One Session has at most one active occurrence-owned `PreparedTarget` or installed `CurrentTarget`. Older exact
residue may coexist only after real transfer to cleanup ownership under [TGT-090](#tgt-090--successor-admission-cleanup-and-nonreturn).
A Target generation is positive, strictly increasing, never reused, and may contain gaps. `TargetOwner` refuses
reuse on exhaustion; [CTRL-100](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities) accepts
only the resulting nonreused identity at its owning transition.

## TGT-010 — Typed boundary map

All inputs are exact identities or typed facts. All outputs retain Target identity, generation, operation identity,
operation kind, and referential provenance.

| Direction | Exact boundary | Target-domain obligation |
| --- | --- | --- |
| inbound from `CTRL-100`–`CTRL-130` | current reconciliation key, immutable `TargetPlan`, exact Controller-owned Replacement identity, construction/install or cleanup command | Reserve one generation, bind the positive Replacement correlation, and arbitrate the matching `PreparedTarget` occurrence once; stale safe completion cleans only itself. |
| inbound from `AND-CAP-020`/`AND-CAP-030` | sealed Android-owned `AndroidTargetPostOutcome = RetiredUnused | DefinitelyUnentered | PostExposed` plus exact platform result | Validate only the bound Target identity/provenance and Replacement correlation, then create/apply the matching Target-owned producer/no-producer/detach or port-retirement fact; never bind or inspect `OperationOccurrence`, `AndroidPostTicket`, Handler, or lane internals. |
| inbound from `GL-050`/`GL-070` | adopted OES/`SurfaceTexture`/`Surface` construction result; exact Surface-release outcome; canonical `GlDestructionSuccessReceipt`; exclusive `ContextNamespace` transfer/receipt | Adopt each returned owner immediately; validate and consume only matching Surface, TargetScope, or namespace evidence. |
| outbound to `AND-CAP-020` | `AndroidListenerInstallationPort`, `AndroidSurfacePort`, `AndroidDetachPort`, or `AndroidListenerRemovalPort`; each raw-handle port includes its counted lease | Expose only the minimum listener, `SurfaceTexture`, or `Surface` needed by that operation; the evidence-only detach port exposes no Target handle. |
| outbound to `GL-050`/`GL-070` | `GlFramePort`, `SurfaceReleasePort`, or `TargetScopeDestructionGraph` command plus its counted lease | Expose only the matching `Surface`, `SurfaceTexture`, and/or OES name for that operation; retain Target ownership. |
| outbound to `CTRL-100`/`CTRL-130`/`CTRL-300` | installed Target identity, generation-fenced source fact, retirement readiness, or exact result | Publish only after the Target transition is durable and `targetGate` is released; controller alone applies it. |
| outbound to `CTRL-400`/`CORE-CLEAN-1`–`2` | unresolved occurrence, writable cells, owner bag, ports/leases and dependent Target resources | Transfer the intact exact graph; a passive flag is not ownership. |

Raw Android objects and GL names never act as ownership, currentness, producer, detach, or cleanup evidence.

## TGT-020 — Preparation and single disposition

Before construction, one prepared Target binds the reserved generation/plan, partial OES/`SurfaceTexture`/
`Surface` ownership, construction settlement, promotion, and release obligations. Return and promotion remain
allocation-safe under `CORE-SET-1`.

[CTRL-110](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision) plan/currentness and
[GL-030](08-domain-gl.md#gl-030--capability-and-admission) per-axis capability preflight must already admit
construction. A deterministic pre-retirement denial creates no `PreparedTarget`, Target call, allocation, or
retirement.

Construction on the GL lane is ordered:

```text
create OES -> SurfaceTexture(oes, false) -> setDefaultBufferSize(Tw, Th) -> Surface(surfaceTexture)
```

`Tw` and `Th` are the positive dimensions in the accepted plan. Each successful return enters its precreated
owner slot immediately. [GL-050](08-domain-gl.md#gl-050--target-oes-bridge-and-gl-construction) owns constructor
calls and result classification; this domain retains every exact partial owner.

Exactly one installation or cleanup disposition wins. Under [CTRL-130](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration),
only a timely complete result matching the full reconciliation key, occurrence, generation, plan, Session
lifecycle, and current Target authority may command `Installed`; Target performs the allocation-free candidate
transfer. Partial, stale, late, terminal, failed, or otherwise noninstalling completion commands
`CleanupClaimed` and never becomes current. An installed candidate owns all three complete resources; cleanup
ownership records each present or structurally absent slot.

No listener, VirtualDisplay producer, frame, or ordinary Target lease boundary is crossed before `Installed`.

## TGT-030 — CurrentTarget ports, leases, and provenance

Only the installed current Target registers an operation port, bound once to its identity/generation, operation
identity/kind, and provenance.

The closed operation kinds are listener installation/removal, VirtualDisplay creation/attachment/detach/release,
Surface release, GL frame, and TargetScope destruction. One registered port admits one operation. Every port that
exposes a raw Target handle issues one counted one-shot lease, and that exposure is itself one-shot; the
evidence-only detach port exposes no raw Target handle. A port cannot change kind, target, generation, operation,
or consumer owner. Repeated use, mismatched provenance, stale generation, wrong operation, or closed admission
returns no authority and changes no Target state.

Producer and detach ports are separately prepared with Target-owned port, provenance, and fixed Target evidence.
The Controller owns and supplies the exact Replacement identity; Android binds its own staged post outcomes and
root to both already-existing identities under `AND-CAP-020`. Allocation-free registration under `targetGate`
installs only that rooted binding. The Target API receives only sealed `AndroidTargetPostOutcome`; it neither
binds nor reads the `OperationOccurrence`, `AndroidPostTicket`, or Android root. `RetiredUnused` is valid only
for the precommit loser with no post. A committed port accepts only matching `DefinitelyUnentered` or
`PostExposed`. Target mints no Android outcome and inspects no Handler, queue, poison, ticket state, caller result,
or Android owner internals.

`DefinitelyUnentered` may retire the exact producer port with its precreated `Unentered` no-producer evidence.
For a detach port it retires only that port/occurrence, preserves `ProducerAttached`, and proves no physical
detach. `PostExposed` normally closes the inert route; Target may accept a later `DefinitelyUnentered` only as
the exact Android-owned outcome authorized by one closed proof in `AND-CAP-020`/`AND-CAP-030`.
Otherwise the unresolved bound graph must settle through a later exact Android platform result or transfer
intact. Target creates only its own application/retirement facts after validating the sealed outcome's bound
Target identity, provenance, and Replacement correlation.

The designated Android or GL owner receives only the handle(s) needed by the call. The Target increments the
lease count before exposure, releases its private gate, invokes the supplied operation body, and releases exactly
that lease after normal return or ordinary throw. An unexpected fatal boundary retains the lease with the exact
fatal residue rather than claiming release. Consumers neither copy nor retain a handle beyond the lease.
Registered-port/lease validation and executable boundary tests enforce one-operation access and non-retention;
Kotlin visibility alone does not prevent arbitrary same-module capture.

There is no general raw-handle getter, copied raw-handle fact, unregistered convenience path, or lease-free
owner access. Retirement requires a zero counted-lease observation after affected entered work drains.

Each installed Target owns one positive nonreused reversible frame-admission epoch under `targetGate`. It may be
open, sealed with at most the exact entered predecessor, reopened with a fresh epoch after quiescence, or closed
permanently for retirement. This state is Target-local mechanical authority, distinct from Session lifecycle/currentness, production policy,
Target generation fencing, and irreversible retirement. The matching `sealFrameAdmission`,
`claimFrameQuiesced`, and `reopenFrameAdmission` operations return only exact
`TargetFrameAdmissionSealedFact`, `TargetFrameQuiescedFact`, and `TargetFrameAdmissionReopenedFact`; an epoch,
lease count, operation entry, or Boolean alone is not a fact.

A GL frame reservation and delayed commit remain bound to the captured epoch and end once as entered then
retired, or inertly rejected by sealing/staleness then retired. `TargetEntered` is the first domain-work
transition and races sealing under `targetGate`. Reservation first may
enter as the seal's sole predecessor and later acquire that port's one counted raw-handle lease while sealed;
seal first returns the precreated typed inert disposition without exposing Target authority or a raw-handle
lease. If no port was constructed it is structurally absent; otherwise the exact reserved port retires. A reserved loser or delayed old-epoch
commit atomically becomes `RejectedBySealOrStaleEpoch`; its carrier/attempt/slot settle exactly once, its Runnable
performs zero Target or GL outward work, and its port retires. Generic executor or operation entry is not
`TargetEntered` and grants no raw authority. No reservation or commit succeeds while sealed.

`TargetFrameQuiescedFact` requires the exact current sealed fact, no predecessor, no GL frame port, and zero
counted leases. Under `targetGate`, exact predecessor-occurrence settlement plus that port's retirement clears
only the matching predecessor; inert-port retirement clears only itself. Quiescence then commits once and remains
stable until its matching reopen or retirement. `TargetFrameAdmissionReopenedFact` requires that exact quiesced
fact and the same installed, unfenced Target with its producer still attached. It reserves a checked fresh epoch
before changing state; exhaustion returns a typed rejection, leaves Target sealed, and offers current
`InternalFailure` to the controller. Stale or repeated facts change nothing. Retirement has precedence, absorbs
either reversible state as `RetirementClosed`, preserves physical ownership, and can never reopen. Controller
policy and final topology/admission commit remain `CTRL-120`/`CTRL-130`.

## TGT-040 — Producer evidence

`TargetProducerState` is closed:

```text
AwaitingEvidence -> ProducerAttached -> Detached
AwaitingEvidence -> NoProducer
```

Listener installation must settle normally before producer admission. The exact producer port then supplies one
of these mutually exclusive typed outcomes:

| Evidence | Admissible source | Result |
| --- | --- | --- |
| producer | normal VirtualDisplay creation with a nonnull owner, or normal `setSurface(surface)` attachment | `ProducerAttached` |
| no producer: `Inapplicable` | the exact create/attach operation was structurally unnecessary and unentered | `NoProducer` |
| no producer: `Unentered` | the exact create/attach occurrence settled without outward entry | `NoProducer` |
| no producer: `ReturnedWithoutProducer` | normal VirtualDisplay creation returned null | `NoProducer` |
| detach | normal `setSurface(null)` or applicable normal `VirtualDisplay.release()` return | `Detached` |

Every evidence object is one-shot and matches the registered port by identity, generation, operation, kind, and
provenance. A bare Boolean or generation cannot satisfy it. Throw, expiry, ambiguous entry/return, projection
stop, or another occurrence cannot prove no producer or detach. Evidence never fabricates a platform receipt.
Normal `setSurface(newSurface)` supplies only producer evidence for that new Target; it cannot prove detach of
the old Target.
GL-frame port registration additionally requires the exact installed generation and `ProducerAttached`; it is
retired only after its matching frame occurrence mechanically settles.

Target alone constructs the evidence, consumes only the matching Android-owned bound outcome/result, and changes
`TargetProducerState` under `targetGate`.

## TGT-050 — Listener, source bit, and generation fence

After installation and before producer attachment, [AND-CAP-020](06-domain-android-capture-metrics.md#and-cap-020--target-facing-typed-boundary)
installs the `SurfaceTexture.OnFrameAvailableListener` through its exact registered port on the Android Handler.
Target owns the listener object and captured generation. Its constant-time body only attempts:

```text
current installed generation -> latestPendingSource.set(true) -> signal controller
```

Target admits only the installed unfenced generation and offers the durable source signal after releasing
`targetGate`. [DEL-PACE-001](12-domain-delivery-observation.md#del-pace-001--pending-source-and-materialization)
solely owns coalescing, exchange/materialization, deferral, and attempt/drop interpretation. Retirement receives
its ordering from [CTRL-120](05-domain-controller-reconciliation.md#ctrl-120--destructive-transition-order), then
Target closes source/work admission, drains entered Target work, fences the generation, and drops its pending
bit. Reversible frame sealing in `TGT-030` neither fences the generation nor changes listener/source-bit
ownership; Production policy prevents materialization until final reopen commit. A retired or noncurrent
callback is inert and cannot touch current pending state, geometry, cache,
admission, producer state, leases, or released resources.

Listener retirement uses one removal occurrence and one typed same-Handler sentinel boundary. The removal command
posts its exact sentinel before publishing normal settlement. Retirement requires both normal removal settlement
and observation of that sentinel for the same operation identity. The sentinel proves ordering of listener
invocations accepted on that Handler; it is not producer freshness, buffer age, or a callback-drain receipt.
Producer timestamps likewise have no admission/currentness authority.

## TGT-060 — Mutually exclusive retirement readiness

Reversible `TargetFrameQuiescedFact` is not retirement readiness and authorizes no detach, listener removal,
Surface release, generation fence, or successor. An installed `CurrentTarget` becomes Surface-release-ready only
after all of these facts hold:

1. fresh, repeat, delivery, and Target-work admission is closed;
2. all entered Target work has drained;
3. its generation is fenced and its pending bit discarded;
4. listener removal settled normally and its exact same-Handler sentinel was observed;
5. every Target port/use is settled and the counted lease count is zero;
6. producer state is exact `NoProducer` or `Detached`.

`NoProducer` is accepted only under [TGT-040](#tgt-040--producer-evidence). `Detached` requires the exact current
`setSurface(null)` or applicable `VirtualDisplay.release()` return classified by
[AND-CAP-010](06-domain-android-capture-metrics.md#and-cap-010--projection-and-virtualdisplay-calls), from which
Target issues and consumes its exact detach receipt. The selected same-VirtualDisplay replacement path uses
normal `setSurface(null)`; definitely-unentered detach-port retirement and later `setSurface(newSurface)` are
not detach evidence.

An uninstalled `PreparedTarget` uses a different readiness proof: construction is mechanically settled,
disposition is `CleanupClaimed`, and structural state proves that listener, producer, frame, and lease APIs were
never crossed. After construction settles, each absent resource slot becomes `Inapplicable`; each present slot
becomes `Required`. `Inapplicable` is structural absence, not a release receipt.

Resource-obligation transitions are closed:

```text
Surface / SurfaceTexture: AwaitingSettlement -> Required -> Completed
                          AwaitingSettlement -> Inapplicable
OES:                      AwaitingSettlement -> Required -> Deleted
                          AwaitingSettlement -> Required -> Transferred
                          AwaitingSettlement -> Inapplicable
```

`Transferred` settles only through the matching namespace receipt in [TGT-080](#tgt-080--context-poison-transfer).

Installed prerequisites cannot satisfy the uninstalled route, and uninstalled structural facts cannot satisfy
the installed route.

## TGT-070 — Surface and TargetScope destruction

Both readiness routes converge on one shared dependency chain:

```text
Surface normal-return receipt
  -> SurfaceTexture normal-return receipt
  -> OES destruction or ContextNamespace transfer
  -> canonical GlDestructionSuccessReceipt
```

An absent partial resource skips only its own structurally `Inapplicable` step. `Surface.release()` is the typed
specialization of [CORE-SET-1](03-shared-runtime.md#core-set-1--generic-operation-occurrence) through
[CORE-SET-3](03-shared-runtime.md#core-set-3--finite-deadline-arbitration); its preterminal occurrence uses the Android entered-operation deadline owned by
[AND-CAP-040](06-domain-android-capture-metrics.md#and-cap-040--android-deadlines-and-call-policy), even when
executed on the GL lane. A timely normal return permits controller application; a normal return at or after its
deadline is a real Target-owned `TargetSurfaceReleaseReceipt` for cleanup only. Throw supplies no receipt. A
terminal-origin or pre-entry terminal-converted mandatory release keeps the same occurrence with no watchdog;
terminal after entry transfers the occurrence and retires the active deadline.

[GL-070](08-domain-gl.md#gl-070--destruction-namespace-poison-and-cleanup-suffix) submits and invokes the outward
`Surface.release()` call exactly once on the private GL lane. [AND-CAP-040](06-domain-android-capture-metrics.md#and-cap-040--android-deadlines-and-call-policy)
owns its deadline and result-classification policy. Target owns its admission port, lease, exact receipt
construction/consumption, and dependent cleanup transition.

After the Surface receipt, Target issues one TargetScope command/lease; [GL-040](08-domain-gl.md#gl-040--fail-closed-gles-groups-and-context-integrity)
owns its deadline and [GL-070](08-domain-gl.md#gl-070--destruction-namespace-poison-and-cleanup-suffix) owns the
`SurfaceTexture.release()`/OES operation. In an intact context their clean completion yields the sole canonical,
duration-free `GlDestructionSuccessReceipt(TargetScope)`. Generic settlement time remains the duration authority.
Target consumes that exact receipt and accepts no local duplicate, bridge, Boolean, or adjacent receipt.

Partial return, throw, expiry, nonreturn, or scheduling rejection retains exactly the still-required resource,
operation, port/lease, and physical dependents. Independent resources and cleanup roots continue according to
[CORE-CLEAN-1](03-shared-runtime.md#core-clean-1--cleanup-forest-and-dependency-graph) and
[CORE-CLEAN-2](03-shared-runtime.md#core-clean-2--quarantine-nonreturn-and-late-reduction).

## TGT-080 — Context poison transfer

When [GL-040](08-domain-gl.md#gl-040--fail-closed-gles-groups-and-context-integrity) declares the private context
poisoned or of unknown integrity, Target authorizes exactly one transfer of its still-required TargetScope OES
obligation; [GL-070](08-domain-gl.md#gl-070--destruction-namespace-poison-and-cleanup-suffix) owns the
`ContextNamespace` bag and retirement operation. No ordinary OES-delete receipt is created. Surface and
`SurfaceTexture` prerequisites remain required because they are non-GLES work.

Only the matching canonical `GlDestructionSuccessReceipt(ContextNamespace)` may retire the transferred OES
obligation transitively. It must match the TargetScope destruction graph, Target identity/generation, namespace
identity, operation kind, and provenance. A TargetScope receipt and namespace transfer are mutually exclusive;
neither may be applied twice. GL owns context-integrity classification, unbind/currentness, context destruction,
and its typed result; Target owns obligation-transfer authorization and matching receipt consumption. Controller
terminal application remains [CTRL-130](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration).

## TGT-090 — Successor admission, cleanup, and nonreturn

A successor Target is admitted only after the predecessor is mechanically retired or its exact unresolved work,
ports/leases, and transitive dependencies have been adopted by a real cleanup child under the permanent Session
quarantine root through
[CTRL-400](05-domain-controller-reconciliation.md#ctrl-400--cleanuproot-handoff). Until that transfer is durable, an
unsafe nonreturn retains the predecessor `PreparedTarget` or `CurrentTarget` and blocks successor admission.

Cleanup and late results are occurrence-fenced. A late normal receipt may reduce only its matching Target child;
it cannot install a Target, allocate a successor, enable Downscaled fallback, reopen admission, publish source
work, or change the terminal winner. A passive adoption receipt, rooted Boolean, assertion, generation value,
managed-reference drop, or cancellation intent cannot clear predecessor ownership or a physical obligation.

Target cleanup retains only exact unresolved dependents. A blocked Surface release may retain Surface,
`SurfaceTexture`, Target OES, applicable GL/EGL/lane dependencies, its occurrence, and its live lease. Proven
independent GL resources retire before release entry; Android capture, JPEG/storage, delivery, metrics,
diagnostics, unrelated deadlines, independent roots, and another Session continue.

## TGT-095 — Concurrency and publication

Target-owned mutable transitions are atomic under a private synchronization boundary (`targetGate`); its concrete
primitive and fairness are not normative. No outward call, scheduling, controller signal, diagnostic,
Flow/publication, cleanup action, or result-derived allocation runs while Target synchronization is held. Port
admission copies only already-owned references within that boundary; the operation body runs after release with
its counted lease.

For producer/no-producer/detach application, the controller action releases `sessionGate` and the applicable
operation `settlementGate` before asking `TargetOwner` to apply the precreated exact fact under `targetGate`.
Application arbitrates with terminal: either the matching Target transition is already durable, or terminal
transfers the still-unapplied bound graph intact. Listener and sentinel callbacks likewise make their bounded
Target transition durable, release Target synchronization, and only then signal the controller.

No caller enum/Boolean, post return/throw, poison, shutdown request, queue observation, dropped root, or copied
ticket/occurrence state is Target truth. Only the sealed Android-owned outcome and exact platform result may
authorize a Target-owned application/retirement fact; `PostExposed` keeps the whole graph transferable until a
later exact Android outcome settles it.

`TargetOwner.currentTarget` and reconciliation currentness remain controller-owned under `sessionGate`;
`targetGate` cannot install, replace, or declare a Target current by itself.

## TGT-098 — Forbidden alternatives

- public or general raw-handle getters, copied handle/name facts, or unregistered owner access;
- reusable/cross-operation ports, uncounted leases, or evidence lacking exact provenance;
- listener, producer, frame, or lease work on an uninstalled `PreparedTarget`;
- Target installation by stale/late completion or by allocation after promotion;
- a callback-drain/freshness meaning for the listener sentinel or producer timestamp;
- a bare Boolean/generation as producer, detach, sealing, quiescence, reopening, adoption, destruction, or cleanup evidence;
- replacement before mechanical retirement or real cleanup-owner adoption;
- fabricated Surface, `SurfaceTexture`, OES, detach, or namespace receipts;
- retry, fallback, replacement, or lifecycle revision after unsafe ambiguity/nonreturn;
- a Target-local GL receipt type, compatibility bridge, second Target state machine, or resource registry.

## TGT-100 — Executable obligations

Closed packet membership is in [router §5](01-authority-router.md#5-closed-implementation-packets); shared
closure/routing and test namespaces are in [Document 04](04-verification.md), and canonical test source sets are in
[router §7](01-authority-router.md#7-test-manifest). The rows below prove Target-private authority; controller
commit, Android calls, GL execution, and pacing materialization remain with their owning leaves.

| Tests | Required Target proof |
| --- | --- |
| `H-RC` | Generation and reversible-epoch gaps/no reuse/exhaustion; complete current install versus partial/stale cleanup claim; unsafe construction nonreturn and successor exclusion; Target replacement while compatible output owners retain identity. |
| `H-OS` | Construction, listener, Surface, and TargetScope occurrences at timely/equality/late boundaries; rejection, throw, nonreturn, terminal transfer and cleanup-only late facts; reservation-first, seal-first and delayed old-epoch entry, typed inert settlement, sole predecessor occurrence settlement plus port retirement, stable quiescence, exact reopen, and terminal at every boundary; exact owner-bag provenance. |
| `H-PS` | Target-side current/retired-generation admission and fence seam; reversible Production/Target sealing; `DEL-PACE-001` owns exchange/coalescing/deferral and attempt/drop assertions. |
| `A-CAP` | Inaccessible construction; only installed `CurrentTarget` crosses listener/VirtualDisplay boundaries; allocation-free registration of separately prepared producer/detach bindings; exact consumption of `RetiredUnused`, `DefinitelyUnentered`, `PostExposed`, settled-result, or terminal-transfer input without Android-internal inspection; exact one-operation ports/leases and producer/no-producer/detach evidence, including new-surface producer-only evidence versus null-surface old detach; listener-generation and sentinel races; mutually exclusive release readiness. Android post/entry interleavings remain solely `AND-CAP-060`. |
| `A-GL` | Partial resource adoption; exact GL frame/Surface-release/TargetScope ports; zero uninstalled GL/frame work; one GL-lane Surface release followed by the SurfaceTexture/OES chain; canonical TargetScope receipt or exclusive namespace transfer. |
| `A-CL` | Every held prerequisite and partial bag; terminal no-watchdog conversion; exact unresolved residue; real cleanup adoption before successor; namespace transitive retirement; independent-root and cross-Session progress. |

Every port test checks Target/generation/operation/kind/provenance mismatch, second use, counted lease increment/
release or fatal retention for raw-handle ports, reversible epoch/seal/currentness, exact evidence-only detach behavior, absence of a general getter,
and consumer non-retention after the operation. Every installed release test withholds each prerequisite
separately; every uninstalled test proves structural zero
listener/producer/frame/lease use. Listener tests cover current/retired-generation admission, removal settlement
versus sentinel order, and stale/late facts; `DEL-PACE-001` tests the pending-bit exchange races. Cleanup tests
consume only the canonical duration-free GL receipt and never infer release from timeout, cancellation, or
managed-reference retirement.
