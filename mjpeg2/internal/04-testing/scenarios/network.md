[Scenario index](../verification-contracts.md) · [Running tests](../testing.md)

# Discovery evidence and LAN permission

Behavior: [maintained contract](../../02-configuration/network-addresses.md). Filters, permission policy and supplied source evidence use L0; Android source/monitor integration uses L1. Controlled native inputs exercise complete/partial reads, callback fallback, scoped selection, stable identity, metadata/filter updates, stale-scan reconciliation and held scan/close settlement. Platform shadows exercise callback copying, registration retry/late retirement, pre-26 stale fallback rejection and tether snapshot retirement. Actual Android native discovery and LAN enforcement need L2. Supplied unregister actions check failure aggregation without proving physical observer settlement.

## Immutable filter criteria

<a id="mjpeg-net-01"></a>

Construct a filter from caller-owned sets, mutate those sets, and apply representative selection groups. The filter copies its inputs and exposes unmodifiable criteria. Matching requires one family/type/category choice; an empty low-level group selects nothing. Saved settings prohibit emptiness separately. Returned monitor lists remain immutable; equal unchanged observations may conflate.

## Supported address classes

<a id="mjpeg-net-02"></a>

Controlled native inputs include RFC1918 inside/outside boundaries, fc/fd IPv6 unique-local ranges and their neighbors, CGNAT, deprecated IPv6 site-local and other nonprivate unicast, both-family loopback and IPv4 link-local. Category-only filters check their independent expected groups at a representative SDK. Scoped selection checks separately include unspecified/multicast, IPv6 link-local and distinct scopes. Supported addresses select expected categories/families; unspecified, multicast and all IPv6 link-local are excluded regardless of filters. NonPrivate does not claim internet reachability; IPv4 link-local may be selected.

## Interface type evidence

<a id="mjpeg-net-03"></a>

Public source snapshot fixtures supply VPN/Wifi/Ethernet/Mobile/Other metadata, tethering and interface names tun/tap/ppp/wlan/wifi/p2p/apN/eth/rmnet/ccmni/pdp/unknown. Call inferInterfaceType and Snapshot.interfaceTypes. VPN wins conflicting reported transports/types; recognized metadata beats fallback inference, tethering fills absent/Other, unknown names remain Other. Prefix/name rules produce the source-supported type.

## Scoped selection and filtering order

<a id="mjpeg-net-04"></a>

Two interfaces advertise the same IP; IPv4/IPv6, scope variants and unscoped aliases coexist. A fresh candidate on one interface is filtered out while a retained candidate on another still matches the same scoped IP. Apply representative filters to the controlled observed scan. One matching interface is selected per IP+scope using stable order; different IPv6 scopes remain distinct; a scoped alias supersedes its same-interface unscoped alias; filtering can choose a different interface without changing that tracked IP identity. Filtering precedes duplicate-interface choice, so the excluded fresh candidate cannot hide the matching retained candidate.

## Address identity and metadata continuity

<a id="mjpeg-net-05"></a>

- **Stable address identity versus confirmed disappearance.** Change metadata/filter/server port for a known IP/scope, exclude it by filtering, make it unreadable, then confirm its disappearance and return. Identity survives metadata/filter exclusion and uncertainty; changing scope creates a separate identity. Current sufficient evidence of absence retires it; a later return gets a fresh ID. Temporary unreadability cannot spuriously tear down listeners.

<a id="mjpeg-net-13"></a>

