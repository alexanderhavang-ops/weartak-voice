# WearTAK Voice

A **standalone Wear OS Mumble app** for watch-based push-to-talk and TAKS channel onboarding.
It runs independently of WearTAK and ATAK, using its own microphone, speaker and network
connection. WearTAK installation, plugin loading and upstream signing keys are not required.

> **0.2 standalone alpha:** connection, channel controls and Android audio paths are implemented.
> The network core has been tested against a real local Mumble server. Physical-watch microphone,
> speaker, headset and background behaviour have not yet been verified.

## Features

- Verified TLS connection, with optional password or imported PKCS#12 client identity.
- Optional private-CA import; no trust-all mode or plaintext connection fallback.
- Live channel list, explicit joining and momentary hold-to-talk.
- Opus microphone capture and playback using Android's platform codecs.
- Mumble's encrypted TCP audio tunnel; no additional native codec library.
- Foreground service with a disconnect notification, explicit reconnection and bounded audio queues.
- Existing TAKS Vx JSON/binary channel-package import and review.
- Credentials and imported identity material encrypted using an Android Keystore key.

This app cannot read WearTAK's private key or automatically use its existing enrollment.
Certificate login requires an identity available to this app and accepted by the Mumble server.

## Start here

| Document | Contents |
| --- | --- |
| [Install and use](docs/SETUP.md) | Watch setup, credentials, imports and PTT |
| [Status and roadmap](docs/STATUS.md) | Tested capabilities and remaining work |
| [Architecture](docs/ARCHITECTURE.md) | Session, audio and credential boundaries |
| [WearTAK team brief](docs/WEARTAK_TEAM.md) | Standalone decision and future integration |
| [Contributing](CONTRIBUTING.md) | Development and documentation upkeep |

## Build and test

Requires JDK 17 and Android SDK platform/build tools 34. Set `ANDROID_HOME`.
No WearTAK SDK checkout is needed.

```bash
./gradlew :core:test :app:assembleDebug
```

Normal unit tests need no server or TAKS checkout. Real-server tests are opt-in:

```bash
python3 scripts/run_local_interop.py --server /path/to/mumble-server
```

That script creates a temporary CA, test identities, database and loopback-only server,
runs the integration tests, and stops the server. It does not contact production TAKS.
The server binary and its system libraries must already be installed. `GRADLE_EXECUTABLE`
can select an existing Gradle installation.

Synthetic package fixtures can be regenerated from the actual TAKS writers:

```bash
python3 scripts/generate_fixtures.py --taks-source /path/to/taks
```

For a signed alpha/release APK, set `VOICE_KEYSTORE_PATH` and `VOICE_KEYSTORE_PASSWORD`
and use alias `weartak-voice`, then run `./gradlew :app:assembleRelease`. Signing material
is private, excluded from Git and not included in published source or APK assets.

## Ownership

This repository owns the standalone watch client. Martine, TAKS onboarding and server-side
changes belong in [`alexanderhavang-ops/taks`](https://github.com/alexanderhavang-ops/taks).
Future WearTAK host and ATAK Companion changes belong in their respective repositories.
No GitHub Actions or deployment pipeline is required.

## License

Original code is GPL-3.0-or-later; see [LICENSE](LICENSE). Dependencies retain their own
terms. Gradle wrapper files retain their upstream Apache-2.0 notices. No WearTAK SDK AAR,
upstream signing key, real operator package or private server configuration is bundled.
