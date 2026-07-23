# Screen Capture Engine — Delivery and Observation

## Scope and authority

This document is the sole mechanics authority for pacing and repeat calculation, execution of
Control-authorized pacing/repeat wake actions, callback handoff, borrowed-frame validity, unsubscribe
convergence, unlocked State/Stats publication, diagnostic construction/emission, and the terminal diagnostic
sink. Product values and Architecture's wake inventory and semantic terminal/activation order are excluded.

Authority remains divided as follows:

- [Product Contract](01-product-contract.md) owns every caller-visible schema, formula, value, cadence, drop,
  exception, callback, cache/repeat, unsubscribe, terminal, and observation outcome.
- [Internal Architecture](02-internal-architecture.md) owns Control and lifecycle authority, execution-role shape,
  `sessionGate`, currentness and the three cross-boundary identities, the delayed-wake inventory, global maxima,
  terminal cutoff/transfer, typed retirement fields, and the fixed terminal activation order.
- [Framework Encoding and Encoded Storage](05-framework-encoding-storage.md) owns immutable payloads, caller-copy
  storage, `FrameStore` roles/transitions, and the storage effect of an identity-fenced lease release.
- [Verification](08-verification.md) owns executable proof, tests, matrices, tasks, and acceptance evidence.

The mechanics here consume already-selected immutable Control inputs and return closed typed facts. They do not
select lifecycle, currentness, fallback, counters, cache policy, terminal winner, public values, or diagnostic
vocabulary. A helper calculation, callback return, publication result, or diagnostic loss is never an alternate
authority.

## Pacing, source coalescing, and repeat

### Pending source and one materialization

Capture's current `SourceCandidate` is the durable, conflated representation of source availability defined by
internal architecture. Repeated current source notifications only preserve that one candidate. A retired-source
notification is inert. The pacing calculation receives one immutable Control snapshot containing the exact
current candidate, lifecycle/admission state, production occupancy, policy, last actual grant times, rational
phases, cache-currentness result, and an elapsed-realtime sample.

The calculation returns one of these proposals without mutating its input:

- retain the candidate because cadence or capacity is not yet eligible;
- consume the exact candidate into one materialized fresh attempt;
- grant one completed current fresh output;
- grant one current repeat;
- arm or replace the applicable delayed wake at the earliest checked eligibility time; or
- do nothing because the input is stale, paused, empty, or terminal.

Control rechecks the complete snapshot and alone accepts a proposal. A racing source notification after an
accepted consume remains represented for a later turn. Retention before materialization is neither loss nor a
drop. Pause, suspension, reconfiguration, or terminal may discard an unmaterialized candidate without creating
a counter fact. Once materialized, the attempt stays represented by the architecture's sole production record
until Control selects exactly one final disposition.

The physical disposition fact is narrow: capacity lost at materialization reports pipeline-busy; mechanically
returned production failure reports failure even if its identity later became stale; otherwise-successful work
suppressed only by stale identity reports stale-work; current output/cache commit reports success. Terminal
retirement of an unclassified attempt or completed-unpublished payload reports no drop. Product Contract §10 is
the sole owner of the public counter membership and saturation result.

### Exact fresh and output cadence arithmetic

Elapsed realtime, including deep sleep, is the only pacing clock. A producer timestamp never participates in
eligibility, a required gap, or public output timestamp selection. Millisecond intervals are converted to
positive nanoseconds with checked arithmetic before use.

Fresh eligibility is calculated as follows:

- `Auto` adds no time gate: a current pending source is eligible as soon as Control-supplied capacity permits.
- `SampleEvery(intervalMillis)` grants the first current source immediately. Each later fresh grant is eligible
  only when `now - lastFreshGrantNanos >= intervalNanos`, using checked nonnegative elapsed arithmetic.
- `MaxFps(fps)` applies one rational gate to fresh grants and an independent rational gate to all produced-output
  grants. A fresh output must pass both applicable gates. A repeat passes the output gate. Cached-first delivery
  is not an output grant and does not pass through either gate.

For each `MaxFps` gate, let:

```text
S = 1_000_000_000
F = fps
q = S / F
r = S % F
phase is in 0 until F
```

On an actual grant only:

```text
sum = phase + r
carry = if (sum >= F) 1 else 0
nextPhase = sum - carry * F
requiredGap = q + carry
```

