[Scenario index](../verification-contracts.md) · [Running tests](../testing.md)

# HTTP listeners, request history and totals

Behavior: [maintained contract](../../03-delivery/http-delivery.md). L1 checks use routes, request registries and configured CIO loopback. Explicit-time registry histories establish identity and retention; real writers separately establish nonzero completed-payload accounting. Registry calls alone do not prove physical request completion. Held binds/cleanup, fatal fault injection and runtime-clock races remain gaps. Android socket behavior needs L2.

## Activation and accepted plans

<a id="mjpeg-srv-01"></a>

- **Activation and preactivation plan.** Configure fresh delivery with a logo and zero or multiple addresses before any capture. Activate once, repeat activation and separately Stop before activation. Preactivation rows stay Pending; accepted activation opens HTTP admission independent of capture and even with empty/unbound plan. Start is accepted once; terminal/preactivated misuse cannot reopen; ownership of immutable logo bytes transfers only to accepted activation.

<a id="mjpeg-srv-02"></a>

- **Server-plan validation.** Direct delivery plans have boundary ports and duplicate address IDs/scoped sockets or changed IP under preserved identity. Configure plans before/after activation. Ports 1..65535 are allowed at delivery boundary; invalid ports, duplicate IDs/scoped sockets and same identity changed scoped IP reject. Settings' 1024 lower bound is a separate product policy. Terminal stop ignores later plans.

## Server incarnation continuity

<a id="mjpeg-srv-03"></a>

A listening plan receives reordered/metadata-only address changes, removals/reselection, port change and distinct IPv6 scope. Apply each plan. Metadata/reordering retains unchanged server UUID/response authority; port/removal/reselection creates a new incarnation. Address ID and server UUID remain separate; stale retry cannot target successor.

## Independent binds and retry budget

<a id="mjpeg-srv-04"></a>

- **Independent binds and classified failure.** Configured CIO plans two host loopback addresses on one port: occupied ::1 becomes Failed while 127.0.0.1 serves recognizable media. Security/Socket/IO/unknown exception classification requires separate faithful platform fixtures. Activate and observe attempts. One bind's failure does not prevent another listening server/capture-ready address. BindException maps AddressInUse, SecurityException PermissionDenied, other SocketException AddressUnavailable, IOException IoFailure, remaining ordinary Exception Unknown; unexpected termination maps IoFailure. The host occupied-port test observes Unknown because configured CIO wraps the underlying bind error; it does not establish Android failure classification.

<a id="mjpeg-srv-05"></a>

- **Three attempts and explicit current Retry.** Exhaust a current server's bind budget, make its port available, then request Retry for current Failed, stale, Listening, preactivation and terminal IDs. Every bind attempt spends the same three-attempt budget, including successful binds; later unexpected termination can exhaust it. Retry of the exact current Failed UUID resets the budget and returns Pending; the other requests do nothing.

A retry during held cleanup or an existing delay cannot bypass physical retirement or erase that deadline. After exhausted cleanup with no retained deadline, no new delay is required. These combined timing histories remain unarranged; the current occupied-port history establishes exhaustion and eligible recovery.

## Replacement and physical retirement

<a id="mjpeg-srv-06"></a>

- **Same-socket successor waits for cleanup.** One incarnation is removed/reselected on same scoped socket while its physical cleanup is held; plan changes again. Apply replacement plans and release exact old listener cleanup. No successor physically binds before old cleanup settles; latest accepted plan governs next incarnation. Pending/Failed does not assert physical descriptor teardown. Caller cancellation cannot discard an accepted replacement plan.

<a id="mjpeg-srv-07"></a>

- **Stale bind/listener result cannot publish.** Bind/start/termination is held while its plan is removed, port changes or terminal Stop wins. Complete old physical operation. Old listener cannot become current Listening or authorize requests; its resources are cleaned independently. Old completion cannot overwrite new server state or extend current-client grace.

<a id="mjpeg-srv-10"></a>

**Listener cleanup receipt.** Stop an unstarted listener and a started CIO listener with an active connection. Completed cleanup together with connection closure/rebind establishes retirement; cancellation alone does not. Held same-socket replacement and rare construction failure remain unarranged.

## Terminal shutdown and cleanup failure

<a id="mjpeg-srv-08"></a>

- **Listener cutoff and permanent runtime shutdown.** Delivery has media/WS requests, acquired payload and server owners; Stop can precede activation or compete with plans. requestStop repeatedly; wait/cancel one stop waiter; release transport work. Admission closes immediately, next-frame readers close and current transports/jobs are cancelled; acquired bytes remain valid while owned by writer. stop awaits shared cleanup independently of waiter cancellation; servers/clients clear while completed totals remain; no accepted successor work runs afterward.

