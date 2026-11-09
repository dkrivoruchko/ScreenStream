# Media scenarios: selected images, acquired bytes and response writes

[Scenario index](../02-verification-contracts.md) · [Testing MJPEG](../01-testing.md)

Test histories that follow captured JPEG bytes from the capture side's offer, through the one image selected for every viewer, to the bytes a `/jpeg` or `/mjpeg` response writes. The public [HTTP server guide](../../../docs/server.md#images) defines what image clients receive, and [Usage](../../../docs/usage.md#automation) defines **Inactive stream image**. [JPEG delivery](../../02-delivery/02-jpeg-delivery.md) owns selection, leases, pooling and writing; [HTTP delivery](../../02-delivery/01-http-delivery.md) owns admission, cancellation and totals.

Borrowed engine frames belong to [CAP-08](03-capture.md#mjpeg-cap-08), rotation during output to [ACC-09](06-access.md#mjpeg-acc-09), and client rows and totals to the [HTTP runtime scenarios](05-http-runtime.md).

## Terms and evidence layers

These scenarios use the [JPEG delivery components and terms](../../02-delivery/02-jpeg-delivery.md#components-and-terms): capture generation, publication version, change revision, lease and the [initial lease](../../02-delivery/02-jpeg-delivery.md#reader-pinning-and-leases). **Cutoff** closes a reader: it gets no further images and releases its initial lease if the writer hasn't claimed it. **Cancellation** of the call job is separate and interrupts transport I/O.

`JpegStore` and its readers are plain Kotlin and suit L0. `JpegResponse` reads Android's elapsed-realtime clock for repeats, so response writing needs L1, where that clock is controlled. Coroutine virtual time doesn't move it. Race histories need separate barriers for output and for time, plus a stated allowed outcome. Use recognizable byte fixtures and parse output independently of the production writer. Android transport behavior needs L2.

## Selection and capture generation

<a id="mjpeg-img-01"></a>

### Initial fallback

**History.** Install the logo once, before any capture is registered. With no real frame, read the selection under each Inactive stream image policy: App logo (`Placeholder`) and Last frame (`LastAvailableFrame`). Then try to install a logo again, after a capture has been registered, and after disposal.

**Expected.** Both policies select the logo, and the image kind is Logo. A second installation, an installation after capture registration and an installation after disposal all fail and leave the selection unchanged. A selected logo never counts as live capture: the kind stays Logo, never Live.

<a id="mjpeg-img-03"></a>

### Live image and retained history

**History.** Build three histories: one with a frame from the current generation, one with a retained frame from an earlier generation or attempt, and one with only the logo. Switch the fallback policy during each capture phase.

**Expected.** A frame from the current generation is always selected as Live, whatever the policy. Without one, App logo selects the logo. Last frame selects the retained frame (kind Retained), or the logo if no real frame exists. Selecting the logo doesn't erase the latest real frame, so switching back to Last frame restores it. The latest real frame survives new capture attempts. Only a newer accepted frame or store disposal replaces it.

<a id="mjpeg-img-02"></a>

### Phase and generation travel together

**History.** Register a fresh attempt with its initial generation. Then apply the status/generation pairs the capture session sends. Starting, WaitingForPermission and Reconfiguring keep the current generation. Suspended, Failed, Stopping and Stopped carry no generation. Active after a revocation carries a new one. Interleave updates that carry an older attempt ID.

**Expected.**

- Each pair is applied atomically.
- Selection follows the applied generation and the common fallback rule without erasing retained history.
- Updates from any attempt other than the registered one change nothing.
- Failed can coexist with a selected logo or retained image.
- While the store is open, it never creates, clears or advances a generation by itself. The one exception is terminal disposal, which clears the generation along with all other ownership and ignores every later update.

Which status carries which generation is the session's decision, verified with [capture scenarios](03-capture.md#mjpeg-cap-08).

## Copying capture output

<a id="mjpeg-img-04"></a>

### Invalid and stale offers

**History.** Offer a zero or negative byte count with the current generation. Separately, offer positive byte counts with a generation that has since been replaced, and after store disposal.

**Expected.** An invalid count on a current generation fails before the copier runs. Nothing is published, and any reserved spare buffer goes back to the store. Stale and closed offers return without calling the copier. Images that readers had already acquired stay valid and unchanged.

<a id="mjpeg-img-05"></a>

### Copy races and failure

**History.** Hold a copier mid-copy, or make it throw after it has written part of the destination. While it is held, do one of the following: suspend and resume the same attempt, register a new attempt, or dispose the store. Then release the copier.

**Expected.**

- The copier runs at most once, outside the store monitor. While it is held, readers can acquire images, and status and policy updates complete.
- A failed copy publishes nothing, returns its buffer for reuse if the store is still open, and rethrows to the caller.
- A copy whose generation was revoked or replaced while it ran is discarded, even when the attempt ID is unchanged. Resuming creates a new generation, and the old one never becomes current again.
- After disposal, a late copy neither publishes nor refills the spare buffer.
- Only accepted publications advance the publication version.

## Readers, leases and waiting

<a id="mjpeg-img-06"></a>

### Pinned first image

**History.** Admit a response while image A is selected. Select a differently sized image B before the writer starts. Close the reader before the writer's first acquisition, and in a separate history after it.

**Expected.**

- The `/jpeg` `Content-Length` and the first payload belong to A. The first MJPEG part is also A, because the first acquisition always hands over the admission pin. Later parts acquire the latest selection.
- A's bytes stay unchanged while they are leased, even after B replaces A or the store is disposed. The array is reused only after its last lease is released.
- Closing before the handoff releases the initial lease and blocks every acquisition.
- Closing after the handoff blocks only future acquisitions. The writer may still finish writing A, and A's bytes stay valid until the writer releases them. A transport failure, a disconnect or cancellation of the call can still interrupt that write. The test must not require A to be written completely.

<a id="mjpeg-img-07"></a>

### Concurrent readers and currentness

**History.** Let several readers hold leases on an old frame, the current frame and the logo while new publications replace the selection. Then let each reader acquire again.

**Expected.** Every existing lease keeps its exact valid prefix. Later acquisitions return the current selection. Spare capacity beyond `byteCount` in a reused buffer never appears in output. Leases on logo or retained images never make the selection Live, and they can't count as recovery for the current generation's slow status. A test of this scenario doesn't establish a global bound on memory or array count.

<a id="mjpeg-img-08"></a>

### Empty waits and lost wakeups

**History.** Use a store fixture without a logo. Open an MJPEG reader while a current frame exists, hand over its initial image, then revoke the generation so that nothing is selected. Publish a new image, or close the reader, at each point in the wait sequence: before the change revision is read, between that read and the acquisition attempt, and between a failed acquisition and suspension. Run the history at two levels:

- At L0, against the Reader alone. Follow its documented order: read the revision, try to acquire, then end if the reader is closed or else wait for a different revision.
- At L1, through the actual `JpegResponse` writer.

**Expected.** At L0, following that order, the Reader either returns the newly selected image or reports itself closed. A wait that starts from the observed revision always sees a change that has already happened. At L1, the writer either writes the newly selected image or ends the response, and it never waits for a change that has already happened. Assert the bytes written or the end of the response, not only a revision increment.

**Boundary.** The no-logo fixture is defensive. After activation, ordinary delivery always has the logo, so a reader always finds an image under either fallback policy.

<a id="mjpeg-img-11"></a>

### Repeats and early publication

**History.** After one MJPEG part has been written and flushed, try three histories while the writer waits for its repeat. Keep the selection unchanged. Publish a new image. Close the reader.

**Expected.**

- An unchanged publication is written again once the repeat interval has passed since the previous part. The public guide gives the interval as about one second.
- Wakeups that leave the selected publication unchanged, such as a status update, don't cause an early repeat.
- A new selected publication is written without waiting for the interval, and closing the reader ends the response.
- Every repeat counts as a completed payload.

Assert ordering and bounds that allow for scheduling delay, not exact wall-clock frame rate.

## Writing responses

<a id="mjpeg-img-09"></a>

### Single JPEG and admission pin

**History.** Pin image A, whose backing array is larger than its valid prefix. Select image B before the writer runs. Parse the headers and body independently.

**Expected.** The response is `image/jpeg` with a `Content-Length` equal to A's byte count, and the body is exactly A's valid bytes. It contains no B and no spare capacity. A cutoff before the writer's handoff can end the response with fewer bytes than announced, possibly none. A test must accept that truncation. It must not require a replacement image or a completed count. `/jpeg` never updates slow status.

<a id="mjpeg-img-10"></a>

### Multipart framing and latest selection

**History.** Publish recognizable images of different sizes while an MJPEG response runs. Read the actual response body through the Ktor channel or a CIO socket, and parse the parts with an independent parser. In a separate history, hold output during one part's headers or payload and publish a newer image meanwhile.

**Expected.** Headers, delimiters, CR LF line endings and payload lengths match the [public framing contract](../../../docs/server.md#mjpeg-stream):

- The body opens with the delimiter.
- Each part has `Content-Type` and `Content-Length` headers, a blank line and exactly the valid payload.
- CR LF and the next delimiter follow each payload.
- No closing delimiter is ever sent.

The first part carries the image pinned at admission. Each later part carries the image that was selected when the writer acquired it. The writer keeps that image's bytes for the whole part, so a newer publication during the headers or payload doesn't change the part being written. The newer image can appear only in a later part. Intermediate publications may be skipped, and framing never adds payload bytes.

<a id="mjpeg-img-12"></a>

### Partial payload, footer and flush faults

**History.** In three separate histories, fail output during the JPEG bytes, after the complete payload while the CR LF and delimiter that follow it are being written, and at flush.

**Expected.** An incomplete payload adds nothing to the counts. A payload that was fully written counts once, even when the following framing or the flush fails. The writer releases its lease on every exit, including exceptions. A completed payload means the bytes were accepted by the response channel, not that the peer received them. Don't require atomic parts: clients can see a partial image.

## Slow-reader observations

Slow status measures skipped publications within one capture generation. It doesn't measure bandwidth, latency or delivery. [JPEG delivery](../../02-delivery/02-jpeg-delivery.md) owns the counting rule: skips accumulate up to a threshold of five, and a publication with no gap resets the count. [HTTP runtime scenario mjpeg-obs-07](05-http-runtime.md#mjpeg-obs-07) owns how a response's status reaches a client row.

<a id="mjpeg-img-13"></a>

### Slow observation before failed payload

**History.** Let an MJPEG writer on the current generation skip enough publications to change its slow observation. Then fail output partway through the next payload.

**Expected.** The slow observation has already advanced, because it is recorded before the part headers are written. The completed counts stay unchanged. The signal measures skipped publications, not bandwidth, successful delivery or receipt.

<a id="mjpeg-img-14"></a>

### Slow threshold and neutral images

**History.** Feed one MJPEG writer a sequence that includes consecutive publications, gaps that accumulate to and past five skips, repeats of the same publication, logo frames, a retained frame from an older generation and the first frame of a new generation.

**Expected.**

- Five accumulated skipped publications mark the response slow, and the count doesn't grow past that threshold.
- One consecutive publication clears both the count and the slow status.
- Repeats and logo frames leave the observation unchanged.
- A frame from a different generation, including a retained one, starts a fresh baseline with no skips.

The runtime shows slow status only for a current listener's response that observed the current non-null generation. `/jpeg` responses never contribute.

## Cutoff and completion

<a id="mjpeg-img-15"></a>

### Cutoff versus response completion

**History.** Cut off three responses: one waiting for a change, one whose writer holds an acquired image, and one whose request fails before the engine invokes the body writer. In a separate CIO history, rotate credentials while a client keeps an MJPEG stream open, and observe the server end the stream before the client closes its own side.

**Expected.**

- After cutoff, no new image is admitted, and already acquired bytes stay valid until the writer releases them.
- In the history that fails before the writer runs, the response releases its unclaimed initial lease on exit.
- The response's final progress is retired exactly once, when its own call job completes. Cancelling the job or closing the reader doesn't count as completion.
- In the rotation history, the stream ends without any action from the client. A part already being written may complete, and no further part starts. Rotation closes readers but doesn't cancel the call. [Listener cutoff](05-http-runtime.md#mjpeg-srv-08) also cancels the call.
