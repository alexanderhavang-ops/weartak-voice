# Brief for the WearTAK team

## Purpose

WearTAK Voice is an independent Wear OS plugin project for live Mumble push-to-talk.
It aims to interoperate with the voice channels ATAK users access through Vx, while making
watch onboarding work both with the ATAK Companion and independently through TAK.

This is a prototype project, not an official WearTAK or TAK Product Center release.

## Work completed

The plugin has an SDK Binder service, a native channel-review screen, an atomic configuration
store and its own signature-protected receiver. The importer reads the existing TAKS Vx ZIP,
its JSON payload and two verified protobuf encodings. Binary-only ZIPs are supported.
When both representations are present, their operational contents must agree.

The new APK builds and 26 importer tests pass. There is currently no Mumble connection,
audio playback, microphone capture or working PTT. Device testing is still pending.

## Proposed host integration

| Path | Proposed responsibility | Current state |
| --- | --- | --- |
| ATAK → Companion → watch | Forward a received Vx channel mission over the existing bonded single-watch link | Source patch prepared separately; full build/device tests pending |
| Independent TAK → WearTAK | Recognize a voice package after the existing authenticated download and source/hash checks | Source patch prepared separately; full build/device tests pending |
| WearTAK → voice plugin | Dispatch the bounded configuration envelope to the plugin-owned receiver | Receiver implemented; host dispatch patch prepared separately |
| Microphone → voice plugin | Negotiate v3 PCM streaming and handle capture ownership | SDK surface inspected; audio integration not implemented |
| Watch ↔ Mumble | Real-time Opus audio, channel state and PTT | Planned |
| BLE-only paired audio | Separate bounded binary relay through the phone | Planned; existing CoT/config forwarding is not an audio tunnel |

The phone retains Vx's existing import. Forwarding to the watch must not consume or prevent
that import. In ATAK's inspected source, the post-import callback carries a
`MissionPackageManifest` parcelable; the ZIP path is read from its `getPath()`.
The callback and coexistence still require verification on the supported ATAK release.

## Interfaces to agree with maintainers

1. **Signing and discovery:** establish supported plugin signing/distribution and a tested
   WearTAK version matrix without weakening `BIND_PLUGIN` signature protection.
2. **Configuration delivery:** agree on an official package-delivery extension or equivalent
   host dispatch to the plugin-owned receiver. The current SDK's typed server-package staging
   concerns TAK server enrollment, not arbitrary Mumble channel missions.
3. **Microphone ownership:** negotiate host v3 PCM availability and an explicit capture lifecycle.
   The inspected format is 44.1 kHz, mono PCM16 in bounded batches; Opus needs compatible
   framing/resampling. Avoid competing recordings with WearTAK acoustic sensing.
4. **Real-time paired audio:** agree on a separate binary relay if a watch has only the Companion
   BLE link. An OS-provided phone Internet route can be used when available, but cannot be
   assumed from Companion connectivity alone.
5. **Operator feedback:** expose actual connection, joined-channel and speaking state; never
   present configuration delivery as a working voice connection.

## Ownership

The watch plugin belongs here. TAKS/Martine changes remain in the TAKS repository.
WearTAK and Companion changes should be reviewed and maintained in their own repositories.
No upstream changes have been submitted, merged or deployed by this prototype.

## Inspected public source revisions

| Component | Repository | Commit |
| --- | --- | --- |
| SDK | `aegorsuch/weartak-plugin-sdk` | `86bdc8b04797c2cf6caa30e78e796abac1c2a330` |
| Wear OS host | `aegorsuch/weartak-wearos` | `492b439497249f052d81467dd238844f5d79e1fd` |
| ATAK Companion | `aegorsuch/atak-plugin-weartak-companion` | `252cc08f6509c3cc5c98f09cf99d647040347d8f` |

These revisions document the inspected interfaces, not a certified compatibility matrix.
