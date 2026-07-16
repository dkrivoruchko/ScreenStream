# Screen Capture Engine — Shared Runtime Contract

This document owns the cross-domain runtime kernel: occurrences, settlement, deadlines and their wake links,
allocation/ownership rules, cleanup, quarantine, privacy, and structural efficiency. Controller lifecycle,
draining, reconciliation, commit, and cutoff authority belongs to
[CTRL-*](05-domain-controller-reconciliation.md); public schemas and caller-visible behavior belong to
[PROD-*](02-product-contract.md); unlocked State, Stats, and diagnostic construction/emission belongs to
[DEL-OBS-*](12-domain-delivery-observation.md). Domain contracts own their platform calls, resources, result
partitions, constants, and tests. They may specialize this kernel only where it explicitly permits a named
completion rule; they may not copy it into a competing state machine.

## Navigation

- [CORE-001](#core-001--terms-and-authority-model)
- [CORE-OWN-1](#core-own-1--ownership-loans-and-receipts)
- [CORE-SET-1](#core-set-1--generic-operation-occurrence)
- [CORE-SET-2](#core-set-2--entry-return-publication-and-lock-order)
- [CORE-SET-3](#core-set-3--finite-deadline-arbitration)
- [CORE-WAKE-2](#core-wake-2--deadline-wake-link-and-scheduling-rejection)
- [CORE-TIME-1](#core-time-1--clock-and-named-completion-rules)
- [CORE-ALLOC-1](#core-alloc-1--checked-allocation-and-failure-partition)
- [CORE-EFF-1](#core-eff-1--structural-efficiency-and-reuse)
- [CORE-CLEAN-1](#core-clean-1--cleanup-forest-and-dependency-graph)
- [CORE-CLEAN-2](#core-clean-2--quarantine-nonreturn-and-late-reduction)
- [CORE-PRIV-1](#core-priv-1--data-lifetime-and-privacy)

## CORE-001 — Terms and authority model

- A **Session** is one nonrestartable capture lifetime with independent state, owners, counters, cache,
  registration, failures, and cleanup root.
- An **owner** has the sole authority to retain, transfer, use, release, or root a resource or runtime role.
- An **occurrence** is one identity-fenced instance of work or ownership-sensitive progress.
- A **carrier** is a resource or byte range with exactly one current owner and explicit leases where needed.
- A **fact** is immutable evidence offered to the controller; publishing a fact does not itself change Session
  policy or lifecycle.
- A **receipt** proves the exact operation or physical outcome named by its type. It never proves an adjacent
  operation, immediate reclamation, or a result not actually observed.
- **Cleanup** progresses already-owned obligations after active authority closes. **Quarantine** retains the
  exact residue whose safe release or completion cannot be proved. Neither means repair, recovery, or reuse.

Domain owners retain their physical resources and execute their outward calls. Controller ownership and helper
limits are defined by [CTRL-001](05-domain-controller-reconciliation.md#ctrl-001--controller-confinement-and-gates)
and [CTRL-200](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority).

The runtime uses direct typed ownership, one active topology, bounded latest-value cells, one production slot,
one current registration/handoff, and one permanent Session quarantine root. It has no generic resource
registry, alternate planner, rollback topology, duplicate state machine, compatibility wrapper, or raw-handle
ownership proof.

Controller rules are routed without restatement:

- gates and confinement: [CTRL-001](05-domain-controller-reconciliation.md#ctrl-001--controller-confinement-and-gates);
- lossless drainer: [CTRL-010](05-domain-controller-reconciliation.md#ctrl-010--lossless-action-drainer);
- public-command linearization: [CTRL-020](05-domain-controller-reconciliation.md#ctrl-020--public-command-application);
- lifecycle and terminal application: [CTRL-030](05-domain-controller-reconciliation.md#ctrl-030--lifecycle-and-terminal-application);
- currentness and reconciliation: [CTRL-100](05-domain-controller-reconciliation.md#ctrl-100--currentness-identities),
  [CTRL-110](05-domain-controller-reconciliation.md#ctrl-110--reconciliation-decision),
  [CTRL-120](05-domain-controller-reconciliation.md#ctrl-120--destructive-transition-order), and
  [CTRL-130](05-domain-controller-reconciliation.md#ctrl-130--completion-and-fallback-arbitration);
- delivery/observation arbitration and cross-domain commits:
  [CTRL-200](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority) and
  [CTRL-300](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules);
- terminal cleanup handoff: [CTRL-400](05-domain-controller-reconciliation.md#ctrl-400--cleanuproot-handoff).

## CORE-OWN-1 — Ownership, loans, and receipts

Every successful allocation or platform return is adopted immediately into one typed partial or complete owner.
Transfer consumes the prior owner's reference. Raw values, names, handles, Booleans, generations, or copied facts
cannot substitute for the exact owner, registered operation identity, occurrence, provenance, lease, and receipt.

An owner may expose the minimum raw object required for one outward call through a typed one-shot port/lease.
The consumer neither stores nor copies it beyond that operation. This is an audited protocol invariant; the
design does not claim Kotlin can prevent arbitrary same-module reference capture after valid exposure.

Each lease/use resolves mechanically once. A resource remains owned until all relevant leases and entered work
settle and its real release/free/return receipt permits the next dependency. For managed memory, dropping the
last engine reference after settlement is logical retirement only; JVM/allocator reclamation timing has no
receipt and no product authority.

Receipt types are canonical per physical boundary. A generic normal return is sufficient only where its domain
contract explicitly defines that return as the receipt. Physical ownership is never inferred from request,
deadline, cancellation intent, a safely settled absent resource, or a different operation's result.

## CORE-SET-1 — Generic operation occurrence

Every opaque, system, ownership-sensitive, or mandatory-release call owns one typed
`OperationOccurrence<R>` containing:

- nonreused identity and `Active`/`Cleanup` domain;
- one-shot scheduler-submission and entered dispositions;
- one nonfair occurrence-local `ReentrantLock` named `settlementGate`;
- one precreated typed `OperationReturnCell<R>` with a closed discriminator and fixed scalar/reference fields for
  the normal result, throwable, typed receipt, and already-existing returned owner;
- the exact owner bag travelling with unresolved work;
- for a finite boundary, one identity-fenced `DeadlineOccurrence` and its child wake link.

The gate, writable return cell, owner bag, deadline/wake state, and live worker/trampoline travel together through
active use, terminal transfer, cleanup, and quarantine. An operation-specific type may add closed evidence and
receipt fields but not another settlement machine.

Every cell, wrapper, timeout cause, owner slot, and fixed outside-gate action needed by return/failure handling is
created before outward entry. The return path can therefore record already-owned references, scalars, tags,
throwable, and receipts even under allocation pressure. Diagnostic strings, public wrappers, and result-derived
objects are created only after complete settlement and gate release.

## CORE-SET-2 — Entry, return publication, and lock order

Immediately before an outward call, the worker takes only the exact `settlementGate`, confirms current unresolved
submission/domain, commits Entered, and, for a finite occurrence, checks and arms its deadline. It releases the
gate, signals the controller, and invokes the outward call once on the same stack without waiting for the
controller. There is no controller round trip between entry commit and invocation; a terminal/expiry contender
in that interval cannot cancel the already-entered call.

After normal return or throw, the worker fills only the precreated evidence. It takes the same gate, completes all
fixed fields, samples finite settlement time `T` immediately before publication when applicable, commits the
one-shot complete cell, releases the gate, and then signals. A worker never takes `sessionGate`.

The only nested lock order is:

```text
sessionGate -> one exact settlementGate
```

Controller arbitration may hold that pair only to read identities/dispositions, sample `EngineClock`, consume a
complete cell, select expiry/cancellation/transfer, and write existing references/tags/scalars. It performs no
allocation, scheduling, outward call, publication, diagnostics, release, or cleanup while either gate is held.
Workers and callbacks that do not arbitrate Session admission never acquire `sessionGate`.

Callback admission is the bounded exception: its trampoline takes `sessionGate -> its settlementGate`, commits
entry or detached self-rejection, and releases both before invoking application code. Terminal uses the same
order against that record.

Synchronous engine submission rejection for an owner Runnable settles only a current unresolved submission while
that operation is unentered and its return cell is empty. Existing cancellation, terminal, entry, return, or
cleanup disposition wins. Active rejection of required work is `InternalFailure`; rejection of mandatory cleanup
preserves its owner and one-shot obligation. Caller dispatcher return/throw/rejection remains an external-call
fact.

## CORE-SET-3 — Finite-deadline arbitration

For a positive duration `C` and nonnegative start `S`, finite occurrences require
`S <= Long.MAX_VALUE-C` and compute `D=Math.addExact(S,C)`. Guard failure is existing checked-arithmetic
`InternalFailure`. The domain leaf owns `C`, its exact start point, result classification, diagnostic source, and
whether an operation is finite.

| Observation under `settlementGate` | Exact authority |
| --- | --- |
| complete cell with `T < D` | apply its timely typed outcome and retire deadline |
| complete cell with `T >= D` | expiry wins; retain the actual returned fact/receipt for cleanup only |
| empty cell with sampled `now >= D` | commit expiry and preserve the writable cell/owner bag for a mechanical late return |
| return after expiry, retirement, or active-to-cleanup transfer | commit the real fact once; reduce only its exact cleanup residue |

Equality belongs to expiry. A worker paused after sampling `T` still owns the gate, so that sample remains the
linearization authority when it completes publication. Timer enqueue/dequeue/run order, controller observation,
and signal timing never replace `T`/`D` authority.

When [CTRL-030](05-domain-controller-reconciliation.md#ctrl-030--lifecycle-and-terminal-application) applies the
terminal cutoff, it first folds a complete active cell under this same rule. A safely cancellable unentered
occurrence with empty entry/return cells resolves directly. An entered, in-call, externally accepted, or
mandatory-cleanup occurrence transfers intact; cancellation never fabricates its return.

## CORE-WAKE-2 — Deadline wake link and scheduling rejection

A finite parent owns one minimal `DeadlineWakeLink`, not a separate lifecycle domain. Its phase is exactly
`Unarmed`, `Armed`, `Expired`, or `Retired`. It precreates one generation-tagged wake whose submission moves
through `None`, `Requested`, `Submitting`, `Accepted`, or `Rejected`, plus an independent one-shot Fired fact.
The physical Session scheduler resource and its ownership/shutdown binding are owned by
[CTRL-040](05-domain-controller-reconciliation.md#ctrl-040--session-scheduler-resource); this section owns only
the generic per-occurrence wake and settlement protocol.

Arming under the parent gate moves `Unarmed -> Armed` and the current wake submission `None -> Requested`.
The controller claims that generation as `Submitting`, calls
`schedule(wake, max(0, D - EngineClock.now()), NANOSECONDS)` unlocked, then publishes the exact Future or
rejection under the parent gate. The callback checks the generation, samples `EngineClock`, publishes only its
one-shot Fired fact, releases the gate, and signals. It may fire before Future publication; an early fire is
evidence only.

A successor waits until both the Fired side and the Future-or-rejection publication side settle, preventing an
orphan Future. A current active wake-submission rejection is `InternalFailure` even though its finite parent is
already Entered; a prior retirement, cancellation, terminal, stale-generation, or active-to-cleanup disposition
remains authoritative and makes the rejection cleanup-only. Retirement/cancellation is identity-fenced,
accepted-Future cancellation occurs unlocked, and a stale callback can settle only its own generation.

Queued engine work has no queue-start timeout. Scheduler nonprogress is not an operation return and cannot prove
cancellation, release, or safe replacement.

## CORE-TIME-1 — Clock and named completion rules

`EngineClock` is the sole interval clock and returns raw `SystemClock.elapsedRealtimeNanos()`, including deep
sleep. It governs pacing, repeat, produced-frame timestamps, Stats cadence, readiness, operation boundaries, and
duration samples. Wall time is used only for diagnostic correlation. Uptime, `System.nanoTime()`, producer
timestamps, timer order, and scheduling delay have no control authority.

Finite private values are positive implementation policy, not public SLAs. Each applies to one named occurrence;
consecutive stages receive independent windows rather than one compound product-latency promise. Domain contracts
own the current metrics, captured-resize, Android, GL, and JPEG values and exact call sets.

The shared watchdog exclusions are:

- queued engine work before entry;
- long-lived metrics collection after first readiness;
- caller-dispatch invocation duration;
- entered application callback duration;
- blocking or Unconfined observation collectors;
- scheduler progress and public/diagnostic Flow publication;
- every post-terminal cleanup call/completion;
- each domain operation explicitly converted to terminal no-watchdog cleanup.

Terminal-before-entry either safely resolves an optional unentered occurrence or converts the same mandatory
occurrence to its named no-watchdog cleanup path. Terminal-after-entry retires an active deadline and transfers
the intact occurrence. No exclusion authorizes a retry or a fabricated receipt.

## CORE-ALLOC-1 — Checked allocation and failure partition

Every API argument, byte count, range, stride, cumulative length, and narrowing is checked before the applicable
allocation/outward call. Checks use exact integer arithmetic and explicit bounds. A checked logical byte count
does not claim opaque texture, gralloc, Bitmap, allocator-metadata, driver, or JVM physical size.

Actual allocation/creation return, coherent boundary error, or directly thrown allocation OOM is authority.
The engine never samples available memory, predicts future success, or creates cross-Session headroom policy.

The common precedence is:

1. ownership ambiguity, malformed evidence, contract violation, or unexpected non-OOM failure is
   `InternalFailure`;
2. otherwise exact coherent required allocation exhaustion is `ResourceExhausted`;
3. only then may a documented returned result select success, ordinary rejection, or safe optional fallback.

Only OOM at an explicitly enumerated allocation boundary receives that boundary's ordinary classification.
Unexpected fatal JVM `Error` follows the owning domain's fatal boundary and is never normalized into ordinary
cleanup. A partially returned resource is immediately owner-bag state; a catch path must not depend on fresh
allocation to retain it.

## CORE-EFF-1 — Structural efficiency and reuse

Efficiency is expressed as bounded ownership, not predictive accounting:

- one current or occurrence-owned capture target;
- one healthy compatible output topology;
- one stable exact CPU-pixel carrier;
- only the Framework resources required by the selected backend;
- one tentative/completed-unpublished production role, one latest payload, and at most one displaced leased
  payload;
- one latest desired cell, one reconciliation occurrence, one pending-source role, one production occurrence,
  one current registration/handoff, and bounded operation/deadline records;
- quarantine contains only exact already-owned unresolved children.

Healthy exact-compatible owners are reused by actual owner identity plus their immutable shape/generation/health
facts. Incompatible replacement first settles uses and real release obligations, then performs a fresh
current/nonterminal check and one replacement allocation. Unsafe old ownership terminally closes production
rather than authorizing overlap. Managed logical detach may overlap later JVM reclamation without asserting a
second live engine owner.

Concurrency between Sessions is valid, but aggregate pressure and opaque platform/global-driver serialization
remain outside an individual Session guarantee. Private owner lanes isolate engine queues and lifecycle; they do
not promise progress through vendor-global locks or the shared Kotlin dispatcher implementation.

## Publication and diagnostic routing

- public State, Stats, result, and diagnostic schemas:
  [PROD-060 through PROD-080](02-product-contract.md#prod-060--state-and-errors);
- controller commit, cutoff, sequence, and decision authority:
  [CTRL-200](05-domain-controller-reconciliation.md#ctrl-200--policy-attempt-counter-and-observation-authority) and
  [CTRL-300](05-domain-controller-reconciliation.md#ctrl-300--cross-domain-commit-rules);
- unlocked coherent State construction, Stats construction/cadence, and diagnostic construction/emission:
  [DEL-OBS-001](12-domain-delivery-observation.md#del-obs-001--coherent-state-and-publication-order),
  [DEL-OBS-010](12-domain-delivery-observation.md#del-obs-010--stats-accounting-and-cadence), and
  [DEL-OBS-020](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission).

## CORE-CLEAN-1 — Cleanup forest and dependency graph

One empty `SessionQuarantineRoot` exists from Session creation before work can escape normal ownership. Terminal
commit closes admission, invalidates cached production as applicable, and transfers each present owner into an
independent cleanup-root record before public terminal assignment.

Cleanup is a forest. Each domain leaf defines its local receipt order; a returned failure is recorded and every
independent reachable root continues. A nonreturn blocks only that call's physical dependents and serial suffix,
not unrelated roots or another Session. Cleanup never blocks one owner lane waiting for another; completion
arrives through its precreated fact.

Each root stores the exact occurrence, settlement gate, writable return cell, domain/deadline state, owner bag,
worker/trampoline where applicable, and transitive resources still owned by it. Logical active-to-cleanup
transfer occurs under the normal gate order; physical release/free/detach runs unlocked.

Real typed receipts alone remove obligations. An already absent resource may be structurally marked
inapplicable only after the relevant construction/entry state proves absence; it never receives a fabricated
release receipt. Managed references are dropped only after exact uses settle, without claiming physical
reclamation. Non-owned process/runtime services never enter quarantine and have no invented shutdown receipt.

## CORE-CLEAN-2 — Quarantine, nonreturn, and late reduction

Unsafe residue attaches once to `SessionQuarantineRoot`. If ambiguity wins before terminal, it supplies terminal
`InternalFailure`; if another terminal winner already committed, the same residue attaches without changing
State. The root contains only resources already owned by the Session and admits no new production work.

Nonreturn is never implicit cancellation, failure return, or release. It retains exactly the writable occurrence
and every resource that the incomplete call may still use or own. Cleanup may proceed for proven independent
siblings. If the call later returns, its identity-fenced fact can release or shrink only that exact child after
all dependent receipts become valid.

No cleanup/quarantine fact may revive a Session, reopen admission, install a replacement, publish stale bytes,
change backend health, add active counters, or rewrite the terminal winner. Resolution before an actual root
mutation emits no quarantine-change event; a real attach/reduction/removal requests the one best-effort diagnostic
defined by [DEL-OBS-020](12-domain-delivery-observation.md#del-obs-020--diagnostic-construction-and-emission).

Rare ambiguity may retain substantial memory or threads until process death. A new Session with fresh consent may
still be attempted, but it receives no guarantee that opaque process/driver damage from an earlier Session has
recovered.

## CORE-PRIV-1 — Data lifetime and privacy

Raw pixels, mutable encoder input, tentative encoded segments, immutable payloads, native writer residue, and
borrowed frames remain behind their exact owner/lease boundaries. Failed, partial, stale, or unleased data never
publishes. Reusable CPU storage is cleared as required after its last lease before cross-purpose reuse.

Only caller-requested `copyBytes`/`copyTo` transfers JPEG bytes into caller ownership. Allocator, gralloc,
driver, OS, and JVM zeroization/reclamation are outside the engine contract. Diagnostics and text representations
never expose raw pixels, encoded bytes, raw handles, tokens, callback/provider/dispatcher objects, or copied
Throwable text.
