[Scenario index](../verification-contracts.md) · [Running tests](../testing.md)

# Viewing credentials and PIN verification

Behavior: [maintained contract](../../03-delivery/http-delivery.md). Use L0 for explicit-time access/limiter histories and L1 for delivery cutoff and HTTP responses. Continuing CIO rotation verifies external stream cutoff and credentials; a held acquired-byte writer and detached physical completion need independent barriers. A completed payload alone does not establish those races.

## Viewing and token exchange

<a id="mjpeg-acc-01"></a>

- **Open/protected viewing and valid PIN shape.** With protection off, viewing needs no credential and PIN verification returns authorization without a cookie. With protection on, activation requires a saved six-ASCII-digit PIN; a current URL token or viewing cookie authorizes media. Valid PIN verification returns the viewing cookie. Missing/wrong credentials and wrong/non-ASCII/short/long PINs do not authorize protected viewing.

<a id="mjpeg-acc-02"></a>

- **Token exchange preserves existing cookie.** Repeatedly exchange valid, invalid or missing tokens under protected and open access. Valid link returns the same existing viewing cookie without rotating credentials or resetting PIN policy; invalid/missing/open token exchange yields no cookie. The [root redirect](web-protocol.md#mjpeg-web-04) checks cookie attributes.

## Credential lifetime and acquired media

<a id="mjpeg-acc-03"></a>

- **Credential rotation versus policy-only edit.** Protected current PIN/credentials exist; policy/limiter/hide/block duration changes and same/different PIN or enable/disable are variants. The same PIN text is independently constructed in a separate SecretValue, rather than reusing the original object. Apply updateSettings/updateConfiguration and exercise old/new viewing decisions. Enable/disable or changed enabled PIN creates a new credential incarnation and revokes old next-frame/WS authority. Same enabled PIN and policy-only edits preserve token/cookie/generation; disabled access remains open. Random token difference alone is not the oracle.

<a id="mjpeg-acc-09"></a>

- **Rotation with acquired media and detached history.** Authorized media reader has an acquired image, another is waiting, and WS/client history exists. Rotate enabled PIN or open/protected mode while writer can still read acquired bytes. Old reader admits no next frame; its acquired JPEG may finish safely. Old WS generation is cancelled; endpoint history clears while late physical completions still contribute global totals. Fresh viewing uses applied current authorization.

## Immediate-peer PIN limiter

Use protected access, explicit times, two listener addresses and distinct immediate peer IPs. Check PIN results and subsequent viewing decisions:

| History | Expected result |
| --- | --- |
| <a id="mjpeg-acc-04"></a>Four wrong PINs, then a fifth across listeners | First four are Wrong (HTTP 403); fifth is Blocked (429) for the configured duration. One immediate IP shares its budget across listeners; remote ports and forwarded IPs do not create another identity. Another immediate IP is unaffected. |
| <a id="mjpeg-acc-05"></a>Correct or wrong PIN during an active block | Both stay Blocked; remaining time decreases from the original deadline, without extension. Existing valid viewing credentials still work. |
| <a id="mjpeg-acc-06"></a>Correct PIN after four failures; verification before/at/after block expiry | Success returns the cookie and clears wrong attempts. Before expiry remains blocked; at/after expiry starts a fresh attempt budget. |
| <a id="mjpeg-acc-07"></a>Rotate PIN, toggle limiter, open access or edit duration | Rotation preserves attempts/current block. Disabling the limiter or opening access clears history; re-enabling starts fresh. Duration edits affect future blocks only. Limiter-off wrong PIN never blocks. |
| <a id="mjpeg-acc-08"></a>Create a block near `Long.MAX_VALUE` | Deadline saturates, remaining time stays nonnegative and expiry follows that deadline. This is defensive arithmetic, not a real clock prediction. |
