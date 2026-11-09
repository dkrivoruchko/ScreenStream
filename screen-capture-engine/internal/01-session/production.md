# Frame production, pacing, and statistics

The public [Architecture](../../docs/architecture.md#android-capture-and-jpeg-pipeline) explains the frame path and bounded-work design. [Usage](../../docs/usage.md#frame-timing) defines timing controls and [statistics](../../docs/usage.md#statistics) for callers. This page records the internal algorithms and accounting boundaries needed to change that path safely. [Session](session.md) supplies lifecycle/topology admission; [Coordination](coordination.md) supplies exact correlation and publication mechanics.

## Contents

- [Responsibilities and materialized work](#responsibilities-and-materialized-work)
- [Read bridge and production progression](#read-bridge-and-production-progression)
- [Pacing calculations](#pacing-calculations)
- [Wake scheduling](#wake-scheduling)
- [Output identity and cache compatibility](#output-identity-and-cache-compatibility)
- [Accounting before terminal freeze](#accounting-before-terminal-freeze)
- [Statistics calculation and publication](#statistics-calculation-and-publication)
- [Implementation and verification](#implementation-and-verification)

## Responsibilities and materialized work

`SessionProduction` owns one materialized fresh production, its cache, fresh/output pacing histories, one pacing-wake identity, output sequence, and accumulated statistics. Its candidates retain the owner, generation, and exact relevant frame/record/read identities. Coordinator revalidates those candidates together with current Lifecycle and Topology before commitment.

Capture owns physical source candidates and GPU reads; its Link records the coalesced opportunity used for session grants. Encoding owns the carrier, its loan, codec work, and tentative bytes. Production does not settle those physical resources by clearing its record. [Delivery](../03-output/delivery.md) owns callback entry and borrow lifetime; output commitment does not establish delivery.

The one-production bound spans input acquisition/read construction, installed Capture read, Encoding loan/transaction, and unpublished output. The Encoding Link reserves acquisition before physical work, while Production retains construction and later record/output state. A deferred encoded output continues to occupy that production; another fresh image cannot bypass it. Source availability remains coalesced rather than becoming a frame queue.

## Read bridge and production progression

Coordinator follows this sequence for fresh work:

1. Obtain a current Topology readiness candidate and sample elapsed realtime outside the gates. Recheck production admission, source opportunity, the one-production bound, and the Encoding Link slot.
2. Allocate one `SessionProductionRecord` containing exact owner, revision, and JPEG quality; install its Encoding request and acquire one exact `EncodingInput` loan.
3. Revalidate the acquisition and currentness. Construct a `SessionReadBridge` containing that record and loan, validating the exact writable RGBA range against the current layout. It is not yet installed in Capture.
4. Evaluate fresh pacing using the earlier time sample. Only a still-current grant installs the bridge in the Capture Link and commits the fresh cadence before dispatching Read. A deferred, stale, or invalid uncommitted grant discards its uninstalled input and advances no cadence; a pacing deferral retains the source opportunity for a later attempt.
5. Correlate the real Capture return, then authorize Encoding to encode current admitted input or discard it. Capture return alone does not make the carrier reusable.
6. Consume Encoding's settled result and account its mechanical work. Only a complete immutable payload that still belongs to current admitted production may become output.
7. Commit output identity and metadata, then ask Session Delivery to offer that exact published frame. The handoff token is installed before physical submission can expose callback entry.

The bridge has a single settlement decision. Before Capture request installation, Coordinator may directly discard the exact unentered loan. After installation, only a matching real return or definite pre-entry submission rejection permits settlement. A normal return is recorded once and claimed once. Terminal freeze can detach an unresolved bridge; its exact late return or exact late rejection can then claim detached settlement once. Acceptance, timeout, cancellation, reference loss, and terminal state are not substitute proofs.

A filled read records readback duration before deciding whether to encode. An obsolete filled read is discarded and counted as stale. A stop-only admission change discards otherwise-current input without marking it stale. Failed and cutoff reads settle through their typed paths. The [accounting table](#accounting-before-terminal-freeze) separates those samples from output eligibility.

Encoding can return a mechanically complete payload after its request becomes obsolete. It owns transaction completion, not semantic currentness. Production retains the returned payload as unpublished output only for the exact matching record; Coordinator then checks revision, plan, and admission. It never relabels old bytes with a newer configuration. An entered Capture read or encode that never returns continues to retain its exact loan and resource dependencies; there is no timeout-generated successor.

## Pacing calculations

`PacingCalculator` uses elapsed-realtime nanoseconds. It computes provisional decisions without mutating histories. A fresh-read grant advances fresh history only when the matching read is installed; fresh output advances output history only when the frame commits.

Fresh capture and output have independent histories:

| Policy | Fresh-capture eligibility | Output eligibility |
| --- | --- | --- |
| `Auto` | Immediate for a nonnegative time sample. | No rate delay. |
| `SamplingInterval` | First available fresh grant is immediate; later grants require the configured interval since the previous fresh grant. | No separate output delay. |
| `MaxFps` | Uses its own rational cadence history; a too-early read retains the opportunity. | Uses a separate rational cadence; a completed fresh payload may remain deferred. |

For a sampling interval `I`, later fresh eligibility is the checked sum `lastFreshGrantNanos + I`. A negative current sample, a retained prior time outside `0..now`, or deadline-addition overflow yields invalid evidence. No cadence is advanced for deferral.

### Rational MaxFps cadence

For valid `fps`, let `N = 1,000,000,000`, `q = N / fps`, and `r = N % fps`. Each history retains `(lastGrantNanos, phase, requiredGapNanos)`, with `phase` in `0 until fps`. A new history starts with phase 0 and no previous deadline.

On each eligible grant:

```text
sum = phase + r
carry = 1 if sum >= fps, otherwise 0
nextPhase = sum - carry * fps
nextRequiredGapNanos = q + carry
```

The committed history uses the actual grant's sampled time, `nextPhase`, and `nextRequiredGapNanos`. For an existing history, validate nonnegative ordered time and require the stored gap to equal `q + 1` when `r != 0 && phase < r`, otherwise `q`. Eligibility is the checked sum of last grant and stored gap. A malformed phase/gap or overflow is invalid evidence, not a request to reset history.

At 3 fps, successive required gaps are `333,333,333`, `333,333,333`, and `333,333,334` ns. A grant delayed by deep sleep advances one phase at its actual time, not every missed phase; there is no catch-up burst. Both fresh and output histories use the algorithm independently.

### History changes

When Control adopts parameters with a different `frameRate`, it resets `lastFreshGrantNanos`, both cadence histories, and the logical pacing wake. Admitting an equal retry does not reset the histories. Reconfiguration suppresses an obsolete wake and later recomputes eligibility from the retained or reset history as appropriate.

Invalid pacing evidence is offered as `InternalFailure`. These formulas schedule eligibility, not delivery deadlines; accepted work still has [ordinary progress limits](coordination.md#progress-limits).

## Wake scheduling

Production retains at most one pacing wake with a target time and configuration revision. Coordinator owns one stable Control runnable and separately records its posted identity.

- A pacing wake is replaced only by a strictly earlier target. Coordinator removes the previous Handler callback and posts the new logical identity outside session gates. A fresh-read or fresh-output commit clears its logical pacing wake.
- Entry clears the posted identity, settles only a matching logical wake, and requests Control only if admission and revision still permit it. Obsolete identity cannot authorize current work.
- A current scheduling failure clears that exact wake and offers `InternalFailure`. An obsolete scheduling result cannot clear a successor wake or fail it.

Posting computes `max(0, targetNanos - sampledNowNanos)` with checked subtraction and converts the remainder to ceiling milliseconds for the Control Handler. Clock calls, callback removal, and posting occur outside session gates. Entry requests a Control turn that recomputes production eligibility instead of treating Handler timing as a grant.

## Output identity and cache compatibility

`PublishedFrame` pairs one complete immutable payload with a positive session-local sequence, nonnegative elapsed-realtime output timestamp, and immutable `CaptureOutputInfo`. Fresh candidate construction proposes the next sequence, but only successful commitment consumes it and updates pacing/cache/statistics. Exhaustion at `Long.MAX_VALUE` offers `InternalFailure` without wrapping. The timestamp records output commitment, not source capture or callback entry.

| Path | Bytes and metadata | Production effect |
| --- | --- | --- |
| Fresh output | New complete payload and its applied output information. | Commits a new sequence/timestamp and becomes latest frame. |
| Cached-first delivery | Exact existing published frame, including its old sequence, timestamp, and metadata. | Offers that frame to a newly eligible registration; no output commit or cadence advance. |

Production owns the latest-frame identity and invalidates only an exact still-current cache candidate. Topology decides image compatibility. `sameCachedImageParameters` compares these seven fields by value: `sourceRegion`, `crop`, `outputSize`, `rotation`, `mirror`, `colorMode`, and `jpegQuality`. It excludes `frameRate`. `sameCachedOutputInfo` additionally requires equal capture geometry, applied source rectangle, and final image size.

Reuse also requires current production readiness: the physical Capture configuration must match, no Capture Apply may be pending, and the exact encoder plan must be ready without pending reconcile. Session admission and the exact cache/registration candidates are revalidated before offer. A Native-health transition invalidates affected Encoding readiness and cache before later Framework reconciliation; it does not permit same-frame retry.

Raw parameter object identity is not the cache comparison. Image-affecting values still matter even if two requests happen to resolve to the same physical Capture rectangle and output shape. A `frameRate`-only update can preserve the payload and the old published frame; cached-first then keeps that frame's older `outputInfo`, while the next fresh output uses current applied metadata. [Usage](../../docs/usage.md#read-frame-identity-and-output-information) explains how callers interpret these snapshots.

Incompatible image changes, affected geometry/readiness changes, suspension, and terminal commitment invalidate relevant cache state. An already-admitted callback still retains its own exact frame independently. Cache eligibility permits an offer; it does not guarantee a consumer or free delivery slot.

## Accounting before terminal freeze

Selecting a physical result, incorporating its measurements, committing output, and assigning Stats are different events. Once a result has been selected for consumption, a racing stop can close ordinary admission without removing its eligible mechanical sample. Revision, plan, or record obsolescence is assessed separately.

The result handlers use these rules before final freeze:

| Consumed evidence | Accounting and continuation |
| --- | --- |
| Filled Capture read | Add its successful readback duration. Current admitted input may encode; obsolete input adds stale-work drop and discards; stop alone discards otherwise-current input without a stale drop. |
| Failed Capture read | Add a production-failure drop, then discard the exact input. Currentness/owner-invalidating scope determines the session failure consequence. |
| Cutoff-inert Capture read | Discard and clear its production; the cutoff itself adds no readback sample or drop. |
| Successful Encoding result | Add encode count, duration, and byte-size samples before currentness is decided. Obsolete or mismatched production adds stale-work drop; otherwise-current output suppressed only by stop does not. |
| Framework `FrameFailed` | Add one production-failure drop and clear production; retain the backend's safely settled availability. |
| Native `ReadinessChanged` | Add one production-failure drop and clear production. While admission remains open, invalidate affected readiness/cache for later reconciliation. |
| Encoding `Failed` | Add one production-failure drop and clear production. Offer terminal failure only when its production is current and admitted. |
| Encoding `CutoffInert` | The defensive handler records a stale-work drop and clears production if consumed before freeze; it supplies no successful encode sample. In normal session wiring, Encoding retirement starts after freeze, so its cutoff results cannot enter session accounting. |
| Exact input-settlement failure | Add a production-failure drop unless its failed Capture read already supplied that drop; apply currentness to the failure consequence. |
| Fresh output commit | Increment produced count and update its first/latest output times, whether or not a consumer receives it. |
| Session Delivery reports consumer busy | Add one delivery-busy drop for that eligible opportunity. |
| Selected callback-failure fact | Add one callback-failure drop; diagnostic emission is optional. |

Source coalescing, pacing deferral, cached-first handoff, or output without a consumer do not themselves add a production drop. The absence of a consumer does not count as delivery busy. A definite current Delivery submission rejection follows session failure handling, not fabricated callback-failure accounting.

During terminal handling, Coordinator consumes recorded Delivery facts and exact returned Capture reads before claim. A selected Encoding result may also finish accounting after stop offers; terminal handling does not harvest an unselected Encoding result merely because it has returned. The final snapshot describes evidence incorporated before freeze, not every operation eventually completed. After `commitTerminal`, Production ignores accounting calls and drops its semantic record/cache/wake references. Outstanding physical loans remain with their exact owners/bridges for late settlement, without changing final statistics.

## Statistics calculation and publication

`SessionStatsAccumulator` retains independent successful readback and encoding sample counts. Readback samples update readback duration; successful encodes update encoding duration, encoded size, last encoded size, and encoded count. Output commits update produced count and the first/latest output times. Delivery and production drop counters have separate membership as above.

For each mean, use the online calculation in binary64:

```text
candidateMean = currentMean + (sample - currentMean) / newSampleCount
```

Keep the candidate only when finite and nonnegative; otherwise retain the prior mean. Duration means convert the stored nanosecond mean to Kotlin `Duration`. Encoded-size mean is `floor(mean + 0.5)`, capped at `Int.MAX_VALUE`, or zero without a successful encode.

For at least two output commits with positive first-to-latest elapsed span:

```text
averageProducedFps = (producedFrameCount - 1) * 1,000,000,000 / elapsedSpanNanos
```

Compute the ratio in binary64; use `Double.MAX_VALUE` if the result is nonfinite. With fewer than two commits or no positive span, report zero. The span includes quiet periods, suspension, and deep sleep between committed outputs; the value does not decay while no further output arrives.

Counters saturate at `Long.MAX_VALUE`; public drop totals use saturating addition. Saturated successful sample counts freeze their associated means. Encoded saturation still permits `lastEncodedByteCount` to change. Produced saturation stops count and first/latest timestamp advancement, so FPS freezes. These internal accumulation rules do not authorize wrapping an output sequence to produce another frame.

The dirty flag tracks changes to exposed statistics. For example, a readback whose mean is unchanged need not publish a new public value solely because its private sample count advanced. Stats candidates retain an exact statistics generation and are revalidated before commitment.

Ordinary publication is activity-driven. On a naturally entered eligible Active Control turn, sample elapsed realtime and require changed statistics plus at least one second since the time sample of the previous committed ordinary snapshot, initially the session-creation sample. The checked deadline is `lastStatsPublicationNanos + 1,000,000,000`. Committing a still-current candidate clears the dirty flag, records that sample as the new publication time, and reserves its complete snapshot before unlocked Flow assignment.

Physical assignment and collector observation may occur later; they have no minimum-spacing promise. There is no Stats-only wake, periodic heartbeat, or catch-up. Ineligible or suspended turns leave changes pending. Terminal publication bypasses cadence and assigns the frozen final Stats before terminal State through [the publication protocol](coordination.md#terminal-publication).

## Implementation and verification

- [SessionProduction](../../src/main/kotlin/io/screenstream/capture/internal/session/production/SessionProduction.kt), [SessionReadBridge](../../src/main/kotlin/io/screenstream/capture/internal/session/production/SessionReadBridge.kt), and [SessionCoordinator](../../src/main/kotlin/io/screenstream/capture/internal/session/SessionCoordinator.kt): candidate commitment, exact settlement, wakes, and accounting.
- [SessionPacing](../../src/main/kotlin/io/screenstream/capture/internal/session/production/SessionPacing.kt), [SessionStatsAccumulator](../../src/main/kotlin/io/screenstream/capture/internal/session/production/SessionStatsAccumulator.kt), and [SessionTopology](../../src/main/kotlin/io/screenstream/capture/internal/session/topology/SessionTopology.kt): formulas and compatibility predicates.
- [Verification contracts](../04-testing/verification-contracts.md): `SES-05`–`SES-07`, `STO-01`, and `TERM-01`. Focused [pacing tests](../../src/test/kotlin/io/screenstream/capture/internal/session/production/PacingCalculatorTimingTest.kt) and [production tests](../../src/test/kotlin/io/screenstream/capture/internal/session/production/SessionProductionStateTest.kt) exercise timing and identity; [prefreeze accounting tests](../../src/test/kotlin/io/screenstream/capture/ScreenCaptureSessionPrefreezeAccountingTest.kt) distinguish selected results from terminal draining.
