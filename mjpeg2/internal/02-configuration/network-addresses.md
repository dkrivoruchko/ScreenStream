# Network addresses

`AndroidNetworkAddressMonitor` discovers supported local address candidates and publishes an immutable inventory of scoped IPs and their interface alternatives. The controller selects from that inventory using its effective filter and owns permission gating, the HTTP plan and network-loss Stop. Discovery does not bind sockets or test reachability. [Settings](settings.md#network) owns saved filter defaults, and [HTTP delivery](../03-delivery/http-delivery.md#planned-servers-and-scoped-sockets) owns the separate server incarnation.

## Contents

- [Source and observation owners](#source-and-observation-owners)
- [Evidence, freshness and retention](#evidence-freshness-and-retention)
- [Classification and filtering](#classification-and-filtering)
- [Address identity and ordering](#address-identity-and-ordering)
- [Refresh and close](#refresh-and-close)
- [Permissions and platform limits](#permissions-and-platform-limits)
- [Implementation and verification](#implementation-and-verification)

## Source and observation owners

| Owner | Responsibility |
| --- | --- |
| `NativeInterfaceAccess` | Native interface enumeration, name lookup and handle getters; the default system reader preserves native failures |
| `AndroidNetworkAddressSource` | Native interface reads and partial-read evidence for a supplied platform snapshot; runs on IO outside the monitor gate |
| Monitor `PlatformObservation` | Callback/fallback/tether metadata and revisions under the monitor's `admissionGate`; immutable interface-level snapshots |
| Monitor `Observation` | One serialized native scan, retained candidates, identities, inventories and refresh deadlines |
| Monitor lifecycle gate | Start/close admission, callback mutation and ordered observer registration handles under one gate; durable cleanup completion and final failure |
| Controller | Effective-filter selection, current permission evidence, allowed HTTP addresses and network-loss episode |

The controller calls `start()` on first settings acceptance. Repeated starts are safe, and discovery continues without subscribers; close can start the worker even if discovery was never started. The state progresses from `NotStarted` to `Loading` and then `Observed(inventory)`. The inventory retains every supported scoped IP and its ordered interface alternatives. `inventory.select(filter)` is a pure operation returning a complete selected list; an empty result is valid. Inventories, selected lists and filter sets cannot be modified. Unchanged inventories do not produce another state update. `Failed` is final failure evidence; successful close publishes `Closed`.

The monitor's platform helper consumes `ConnectivityManager` callback payloads and older-platform fallback reads. Its conflated `Unit` notifications invalidate known platform evidence; observation reads a fresh snapshot when handling a notification. Snapshots expose only the revision, callback interface addresses, resolved interface types and metadata readiness. Raw per-network callback/fallback maps and tethering state stay private to the helper.

The native source reads through [NativeInterfaceAccess](../../src/main/kotlin/io/screenstream/mjpeg/networkaddress/NativeInterfaceAccess.kt). Its default `SystemNativeInterfaceAccess` delegates enumeration, name lookup and handle getters to `NetworkInterface` when read, preserving native exceptions for evidence classification.

The monitor observes nonrestricted networks without requiring `INTERNET` or `VALIDATED` capability, preserving LAN/P2P candidates. API 24–25 use a getter fallback; it also remains available on newer APIs while callback registration has failed. Registered callbacks are authoritative from API 26 onward. The fallback is revision-fenced and never overwrites newer callback information or restores a lost network. API 36+ tethering callbacks add public interface-type evidence.

## Evidence, freshness and retention

Each scan records its supplied platform snapshot revision, along with found addresses, successfully read interfaces, failed interfaces and whether enumeration completed. A successful read of a down interface records an empty address list. Enumeration and individual reads can fail independently. After complete enumeration, retry lookups use failed interface names; after incomplete enumeration, they use known callback/tether interface names from the snapshot's interface-type keys.

Callback addresses supplement unknown native reads after incomplete enumeration or interface-read failure. They do not override a successful native read that shows absence. Callback interface entries remain available when multiple networks report the same interface. Callback metadata may still be incomplete while Android emits its updates; `Snapshot.ready` describes observer/type information, not successful reads of all addresses.

At scan completion, the monitor rereads the platform snapshot. It removes a missing candidate only if the metadata revision still matches and the scan confirms absence for that interface: a successful read, or completed enumeration with the interface absent from the failed set. Unrelated incomplete metadata does not invalidate a successful current read. If the snapshot revision changed during the scan, its results can add candidates but cannot remove older ones; the monitor immediately requests another scan.

```mermaid
flowchart TD
    S[Native scan and callback evidence] --> R{Revision still current?}
    R -->|No| K[Retain unconfirmed candidates and rescan]
    R -->|Yes| I{Interface read or enumeration confirms absence?}
    I -->|No| K
    I -->|Yes| D[Remove missing candidate]
    K --> N[Publish immutable inventory]
    D --> N
    N --> F[Controller filters fresh then retained candidates]
    F --> P[Select one interface per scoped IP]
```

Fresh candidates are sorted and placed before retained candidates. A candidate replaced by matching fresh interface/IP evidence is not retained twice. Temporary unreadability keeps its identity and avoids treating uncertainty as a listener teardown request. Platform type updates can reclassify retained candidates while address reads catch up; missing new type evidence preserves the candidate's previous classification.

## Classification and filtering

A candidate must match one selected member of each group: families **and** interface types **and** categories. Members within one group use **or**. A low-level empty group selects nothing; saved [network settings](settings.md#network) reject empty groups.

Unspecified addresses, multicast and IPv6 link-local are always excluded. Virtual interfaces are not excluded solely because they are virtual. A network with only IPv6 link-local addresses provides no supported selection.

| Category | Meaning |
| --- | --- |
| `Private` | IPv4 RFC 1918: `10/8`, `172.16/12`, `192.168/16`; IPv6 ULA: `fc00::/7` |
| `NonPrivate` | Other supported unicast, including CGNAT `100.64/10` and deprecated `fec0::/10`; no Internet reachability promise |
| `Loopback` | IPv4 `127/8`, IPv6 `::1`; local-device viewing |
| `LinkLocal` | IPv4 `169.254/16`; IPv6 link-local remains excluded |

Public framework transport evidence takes precedence over name inference. VPN wins when multiple transports are reported, followed by Wi-Fi, Ethernet, cellular and Other. Tethering evidence supplements unknown types; Wi-Fi tethering maps to `Wifi`, while other tethering types are `Other` unless further evidence/name inference applies. USB tethering is not assumed to be cellular.

Fallback name inference recognizes `tun`/`tap`/`ppp` as VPN; `wlan`/`wifi`/`p2p`/`ap` plus digits as Wi-Fi; `eth` as Ethernet; and `rmnet`/`ccmni`/`pdp` as mobile. Unrecognized names remain `Other`.

`NetworkAddressInventory.select(filter)` applies filtering before duplicate interface selection. An excluded fresh candidate cannot hide another matching interface for the same IP. One matching candidate is selected per IP identity, preferring matching fresh evidence to matching retained evidence. The controller applies an accepted filter change synchronously to its processed inventory; discovery has no filter update or acknowledgement step. Permission gating follows that selection.

## Address identity and ordering

`NetworkAddress.id` is monotonic within one monitor instance and identifies IP bytes plus IPv6 scope ID and scoped-interface name. All interface alternatives for that scoped IP share the ID. The shared immutable `IpAddressKey` copies those fields for discovery, `NetworkAddress.ipKey` and HTTP socket identity; HTTP adds its port separately. Filter changes, interface metadata changes and HTTP port changes preserve the discovery ID. Once all evidence for the address is confirmed gone, a later return gets a new ID. A different IPv6 scope is a distinct identity.

When one interface supplies both scoped and unscoped aliases for the same IPv6 bytes, the scoped candidate suppresses its unscoped alias. Selection is sorted deterministically by interface name, address-byte length, unsigned byte representation, scope ID, scope-interface name and interface-type ordinal. Returned lists/filter sets are immutable copies.

`NetworkAddress` contains one candidate's IP, interface name and type; the HTTP port is separate. Its equality includes metadata and scope as well as ID, so a metadata edit can publish an updated inventory while [HTTP](../03-delivery/http-delivery.md#planned-servers-and-scoped-sockets) retains its incarnation.

## Refresh and close

The observation coroutine permits at most one native scan on IO. Starting discovery requests the first scan. New system revisions request another read and can reset retry scheduling. One nullable deadline records immediate and delayed scan requests; no timeout is selected while a scan is active. Incomplete reads retry after 250 ms, 1 second and 3 seconds, then every 30 seconds. Complete reads refresh every 30 seconds. Filter changes select from known inventory without requesting another scan. These coroutine timers do not guarantee detection latency while the device sleeps; discovery uses no wake lock or alarm.

`close()` first closes admission and takes the ordered observer unregister handles under the monitor's single `admissionGate`, then awaits a shared completion independent of that waiter. Callback mutations and new starts are ignored after this fence. Platform registration and all unregister actions run outside the gate. Unregister failures are collected. Registration that finishes after close is rejected and unregistered before its scan completes. The native source has no separate closing state or observer lifecycle.

The independent cleanup worker unregisters observers, joins observation including an active native scan and late registrations, then closes the platform invalidation channel and settles shared completion. An observation failure publishes `Failed` and starts unregistering immediately, before waiting for a stuck scan. Its cause remains primary; cleanup failures are attached as suppressed causes. Canceling one close waiter does not abandon cleanup or establish success. A native scan that never returns can keep close pending; the code does not manufacture completion from elapsed time.

## Permissions and platform limits

The manifest declares `ACCESS_NETWORK_STATE` for observation, `INTERNET` for HTTP and `ACCESS_LOCAL_NETWORK`. The project uses min API 24 and target/compile API 37. Android's [local-network permission](https://developer.android.com/privacy-and-security/local-network-permission) applies to target-37 apps on Android 17, including incoming TCP acceptance. [Coordination](../01-lifecycle/coordination.md#lan-permission-request-identity) defines request admission and permission filtering of the HTTP plan.

The model conservatively marks nonloopback Wi-Fi, Ethernet and Other addresses as requiring LAN permission; mobile and VPN types do not. The controller applies permission to each selected address, independently of the filter that selected it. This is application planning logic, not proof of the platform's eventual routing or socket decision.

Discovery does not request permission, start a network, alter routing, perform DNS/probes, scan Wi-Fi, inspect SSID/location or manage hotspots. It has no mDNS discovery. Android/OEM callback omissions, interface-read bugs and tether metadata remain runtime uncertainties. The [NetworkCallback reference](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback) warns against synchronous getters inside callbacks; the monitor's platform helper consumes callback payloads and uses its separate older-platform fallback.

## Implementation and verification

- [Address model/filter/inventory](../../src/main/kotlin/io/screenstream/mjpeg/networkaddress/NetworkAddress.kt), [monitor contract](../../src/main/kotlin/io/screenstream/mjpeg/networkaddress/NetworkAddressMonitor.kt), [Android monitor](../../src/main/kotlin/io/screenstream/mjpeg/networkaddress/AndroidNetworkAddressMonitor.kt), [native source and evidence models](../../src/main/kotlin/io/screenstream/mjpeg/networkaddress/AndroidNetworkAddressSource.kt) and [native interface access](../../src/main/kotlin/io/screenstream/mjpeg/networkaddress/NativeInterfaceAccess.kt) define these rules.
- [Testing](../04-testing/testing.md) describes verification scope and evidence requirements.

Navigation: [Internal guide](../README.md) · [Usage](../../docs/usage.md) · [Architecture](../../docs/architecture.md).