The gate commits `nextPhase` and anchors `requiredGap` at that actual grant's elapsed-realtime sample. A denied,
stale, early, paused, or superseded proposal advances neither phase. Fresh and output phases never share state.
This retains rational cadence without truncation drift and creates no catch-up entitlement.

### Repeat eligibility and cache use

A repeat proposal is eligible only when all of the following were true in the same Control snapshot:

- lifecycle and output admission are Active;
- repeat policy is nonnull and its checked interval has elapsed since the latest actual fresh/repeat output
  commit;
- the latest immutable payload remains current and valid for the committed effective descriptor;
- the output `MaxFps` gate, when present, is eligible; and
- no completed current fresh output is simultaneously eligible.

Fresh wins the tie. Repeat timing is a best-effort maximum-silence target, not a deadline. An early or delayed
wake merely causes a new calculation; a late calculation grants at most one output and never catches up.

The cache-currentness input is true only for the exact current capture geometry and image-affecting plan—region,
crop, output size, rotation, mirror, color, and JPEG quality—and the exact installed Target, render, and encoder
state that produced the payload. Pause makes cache use unavailable. An untrusted source, image/backend/topology
change, Target/output rebuild, fallback, or terminal invalidates use. A frame-rate-only or repeat-policy-only
change may preserve the immutable bytes, but use is rechecked against the complete current state after Active
resumes. Control owns that currentness decision; this chapter defines only how an accepted decision is executed.

An accepted repeat sends `FrameStore` an already-authorized transition retaining identical payload backing and
installing only Control-selected new sequence, elapsed-realtime timestamp, and current effective descriptor. It
performs no capture, readback, encode, payload allocation, or payload copy. An accepted cached-first offer uses
the cached output's original bytes and metadata and changes no cadence phase. Exact storage-role transitions are
owned only by the encoding/storage document; this chapter creates no second payload or lease store.

### Pacing and repeat wake execution

Internal architecture supplies the applicable current identity-bound Control-Handler wakes and their
cardinality. This document only executes the authorized pacing/repeat wake actions; it does not add a timer,
scheduler, queue, generation, or execution role.

For an authorized wake action, the pacing collaborator posts the immutable wake token and checked target time to
the Control Handler. A fired Runnable first verifies that exact token is still current before reading pacing
state or the clock. A stale Runnable returns without calculation. A current Runnable resamples elapsed realtime,
builds one proposal, and lets Control recheck and accept at most one action and at most one successor wake.
Removing or superseding a queued Runnable is only coalescing; it is never proof of entry, return, progress, or
eligibility.

An early wake rearms only from newly calculated current eligibility. A late wake produces no burst. Failure to
post a required current wake returns one typed scheduling-failure fact for Control's architecture-owned
fail-closed decision; rejection after the token is stale, detached, or terminal is cleanup-only. No pacing or
repeat path inspects private Handler queues or waits for another role.

## Callback delivery

### Registration, opportunity coalescing, and handoff creation

The front door and Control allocate and apply `registrationId`; Delivery stores only the exact callback and
physical state for the Control-authorized current registration. Registration itself performs no cached offer,
payload acquisition, or callback work while output admission is paused.

As part of a successful registration while Active, Control atomically rechecks the current cache and, when it is
eligible, authorizes cached-first through the ordinary handoff path immediately. A registration created while
paused persists without an offer or waiting record; after Active resumes, Control performs the same current-cache
check before authorizing any preserved bytes. An absent or stale cache creates no handoff.

For an Active delivery opportunity—fresh output, repeat, or cached-first—Control either authorizes one handoff
or applies the Product Contract outcome for an occupied handoff. Delivery has no waiting-offer slot and creates
no callback queue. An opportunity with no current registration creates no delivery work or drop. An opportunity
while the prior handoff is not fully converged returns one consumer-busy fact; Control alone applies it once.

Handoff construction binds, before submission:

- the exact `registrationId` and callback identity;
- immutable bytes metadata and the counted one-shot `FrameStore` lease;
- the exact sequence, timestamp, and effective descriptor selected for that offer; and
- one durable `DeliveryCapsule` entry state and closed-completion slot.

Construction is all-or-nothing. A failure before a lease is attached creates no handoff. A failure after
attachment retains that exact lease in the capsule until a real local release. Submission acceptance is not
callback-entry proof.

### Non-direct serial execution and callback entry

