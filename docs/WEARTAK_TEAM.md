# WearTAK integration brief

The project now has two builds sharing the same onboarding parser, review/store,
Mumble engine and PTT: a real SDK plugin and a standalone application. The standalone
build needs ordinary Android signing and can import/share the same VX ZIP directly.
It can also exercise the full host/Companion path through a DEV-only explicit Binder
adapter in the matching WearTAK Dev build.

The host fork adds capabilities/software/plugin inventory and one memory staging
broker for direct TAK packages and Companion normalized JSON. Its configured-server
TLS download remains isolated from the restricted local-peer path. No sender URL
receives host credentials. Plugin UID/package/signer/live binding is checked on each
control call; one-use origin-bound handles expire; pending payloads remain in memory.
Receipts survive rebind/restart and distinguish DOWNLOADED, STAGED and APPLIED.

The immutable existing SDK AAR pin/checks remain enforced. Our optional
`voice-package-api` Binder is supplied through versioned hostExtras. It is a
prototype extension for upstream review, not a claim of an official SDK release.
No frozen SDK Parcelable layout or existing transaction number changes.

See [architecture and onboarding](ARCHITECTURE.md) and [the API proposal, package
names and DEV signing certificate](SDK_VOICE_PACKAGE_API_PROPOSAL.md). The normal
sender is another ATAK user sharing channels in VX over GeoTalk; TAKS is an
additional sender, not a required proprietary onboarding mechanism.

Channel files may carry an endpoint-scoped Mumble account or a shared server password. Voice owns its own authentication and
never retrieves WearTAK private keys. Full VX audio/feature compatibility and
full device compatibility remain to be demonstrated. The standalone client exchanged
voice with ATAK VX in both directions. A Galaxy Watch Ultra received a channel
handoff in plugin mode. See [test status](STATUS.md) for the outstanding
screen-off and earbud issues. The two app variants share local multicast support
and the TCP-tunneled Mumble client.
