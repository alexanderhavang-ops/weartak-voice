# Brief for the WearTAK team

## Direction

WearTAK Voice is now a **standalone Wear OS Mumble application**. The original prototype
used WearTAK's plugin SDK, but production-host signing/loading was not established. The
standalone version removes that dependency and lets voice development continue without
upstream signing keys or changes to WearTAK.

This is an independent alpha, not an official WearTAK or TAK Product Center release.
It is deliberately not advertised as an importable WearTAK plugin.

## Implemented

- Own Android foreground service, microphone capture, Opus codecs, playback and momentary PTT.
- TLS Mumble control and TCP-tunneled voice, live channels and explicit channel joining.
- Optional password or imported PKCS#12 identity; configurable private CA with hostname checks.
- Encrypted credential storage using Android Keystore, without automatic host-key access.
- Import of the existing TAKS Vx JSON/protobuf packages, native review and channel-ID/name checks.

The code builds. All 44 tests passed, including six tests against a real local Mumble server.
Physical-watch audio, background operation, headset routing and the live TAKS authentication
mapping remain unverified. The network tests should not be mistaken for hardware certification.

## Future integration points

1. **Provisioning:** forward an accepted voice package into a supported import interface,
   or use a non-secret acoustic setup offer. No open credential-bearing receiver is exposed
   by the standalone app. A future interface needs explicit caller authorization and review.
2. **Identity:** establish an approved way to enroll an app-owned voice identity for the same
   operator, or expose a host-owned TLS/key operation. Do not copy private keys through channel
   packages or microphone audio. TAKS must authenticate proof of key possession and authorization.
3. **Optional plugin wrapper:** a shared audio core could later support a genuine WearTAK plugin,
   after agreeing on signing/discovery, permissions, capture ownership and supported versions.
4. **Phone relay:** a CoT/BLE Companion link does not provide a Mumble audio tunnel. A BLE-only
   voice path would require a separately designed bounded binary audio relay.

The original host/Companion patches were prepared against these source revisions and remain
unmerged, undeployed prototypes, not a dependency of the standalone app:

| Component | Repository | Inspected commit |
| --- | --- | --- |
| SDK | `aegorsuch/weartak-plugin-sdk` | `86bdc8b04797c2cf6caa30e78e796abac1c2a330` |
| Wear OS host | `aegorsuch/weartak-wearos` | `492b439497249f052d81467dd238844f5d79e1fd` |
| ATAK Companion | `aegorsuch/atak-plugin-weartak-companion` | `252cc08f6509c3cc5c98f09cf99d647040347d8f` |

## Ownership

This repository owns the watch app. Martine, TAKS onboarding, channel provisioning and server
certificate authentication belong in `alexanderhavang-ops/taks`. Host/Companion changes belong
in their own repositories. Documentation is maintained with the corresponding code changes.