Delivery uses the architecture's distinct asynchronous non-direct serial blocking role with its own permit. It
adds no private submission, execution, or role-lifecycle mechanism. Submission never invokes user code inline on
Control, Capture, JPEG, Bootstrap, or a caller.

At the Delivery task's first action, it enters `sessionGate` and compares the exact capsule, registration,
handoff admission, and terminal/unsubscribe cutoff. It changes that capsule only from `Queued` to:

- `Entered`, when the admitted handoff and registration still match and no cutoff has won; or
- `CutoffInert`, when terminal/unsubscribe cutoff won first or the handoff no longer matches.

The gate is released before the callback or any payload copy. Only `Entered` may invoke user code. Submission
acceptance, queue position, task selection, or a later Active state cannot substitute for this entry decision.
If cutoff wins, Delivery invokes no callback and closes the exact borrowed authority/lease locally.

A reconfiguration pause does not revoke a handoff whose admission won first. Its task may enter while State is
`Reconfiguring`, using its immutable old metadata and registration. This is the grandfathered draining handoff;
reconfiguration neither waits for nor interrupts it. Serial Delivery and the unresolved-handoff exclusion keep
a later callback from overlapping it. If pause wins before handoff admission, no lease, task, or callback entry
is created.

A current submission rejection returns one closed internal-delivery-failure fact; Product Contract §8 and
internal architecture own its terminal classification. A stale or terminal rejection only closes the exact
local capsule and lease. There is no delivery retry, callback-entry watchdog, callback execution watchdog,
interruption, or fabricated return.

### Callback return, failure, and handoff convergence

Immediately before invoking the callback, Delivery records the executing thread and opens the borrowed-frame
authority token. Invocation occurs outside every engine gate. A `finally` boundary closes that token
synchronously before any completion publication and performs the one-shot lease release as soon as user code
returns or throws.

Normal callback return creates one normal closed callback fact. A thrown `Exception` creates one callback-failure
fact containing that identical throwable. Control alone applies the Product Contract's counter, diagnostic, and
registration-continuation outcomes. A directly thrown `Error` or other non-`Exception` throwable closes only
the safely closable borrowed authority and lease, then passes the identical object to the fixed architecture
fatal boundary; it is not converted to a delivery drop or ordinary diagnostic.

Borrowed authority and the lease are released at callback return even if installing the task's closed result is
still physically pending. Until that result wins the matching entry-versus-cutoff arbitration, the handoff
remains occupied: another opportunity yields consumer-busy, and unsubscribe has not physically converged. A
late install never reclassifies the callback.

When admission remains open, Delivery installs only the exact typed completion under `sessionGate` and requests
the coalesced Control turn. If terminal cutoff already owns the capsule, Delivery performs the matching
one-shot lease/storage release and typed terminal-sink reduction locally, without a Control wake. A late return
cannot mutate State, Stats, counters, cache, registration outcome, or terminal cause. Permanent callback
nonreturn retains the exact callback authority and lease as architecture-owned Delivery residue; no replacement
worker or lease is fabricated.

### Borrowed frame and payload lease

The callback receives a façade over the handoff's immutable metadata and counted payload lease. Every property,
`copyTo`, and `copyBytes` first verifies both the exact callback thread and the still-open authority token. A
wrong thread or closed token rejects the access before it reaches payload storage. The Product Contract owns the
thrown exception; the encoding/storage document owns full-range validation and the actual segmented copy after
validity succeeds.

Fresh and repeat handoffs expose their committed output metadata. Cached-first exposes the original cached
metadata. A grandfathered handoff retains the metadata fixed at admission even if a newer Active configuration
exists before callback entry. No raw segment, mutable buffer, transaction, tentative bytes, storage owner, or
lease handle escapes through the façade. Only a caller-created copy may outlive the callback.

Lease release is identity-fenced and one-shot. Normal return, callback `Exception`, fatal unwinding where safely
reachable, and cutoff-before-entry all execute the same local release authority. Duplicate release changes
nothing. Release may detach a displaced payload only through the exact `FrameStore` transition owned by the
encoding/storage document.

### Unsubscribe execution

`unsubscribe` uses the exact registration and handoff identity; this section defines its physical convergence,
while Product Contract §7 owns every caller-visible result and replacement rule.

The first valid call closes new admission for that registration under `sessionGate`, captures the one shared
convergence object, and then waits without holding an engine gate. With no handoff, the registration can close
immediately. With an unentered task, entry arbitration makes it inert before success. With an entered callback,
success waits until callback authority, lease, and the task's closed handoff fact have all actually converged.