**Metadata continuity.** Publish changed interface metadata while preserving the tracked IP/scope identity and unchanged [HTTP incarnation](http-runtime.md#mjpeg-srv-03). Different IPv6 scopes remain separate address/socket identities.

## Complete, stale and uncertain evidence

<a id="mjpeg-net-06"></a>

- **Scan completeness and absence authority.** Supplied ScanResult fixtures vary successful/failed interface sets, enumeration completeness and platform readiness. Read complete/canConfirmAbsence and integrate missing known addresses through the monitor. Normal-refresh readiness requires enumerationComplete, no failed interfaces and ready metadata. Successful native reads or complete enumeration excluding a failed interface can confirm absence; caller must also require current evidence and actual missing address.

<a id="mjpeg-net-07"></a>

- **Native reads versus callback fallback.** Native enumeration/read fails or succeeds empty; callbacks report addresses for known interfaces. Run source scan with independently controlled platform/native evidence. Callback addresses supplement unknown reads after incomplete enumeration/failed interface read; successful native absence is authoritative. Down interfaces contribute successful empty reads. Enumeration/retry exceptions keep uncertain known evidence rather than declare global loss.

<a id="mjpeg-net-08"></a>

- **Current versus stale scan and latest filter.** A scan is held while callback metadata revision and latest filter change; variant scan remains current but incomplete. Complete the scan and latest updates. Current metadata/filter wins selection; stale scan cannot confirm old addresses absent and triggers another observation opportunity. Incomplete current evidence retains uncertain addresses and uses recovery refreshes; successful later evidence converges.

## Platform callback paths

<a id="mjpeg-net-09"></a>

API 24/25, 26+, 30+ and 36+ fixtures vary callback registration, capabilities/link properties/loss and tethering availability. Register observers; deliver callbacks or fallback reads including callback-during-fallback race. Pre-26 fallback may supply missing metadata without overwriting newer callbacks. Registered modern callbacks become authoritative; VPN and no-INTERNET LAN information remains usable. API-36 tethering observation supplies supported types. ConnectivityManager registration failure/retry checks retain deterministic native address evidence on both scans; readiness follows later callback metadata. This does not exercise tether registration failure/retry.

The implemented fallback histories run on 24/25; callback payload/retirement runs on 26/29/30/35. A stock-derived registration receipt checks request criteria on 29/30/35 and public capability satisfaction on 30/35: unrestricted untrusted LAN/VPN inputs need neither INTERNET nor VALIDATED. Stock callback delivery alone does not evaluate a NetworkRequest or establish real network availability. API 26 begins [guaranteed initial capability/link callbacks](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback); API 30 supplies clearCapabilities; API 36 supplies the [public tether callback](https://developer.android.com/reference/android/net/TetheringManager.TetheringEventCallback). Tether snapshot readiness, copying and retired-event rejection run on 36/37. Tether registration faults/late registration and non-WIFI tether inputs remain unarranged; the compiled public tether constants expose WIFI only.

## Discovery lifetime and settlement

<a id="mjpeg-net-10"></a>

- **Discovery starts once without subscribers.** New monitor is uncollected; filter calls include rapid replacements; repeated equal observations occur. Call updateFilter, collect later, then change filter. Initial NotStarted becomes Loading for requested filter then Observed; first call starts discovery without needing a subscriber. Latest selection replaces rather than appends; one native scan at a time is a source-supported resource obligation, not inferred from emissions.

<a id="mjpeg-net-11"></a>

- **Close before start, during scan and late registration.** Monitor/source is idle, has registered observers, or is midscan/registration when close wins. Close repeatedly, cancel one waiter and attempt later filter/callback updates. Close succeeds as Closed only after current unregister actions, active scan and late registration retirement settle; all waiters share cleanup outcome. Closing ignores new filters and stale callbacks; cancelling one waiter cannot abandon cleanup. Closing an inert monitor admits no native scan, including after later filter requests.

<a id="mjpeg-net-12"></a>

- **Discovery/cleanup failure aggregation.** Discovery failure and a held combined monitor cleanup failure remain unarranged. Supplied unregister actions do exercise distinct/identical failures and ensure remaining actions are attempted. A broader history must also hold an applicable obligation until the others settle. Discovery failure publishes Failed promptly and remains final; cleanup waits for all applicable unregister actions, active scan and late registrations. Narrow supplied-action aggregation retains the first exception and attaches later failures except the primary exception itself; no global deduplication is promised. Monitor failure may attach the source aggregation as a nested suppressed cause. Close reports failure only after required settlement.

## Permission gates and value policy

<a id="mjpeg-lan-01"></a>

- **Runtime and target SDK gates.** Runtime API <37 / >=37 and target <37 / >=37 are independently varied with grant denied. Start controller and apply a representative ready address list. Permission is treated granted unless both runtime and target are >=37; only that combination requires actual ACCESS_LOCAL_NETWORK grant. No prompt or launcher behavior is asserted.

Focused L1 controller fixtures cover the supported minimum and immediate predecessor (24/36), then API 37 with target 36/37 and grant changes. This checks MJPEG's admission policy; [Android 17 enforcement](https://developer.android.com/privacy-and-security/local-network-permission) applies inside the real network stack. Android 16's optional compatibility restriction is a separate L2 configuration, not this API-37 production gate.

<a id="mjpeg-lan-02"></a>

- **Address/filter permission policy.** Loopback versus nonloopback addresses use Wifi/Ethernet/Other/Mobile/Vpn; filters use Loopback-only and mixed categories/types, including empty non-saved sets. Read requiresLocalNetworkPermission and apply controller filtering. Nonloopback Wifi/Ethernet/Other addresses require permission; Mobile/Vpn and loopback do not. A filter requires permission when it includes any nonloopback category and any eligible interface type. Empty saved filters are rejected elsewhere.

## Grant changes and real LAN enforcement

<a id="mjpeg-lan-03"></a>

- **Grant, revoke and resume-time recheck.** A ready discovery selection contains eligible and exempt addresses; grant changes outside the controller. Allow a genuine controller permission recheck/heartbeat after grant/revoke. Eligible listeners are removed on denial/revocation and become plan-eligible after grant; exempt rows remain. Controller discovery selection remains intact. Permission failure affects availability rather than silently replacing discovery with empty.

<a id="mjpeg-lan-05"></a>

- **Real incoming LAN enforcement and recovery.** Target-37 module runs on API37 device with remote LAN client; loopback and grant/deny/revoke states are recorded. Establish authorized viewing, revoke permission, then grant and allow current plan recovery. Actual permitted LAN access works where network allows it; denied/revoked LAN cannot be claimed reachable; module follows current permission policy and recovers eligible plan. Record platform outcomes without requiring every denial to be a SecurityException or immediate socket close.

## Permission denial versus network loss

<a id="mjpeg-lan-04"></a>

Observed matching discovery addresses are nonempty but all require a denied grant; a separate matching empty observation exists. Wait/advance the configured network-loss interval with faithful clocks. Denial alone cannot start the observed-empty network-loss episode; actual matching empty observation can. Start readiness remains unavailable without a current permitted Listening server.
