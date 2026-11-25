# WearTAK Voice

## 0.6 watch preview

The WearTAK SDK plugin and standalone watch app share the same push-to-talk screen and onboarding. Use **Voice setup** for Wi-Fi pairing, manual Mumble or local multicast, sound, and VX ZIP import. Use **Review received channels** for a package sent with the ATAK Companion. See [voice setup and device tests](docs/VOICE_SETUP.md).

A **Wear OS Mumble app** for watch push-to-talk and TAKS/VX channel onboarding.
The `standalone` variant works independently. The optional `plugin` variant also registers with
WearTAK and receives reviewed channel handoffs from our compatible development host.
Both use the app's own audio, network connection and voice credentials.

> **0.4 capabilities / PTT alpha:** connection, channel controls and Android audio paths are implemented.
> The network core has been tested against a real local Mumble server. Physical-watch microphone,
> speaker, headset and background behaviour have not yet been verified.

## Features

- Verified TLS connection, with optional password or imported PKCS#12 client identity.
- Optional private-CA import; no trust-all mode or plaintext connection fallback.
- Live channel list, explicit joining and momentary hold-to-talk.
- Learn an available physical PTT button, with release/focus-loss handling.
- Phone-to-watch acoustic enrollment link and HTTPS voice profile download (matching TAKS update required).
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
| [Sound setup and buttons](docs/AUDIO_ONBOARDING.md) | No typing on the watch, ticket security and transport contract |
| [Status and roadmap](docs/STATUS.md) | Tested capabilities and remaining work |
| [Architecture](docs/ARCHITECTURE.md) | Session, audio and credential boundaries |
| [WearTAK team brief](docs/WEARTAK_TEAM.md) | Standalone decision and future integration |
| [Contributing](CONTRIBUTING.md) | Development and documentation upkeep |

## Build and test

Requires JDK 17 and Android SDK platform/build tools 34. Set `ANDROID_HOME`.
The standalone build needs no WearTAK SDK checkout.

```bash
./gradlew :core:test :app:assembleStandaloneDebug
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

## Companion/plugin development build

The standalone path remains available:

```sh
./gradlew :core:test :app:assembleStandaloneDebug
```

For the plugin variant, first build the immutable SDK source pin (JDK 17, Android SDK 34), then:

```sh
python ci/build_plugin_sdk.py
./gradlew :app:testPluginDebugUnitTest :app:assemblePluginDebug
```

SDK hashes remain enforced. `-PweartakSdkAar=/absolute/path/to/verified.aar` can reuse the same
combined pinned artifact. Release follows the upstream template's signature binding permission. Matching DEV builds use
an isolated signature permission (`se.takcampus.voice.permission.BIND_PLUGIN_DEV`), admitted only
by the debug host for the Voice DEV package. This avoids permission-owner conflicts with an older
installed voice prototype. The plugin DEV bind permission also recognizes the 5.8.4-companion-pause-test1 DEV host certificate on Android 12 and later.
Debug applications use `.dev` IDs and separate storage. Release signing still uses your own keystore.

See [the companion contract and physical-device checks](docs/COMPANION_CAPABILITIES.md).

The shared host staging API is implemented in our development host; stock hosts without it keep channel transfer disabled. See [the API request](docs/SDK_VOICE_PACKAGE_API_PROPOSAL.md) for signatures and package/signing details.