<a id="mjpeg-srv-09"></a>

- **Listener cleanup/fatal runtime failure.** These fault histories are unarranged. A worker fatal fault with successful cleanup, a cancellation/physical cleanup fault with other owners, and combined faults require distinct controlled boundary inputs. Worker fatal failure retains Info.fatalCause and closes the runtime; if cleanup succeeds, stop may complete normally despite that retained fatal cause. Cancellation/physical cleanup failure records a cleanup obligation failure and stop must fail rather than claim successful teardown; uncertain cleanup permits no same-socket successor retry. With combined faults the first fatal cause can differ from the cleanup failure reported by stop. Remaining cancellation obligations are attempted independently.

## Media consumer presence and grace

<a id="mjpeg-obs-01"></a>

Check `hasConsumer` against sampled Active media rows, including idle grace, independently of capture phase. WebSockets create no media row. The [controller report](controller.md#mjpeg-ctl-04) and grace history below share these checkpoints.

<a id="mjpeg-obs-04"></a>

- **Two-second Active grace and five-second retention.** Final current media response for endpoint completes at explicit reference time; reconnect before/at/after expiry variants exist. Sample just before/at grace deadline and just before/at retention expiry. Current final completion retains Active for two seconds then Disconnected for five; at expiry row disappears. A reconnect before expiry retains row ID/totals and current state; a return at or after expiry gets a fresh endpoint ID. Registration itself enforces this deadline even without an intervening sample. WS does not prolong either interval.

## Endpoint identity

<a id="mjpeg-obs-02"></a>

Multiple JPEG/MJPEG responses share exact server incarnation/IP/remote port; same IP different port/server is separately arranged. Register/respond/complete requests using existing runtime boundary. Same endpoint shares one row and bytes with latest registered format; differing port/incarnation keeps separate rows. WS creates/prolongs no endpoint row. Forwarded authority is not a new immediate endpoint identity.

## Completed-progress retirement

<a id="mjpeg-obs-03"></a>

One response completes payloads before request ends; several same-endpoint responses and late completion are variants. Sample while active, then complete request and resample/repeat retirement through natural callback boundary. Global/row totals transfer completed payload counts/bytes exactly once without duplication at retirement. Partial payloads/framing add nothing; acquired repeats add completed counts. Physical completion is distinct from close request.

## Cutoff, detached history and fresh lifetimes

<a id="mjpeg-obs-05"></a>

- **Cutoff skips grace and old completion cannot extend history.** Current listener cutoff occurs with live responses; same endpoint's old listener completes late while replacement exists. Remove/cutoff listener and complete old responses at later time. Cutoff makes row Disconnected without Active grace; late obsolete completion cannot extend its retention deadline or mark a current row active. Old physical totals still count globally, and detached row history is not resurrected.

<a id="mjpeg-obs-06"></a>

- **Credential rotation and shutdown clear history preserve totals.** Completed/live request progress exists when credentials rotate or runtime stops. A fresh delivery is created after the old lifetime ends while saved preferences remain available. Rotate credentials/stop; release pending physical completions. Rotation clears clients and samples totals; shutdown clears servers/clients and final sample retains global totals including late completions. Physical writers remain cleanup obligations despite removed history; no leaked current authorization is inferred from old totals. A fresh lifetime begins without old peer PIN attempts, endpoints, totals or selected capture image. Saved PIN/preferences follow their own policy; no random credential/PIN inequality is required.

<a id="mjpeg-obs-09"></a>

- **History retention does not retain request/image resources.** All endpoint media requests have physically completed while numeric history remains Active/Disconnected. Complete exact request cleanup and continue observing retained row. Retained history represents numeric/string facts only and cannot delay exact reader/request/image settlement. Do not use garbage collection, reference counting private maps or heap reachability alone as proof.

## Current-source slow correlation

<a id="mjpeg-obs-07"></a>

A real output-boundary writer establishes slow progress. Sample with current/absent/replaced source identities and current/obsolete listeners, then after consecutive-frame recovery and exact response retirement. These checks establish sampled correlation; retained-logo neutrality and overlapping slow responses remain unarranged. Endpoint slow is true only if a current listener response reports slow for current nonnull source. Obsolete source observations cannot mark successor slow; source/store samples may be adjacent moments rather than an atomic frame snapshot.

## Sampling versus control publication

<a id="mjpeg-obs-08"></a>

One statistics sample exists, then server Pending/Listening/Failed/fatal updates occur between scheduled samples. Observe control publication, advance proved sample arrangement, then rotate/cleanup. Server/failure state publishes promptly while prior counters/clients/sample time are preserved. Routine samples occur once per configured second subject to scheduling; rotation and cleanup sample separately. sampledAtElapsedRealtimeMillis is monotonic for a monotonic platform clock.
