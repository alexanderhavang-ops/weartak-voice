# WearTAK Voice

Mumble voice communications for WearTAK on Wear OS.

The goal is live push-to-talk from the watch, using the same voice channels as ATAK Vx,
with TAKS/Martine onboarding and support for both paired and independent operation.

> **Current status: channel-onboarding prototype.** The debug APK builds and the 26
> importer tests pass. Live Mumble audio and PTT are not implemented yet. No watch/phone
> hardware validation has been completed.

## What works in this prototype

- Import existing TAKS Vx mission packages without installing Vx on the watch.
- Decode JSON and both binary protobuf layouts produced by TAKCTL and Martine,
  including binary-only ZIPs.
- Preserve the mission, server endpoint, channel names and actual Mumble channel IDs.
- Receive channel configuration through the plugin's own signature-protected receiver.
- Review and accept or dismiss incoming channels on the watch.
- Keep pending configuration, accepted configuration and transfer receipts in atomic storage.

Package import does not authenticate a Mumble user, grant channel access, or install TLS trust.

## Documentation

| Document | Audience |
| --- | --- |
| [WearTAK team brief](docs/WEARTAK_TEAM.md) | Maintainers evaluating the integration |
| [Architecture and contracts](docs/ARCHITECTURE.md) | Plugin, Companion and TAKS developers |
| [Status and roadmap](docs/STATUS.md) | Current capabilities, verification and next steps |
| [Contributing](CONTRIBUTING.md) | Development and documentation conventions |

## Build and test

Requires JDK 17 and Android SDK platform/build tools 34. Set `ANDROID_HOME`.

```bash
./scripts/bootstrap_sdk.sh
./gradlew :core:test :app:assembleDebug
```

The bootstrap script checks out the pinned public WearTAK SDK source. Alternatively,
set `WEARTAK_SDK_SOURCE` to a clean checkout of that revision. Synthetic interoperability
fixtures are included; normal tests do not need TAKS or access to a live server.

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It is an onboarding
prototype, not a working radio. The watch requires Android API 30 or later. Automatic
host binding/delivery also requires signing compatible with WearTAK's signature permission;
an ordinary debug APK does not establish production-host compatibility.

To regenerate interoperability fixtures from the actual TAKS package writers:

```bash
python3 scripts/generate_fixtures.py --taks-source /path/to/taks
./gradlew :core:test
```

The fixture provenance records the TAKS source revision. Fixtures use synthetic callsigns,
`voice.example.org`, and no credentials.

## Repository boundaries

This repository owns the **watch plugin**, its channel-import contract and tests.
Martine, onboarding services, channel provisioning and server-side changes belong in
[`alexanderhavang-ops/taks`](https://github.com/alexanderhavang-ops/taks).
WearTAK host and ATAK Companion changes belong in their respective upstream repositories
or forks. Their initial integration patches are prepared separately and are not claimed
as merged or deployed. See the [team brief](docs/WEARTAK_TEAM.md).

## License

Original code is GPL-3.0-or-later; see [LICENSE](LICENSE). Dependencies retain their own
terms. Gradle wrapper files retain their upstream Apache-2.0 notices. SDK source is acquired
separately; no upstream signing keys, private configurations or SDK AARs are bundled.