Self-unsubscribe is detected by the entered callback's exact registration and executing-thread marker before
admission is changed; it reports the Product Contract outcome and leaves the registration open. Cancelling one
waiting caller detaches only that waiter. It does not reopen admission, cancel the callback, release a lease,
fabricate convergence, or change the shared result. Repeated calls observe the same monotone result. Terminal
cutoff resolves the public waiter according to the Product Contract but does not convert unresolved physical
residue into successful unsubscribe or replacement permission.

## Observation publication

### Common unlocked publication rule

Control supplies an already-selected immutable construction input only after its authoritative fields and
outward actions are fixed for the turn. State/Stats builders and the ordinary diagnostic publisher retain no
controller state and do not join facts, sample platform state, recheck currentness, compute lifecycle, or revise
the input.

Construction and publication execute directly at the unlocked end of the Control turn. No `sessionGate`,
resource lock, or role-local resource lock is held. There is no Observation lane, worker, actor, queue, or
dispatcher. Publication is allowed to resume `N` collectors and therefore may cost O(N); that cost is never
moved beneath a resource lock and does not make collector progress an engine receipt. Collectors must follow the
nonblocking, non-reentrant Product Contract.

### State publication

One State input contains every field of one selected immutable public value. Construction reads only that input,
so requested parameters, effective/history fields, geometry, visibility, problem, and terminal fields cannot be
mixed across Control commits. The publisher assigns that complete value directly to the Session's
`MutableStateFlow`. Equal values follow StateFlow equality conflation; unequal rapid assignments may be observed
as latest-value conflation, never as a partially assembled State.

The publisher does not make State assignment and StateFlow collector resumption one event. It supplies no
synchronous setter-return/collector oracle. Startup and reconfiguration ordering relative to outward effects,
and terminal cutoff, remain owned by internal architecture and the Product Contract.

### Stats construction and publication

One Stats input contains the authoritative counters, totals inputs, ordered accumulator projections, byte
fields, and protection decisions selected by Control. The builder applies exactly the public formulas in Product
Contract §10 and returns one immutable Stats value; it does not own or update an accumulator, counter, cutoff,
dirty bit, wake, or cadence.

Control requests an ordinary assignment only for a dirty public value at the Product Contract cadence. The
publisher assigns the complete value directly to the Stats `MutableStateFlow`; equality follows StateFlow
conflation. The creation-time zero snapshot is installed once, accepted start does not reassign it, and
reconfiguration alone causes no equal assignment. The Stats delayed wake is part of the architecture-owned wake
inventory, not an observation worker. Final Stats bypasses ordinary cadence and is published by the terminal
section below.

State and Stats are separate StateFlows. Their individual values are coherent, but no combined atomic snapshot
or cross-Flow collector order is created.

### Ordinary diagnostic construction and emission

Control selects whether an ordinary event is required and supplies one immutable request containing source,
label, semantic message inputs, and the exact cause. The observation publisher owns only mechanical sequencing,
construction, and emission. Under its private short `diagnosticGate`, it reserves the next positive nonreused
Session sequence, samples wall time for correlation, constructs the immutable event, builds a short semantic
message without copying Throwable text, and calls `tryEmit` exactly once on the Session's diagnostic
`MutableSharedFlow`.

The SharedFlow configuration, vocabulary, fields, causes, sequence outcomes, and facade lifetime are owned by
Product Contract §11–12. Mechanically, the Session constructs the Product-configured diagnostic Flow once and
each accepted request receives only one `tryEmit` call; no emission is retried. Allocation failure, emission
failure, overflow, collector absence/delay, and raw-cause retention change no engine state.

`diagnosticGate` is the private observation-sequence/emission serializer. Its complete authority is mutual
exclusion for diagnostic sequence reservation, construction, `tryEmit`, and the exact typed post-terminal
cleanup observations below. It is not lifecycle, resource, currentness, counter, fallback, or cleanup-policy
authority and cannot select a transition. An architecture-defined typed transition wins before its observation
enters this gate. `diagnosticGate` is never held at the same time as, or nested with, `sessionGate`. Per
[Internal Architecture](02-internal-architecture.md), `sessionGate` remains the sole cross-component gate for
semantic/lifecycle decisions and resource admission.

