# Sign the Voice watch plugin

The GitHub Actions environment `voice-release` holds one encrypted environment secret, `VOICE_RELEASE_SIGNING`. Only the `main` branch may deploy to this environment. The build job compiles the pinned SDK, runs core tests and produces an unsigned plugin. A separate signing job has no source checkout. It signs that APK and prints its public certificate fingerprint.

1. On your Mac install a JDK and GitHub CLI, then sign into GitHub CLI with an account that administers `alexanderhavang-ops/weartak-voice`.
2. From the reviewed Voice checkout run `python3 scripts/setup-release-signing.py`. Choose the password in LastPass. This generates or reuses `~/.weartak-signing/voice-release.p12` and uploads the encrypted keystore and password to the GitHub environment over stdin. Keep an independent backup of the file and password. The script preserves an existing environment secret.
3. After the reviewed workflow is on the default branch, open **Actions → Sign WearTAK Voice plugin → Run workflow** on **main** and enter the full reviewed source commit from the Voice PR or main. This allows hardware testing before the integration PR merges. Download **signed-voice-plugin** from the run. It contains the APK, public signer report, source commit and APK checksum.
4. Give Alex the APK and the **Signer #1 certificate SHA-256 digest** in `signer.txt`. The APK file checksum is a separate value. Alex can verify the APK with `apksigner verify --print-certs`, then review and pin its package and current signer in core. Use the watch test card in the Voice integration PR with matching signed builds.

The release plugin package is `se.takcampus.voice.watch`. Its binding permission is `se.takcampus.voice.watch.permission.BIND_PLUGIN`. It trusts `com.aitech.weartak_civ` signed with the public certificate verified for TAK.gov release job 8034608. Key rotation on either side requires coordination and a reviewed update.
