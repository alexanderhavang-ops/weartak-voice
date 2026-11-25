# Status and roadmap

Last verified: 10 November 2026. Current milestone: **0.4 capabilities, shared onboarding and PTT alpha**.

| Capability | Status | Verification |
| --- | --- | --- |
| Standalone build; no WearTAK SDK/signature dependency | Implemented | Android build |
| TLS/password authentication and channel joining | Implemented | Real local Mumble 1.5.735 server |
| Certificate login without a password | Implemented | Registered client certificate on real local server |
| Untrusted server / wrong password / missing identity rejection | Implemented | Real-server negative tests |
| TCP-tunneled Opus packets and PTT release token | Implemented | Two real-server clients; terminator and stale-token checks |
| Android microphone, Opus codec and speaker pipeline | Implemented | Compiles; physical-watch audio not tested |
| Native channel controls and hold-to-talk | Implemented | Compiles; physical interaction not tested |
| Foreground connection and network-change handling | Implemented | Device/background/battery testing pending |
| TAKS JSON and both verified binary package formats | Implemented | 26 importer tests |
| Encrypted settings, identity import and CA selection | Implemented | Compiles; watch Keystore/import UI tests pending |
| Acoustic setup link + HTTPS profile download | Implemented; not deployed to live TAKS | Actual browser encoder → Java decoder; HTTP tests and real Murmur/Ice authenticator login/rejection checks |
| Physical button learning + momentary PTT | Implemented | Pure state tests; Samsung hardware pending |
| Automatic Martine/Companion onboarding | Not implemented | File/audio onboarding only |
| BLE-only paired phone audio relay | Not implemented | Watch requires a usable network route |

## Verification scope

The signed release build and Android release lint checks pass. Manual optional password
entry is an intentional alpha fallback until phone-assisted onboarding exists.

The current suite contains 53 tests: 38 package/protocol tests, nine acoustic/button/URL
tests (one opt-in browser encoder check), and six opt-in tests against
an actual isolated Mumble server. All 53 passed in the combined browser/real-server run. Normal builds skip
the six Mumble integration tests and browser encoder check unless their fixture parameters are supplied.
The test server is loopback-only, uses generated test certificates and synthetic identities,
and is stopped after the run. No production node or operator credential is involved.

The real-server tests verify control/voice packet interoperability. They do not prove that
Android's codec, microphone or speaker works on a particular watch. No physical watch or
emulator was exercised, and no compatibility with Vx audio or the live TAKS authenticator
has been verified. Certificate authentication is supported by the client, but host-owned
WearTAK keys are not automatically available to this app.

## Remaining work

1. Exercise installation, settings, microphone, speaker and PTT on a physical watch.
2. Verify current TAKS certificate authentication and map watch identities on the server side
   in the TAKS repository, without relying on self-declared callsigns or certificate names.
3. Test Vx interoperability, headset routing, multi-speaker audio, screen-off operation,
   battery use, interrupted capture, channel changes and network handoff.
4. Deploy and verify the matching TAKS audio onboarding endpoints and watch-authenticator update;
   exercise the complete flow through a real phone speaker and watch microphone.
5. Consider UDP transport and a phone relay after the basic watch audio path is validated.

The original 0.1 WearTAK/Companion integration patches remain separate historical prototypes;
they are not a runtime dependency or a feature shipped by this standalone alpha.

Update this page and the team brief whenever capability or evidence changes. Distinguish
source implementation, compilation, real-server tests and actual watch verification.

The connected DevOps shell reported disabled during this change. No live server patch or
authenticator restart was performed. TAKS source, backend tests and rollout guidance are
kept in the TAKS repository.

The TAKS suite passed 35 tests. A separate loopback-only Murmur run with the actual generated
Ice authenticator accepted a valid watch login and rejected wrong, malformed, expired and
revoked credentials. This does not establish deployment on the live node.

## 0.4 development integration

- Actual pinned SDK plugin variant plus independent standalone variant, sharing
  one importer/review/store/Mumble/PTT implementation.
- Optional versioned Binder handoff, host-owned memory leases and durable receipt
  metadata in a matching WearTAK development fork. UID/package/signer/live-binding
  checks, one-use handles, expiry, deduplication and explicit operator review.
- Direct `b-f-t-r` candidate classification and Companion normalized channel input
  converge on that broker. Existing server and source-bound local-peer credential
  boundaries remain. Stock firmware does not include this extension.
- Standalone direct file/share import and an opt-in, same-signer DEV adapter for
  full integration testing without TAK plugin signing.
- Firmware/plugin/capability snapshots and guarded ATAK voice-transfer controls.
- Watch physical-button and Android media-button PTT with multiple-source tracking,
  optional click-toggle, communication routing and forced-stop behavior.

### Watch testing through 0.6.19

On a Galaxy Watch Ultra, the standalone client exchanged voice in both directions
with ATAK VX. The tester confirmed Back-button PTT toggle and reported intermittent
earbud media-button delivery. The watch plugin received a channel package through
WearTAK Companion on the 5.8.4 companion-pause-test1 DEV host. The transfer carries
channel configuration only. For a new endpoint Voice requires separate credentials.

The source includes local multicast voice and Mumble over TLS/TCP. Screen-off
Mumble reliability remains open: observed sessions timed out and reconnected.
Full VX interoperability, private/whisper calls and UDP Mumble remain outside
the verified scope.
