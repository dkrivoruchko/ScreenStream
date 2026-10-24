[Scenario index](../verification-contracts.md) · [Running tests](../testing.md)

# Selected images, immutable bytes and media writes

Behavior: [maintained contract](../../03-delivery/jpeg-delivery.md). Use L0 for store/readers and L1 for responses, wire output and runtime progress. Application writers can verify output faults and accounting; exact lost-wake, repeat, slow-state and cutoff races need independent timing/publication barriers. Android transport evidence requires L2.

## Stopped-image policy histories

<a id="mjpeg-img-01"></a>

- **Initial logo and stopped-image policy.** Install a recognizable immutable logo before capture, apply Placeholder/LastAvailableFrame while Stopped, then open a reader. Without a real-frame history, both choices select logo. Logo installation is once/pre-capture only.

<a id="mjpeg-img-03"></a>

- **Live policy edits and retained history.** Captures have a current-source real frame, a retained real frame or only logo. Change policy between both choices, then transition through every capture status. A current-source real frame always wins. Otherwise, policy edits immediately select logo for Placeholder, or the latest real frame (logo if absent) for LastAvailableFrame. Placeholder does not erase real-frame history: switching back to LastAvailableFrame restores it. The latest real frame survives capture attempts and policy edits until replacement or store disposal.

## Capture status and selected image

<a id="mjpeg-img-02"></a>

Logo or a real frame exists under an exact attempt; stale attempt and every current capture status are variants. Register with a supplied fresh generation, then apply status/generation pairs from the capture session and read selection. Register sets Starting and retains the latest real frame and readers; selection follows the common fallback until a current-generation frame arrives. The capture session preserves the generation through WaitingForPermission/Starting/Reconfiguring, creates one on Active after revocation, and revokes it on Suspended/Failed/Stopping/Stopped. The store applies those decisions and the same Placeholder/LastAvailableFrame fallback without erasing real-frame history. Stale status cannot change current facts; Failed can coexist with a selected logo or retained real frame.

## Admitted copies and stale production sources

<a id="mjpeg-img-04"></a>

- **Stale/closed offers and invalid size.** Offer JPEGs after disposal or attempt/source replacement, and separately offer positive/zero/negative byteCount values to the current source. Stale/closed/no-source offers admit no copy/publication. Current source requires positive byteCount; invalid size throws without publishing partial selection. A failed validation does not invalidate already acquired bytes.

<a id="mjpeg-img-05"></a>

- **Copy failure and producer replacement during copy.** An external copier is held after admission or throws after partial writes; capture/source/disposal can change while held. Register successor/suspend/stop/dispose and complete/throw the old copy. For the same attempt, suspend and resume to Active before releasing the old copier. Copy runs synchronously at most once outside store monitor; failed copies rethrow without publishing partial data. A source-replaced/closed copy cannot resurrect output or change current selection. Unrelated readers/status operations can complete while copy is held. That old copy remains fenced by its revoked production source despite unchanged attempt identity.

## Pinned image and concurrent readers

<a id="mjpeg-img-06"></a>

- **Pinned initial image survives reselection.** Reader pins image A before headers; B is later selected with different size; reader close before versus after first handoff are variants. Acquire/release initial frame and replace/dispose selection. InitialByteCount and first handed-off bytes belong to A. Acquired A stays immutable through B/reselection/disposal until exact release; close revokes only unclaimed initial acquisition and next-frame admission, not writer-owned bytes.

<a id="mjpeg-img-07"></a>

- **Multiple readers and current-source relevance.** Two readers acquire old/current/logo images; capture source changes while one old acquisition remains. Publish new frames, release/acquire readers and check currentness. Readers keep their acquired immutable payloads; later acquisitions use latest selected image. Logo, old source and stopped retained images do not count as live capture recovery; a current matching live frame does. Spare capacity is never valid payload.

## Empty-image waits and repeats

<a id="mjpeg-img-08"></a>

- **Empty-image wait and cutoff lost-wake history.** Admitted MJPEG writer becomes image-empty; publication/cutoff can occur between observing revision and acquisition/wait. Arrange competing publication/close/dispose at available reader boundary. A waiting response resumes for new selected image or exits on cutoff; no already occurred publication/close leaves it stranded. Reader's revision only arranges wakeup; actual bytes/exit is required evidence.

<a id="mjpeg-img-11"></a>

- **Same-publication repeat and new-publication wake.** After one MJPEG image, hold unchanged selection, then publish a fresh version or clear/close the reader using controlled timing and publication/cutoff barriers. Unchanged publication is repeated after configured one-second repeat delay; new publication can wake earlier; image-empty waits and close exits. Repeats count as completed payloads. Tests require eligible timing bounds, not exact wall-clock intervals or guarantee frame rate.

## JPEG and multipart wire output

<a id="mjpeg-img-09"></a>

- **JPEG response exact prefix and headers.** Reader pins A with valid byteCount smaller than backing capacity; B arrives before response writer enters. Call respond through real application boundary and consume bytes. Content-Type image/jpeg and Content-Length match pinned A; body sends only A valid prefix once. Closure before writer handoff may truncate pinned-length response; no fabricated replacement B or successful count for an incomplete payload.

<a id="mjpeg-img-10"></a>

- **Multipart wire format and payload selection.** MJPEG reader has distinct small/large frames and current publications. Consume several parts through in-process/real configured CIO and independently parse wire. Content-Type is multipart/x-mixed-replace with screenstream-jpeg boundary; each part has image/jpeg and exact Content-Length, CRLF framing and valid-prefix payload. Later acquisitions select current image rather than queued every historical publication; framing is excluded from payload totals.

## Payload accounting and slow observations

<a id="mjpeg-img-12"></a>

- **Partial payload versus footer/flush failure.** Faulting application output can fail during JPEG bytes, after full payload but before multipart footer, or during flush. Write each arranged response and complete physical request. Partial payload adds no completed JPEG/bytes; complete payload adds once even when later footer/flush fails. Framing never contributes. Acquired bytes are released on all writer exits; counts do not prove network receipt.

<a id="mjpeg-img-13"></a>

- **Slow observation may advance before failed payload.** Skip enough accepted current-source publications to mark slow, then fail partway through the observed frame payload. Slow/source Progress can change before header/payload IO even though completed JPEG/byte counts stay unchanged. Slow is an observation of skipped publications, not successful delivery or a receipt.

<a id="mjpeg-img-14"></a>

- **Slow threshold, recovery and neutral observations.** Consume controlled consecutive publications, skipped runs totalling below/at five and a new source. Include threshold/recovery/source reset, neutral repeat and retained-image histories separately. Five accumulated skips mark slow; a consecutive publication resets skips/slow. Repeats and logo are neutral; a newly observed generation resets the baseline. Retained frames can update an obsolete generation's baseline, but runtime exposes slow only for the current non-null generation. JPEG format never contributes slow.

## Response cancellation and completion

<a id="mjpeg-img-15"></a>

Admitted response is waiting, writing acquired bytes or fails before writer entry; credential/listener/runtime cutoff competes. Close reader and cancel/complete actual request through runtime. Reader close stops new admissions; acquired bytes remain valid until final read/release. Actual request completion retires exact final progress once, including pre-writer failure. Cancellation/reader closure alone does not establish finished physical IO or peer delivery.

Check normal continuing MJPEG credential rotation after a completed wire payload: the old stream ends externally before local socket close, old credentials fail and current viewing remains usable. Use a held acquired-byte writer to verify rotation during a payload independently.