### Terminal publication section

Internal architecture owns terminal selection, cutoff, final accounting, initialization of the four typed
retirement fields, and who calls this section. `publishTerminal` receives only the already-selected final Stats,
terminal diagnostic request, and terminal State. With no lifecycle or resource gate held, one private contiguous
call performs exactly:

```text
assign final Stats
-> reserve/construct/tryEmit the terminal diagnostic once under diagnosticGate
-> assign terminal State
-> switch the diagnostic sink to TerminalCleanupOnly
```

No cleanup transition or collector callback may be awaited inside the section. Emission failure does not skip
terminal State or the mode switch. The section performs no `Attach`. After it returns, internal architecture
alone performs its defined same-turn semantic activation sequence; this document supplies only the invoked typed
sink mechanics.

Before the switch, only semantic authority may supply ordinary diagnostic requests. After it, ordinary requests
are rejected inertly and only the four exact typed cleanup entry points below may reserve a sequence and attempt
an event. The mode switch is diagnostic routing, not a lifecycle state.

### Typed terminal cleanup diagnostics

The terminal sink contains only `diagnosticGate`, the diagnostic sequence/emitter, its mode, and four separate
typed retirement transitions. There is no collection, registry, common capsule base, generic transition API,
loop, cleanup hierarchy, scheduler, worker, wake, or retry. Internal architecture defines the semantic activation
order and invokes the concrete identity-bound methods; a matching role return calls only its own concrete method.

For each architecture-defined typed retirement field independently, activation and a real matching return are
the only competitors:

- If the durable return wins first, its typed transition changes `ReturnExpected(exact identity)` to `Closed`
  and makes no cleanup diagnostic attempt.
- If activation wins first, its typed transition changes that exact `ReturnExpected` to
  `ProcessLifetimeResidue` and immediately makes one attempt using the Product-owned cleanup diagnostic
  source/label and semantic action `Attach`.
- A later real matching partial reduction that leaves nonempty residue replaces only that exact residue and
  makes one attempt using the Product-owned cleanup diagnostic source/label and semantic action `Reduce`.
- A later real matching reduction that empties the residue changes it to `Closed` and makes one
  attempt using the Product-owned cleanup diagnostic source/label and semantic action `Remove`.

Each successful typed mutation fixes its semantic action before entering `diagnosticGate`; the sink then
reserves the next sequence, constructs the event with the Product-owned cleanup diagnostic source/label, the
action in the noncontractual message, and the supplied raw cleanup cause, and calls `tryEmit` once. Calls are
emitted in that gate's reservation order. Emission failure does not roll back ownership and is never retried.

A duplicate activation, duplicate return, stale identity, empty reduction, already-closed field, or losing
typed compare-and-set mutates nothing and emits nothing. No call can emit two actions. A pre-switch durable
return remains rooted by its exact capsule for the architecture-owned activation to consume. A post-switch
return invokes its typed sink entry directly, without Control or a wake, and can reduce only that exact residue.

The terminal sink cannot select terminal winner, lifecycle, State, Stats, fallback, retry, registration result,
cache, arbitrary diagnostics, or cleanup work. `Attach`, `Reduce`, and `Remove` report exact physical ownership
changes only; they are not cleanup-completion promises and cannot reopen admission or authorize replacement.

## Bounds and forbidden mechanics

All production, source, registration, callback, handoff, payload, lease, role, and delayed-wake maxima are owned
only by internal architecture. This document operates within those maxima and adds no private numeric policy,
timer family, lane, queue, generation, or resource owner. Slow or permanently blocked application code can
occupy only the architecture-defined Delivery handoff/lease residue while Control, Capture, and JPEG remain
independent.

The implementation must not introduce any of the following through delivery or observation:

- a private Delivery execution/lifecycle mechanism in addition to the architecture-owned serial role;
- a callback queue, waiting delivery offer, per-frame coroutine, callback watchdog, interruption, or retry;
- a second lifecycle/currentness/counter/cache/publication-decision/terminal authority;
- an observation lane, observer queue, resource-locked publication, synchronous collector oracle, or Flow close;
- a generic cleanup hierarchy, common typed-retirement registry, or cleanup-completion façade;
- payload access without a live callback token and exact lease, fabricated release, or a public mutation from a
  late terminal return; or
- Stats formula copies, verification matrices, test tolerances, or runtime policy selected by diagnostics.
