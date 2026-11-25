# WearTAK Voice 0.6.19

Standalone 0.6.19 shows its version in the launcher and in connection details.
The default standalone debug build reuses the Wi-Fi 0.6 package ID.
Use `-PlegacyStandaloneDevBridge=true` only to build the older signature-bound host gateway variant.

The round talk screen turns green when connected. Tap the joined channel (or **Details** while offline) for the server, audio focus and route, bud input, version and recent connection history. Session endings recorded by the service include the interactive screen state and the system idle flag at the time of observation. Mumble reports default-network changes in diagnostics and continues using its existing TLS socket while it works. The receive timeout is 35 seconds without data during a socket read; ping replies and other Mumble traffic count. Wear OS may also supply the previous app process exit reason.

Socket history records CPU sleep and recent Mumble ping activity for each connection.

## Mumble phone call mode

Open **Settings → Voice setup → Mumble phone call mode** and choose **On** or **Off**. Off is the default. Connect to Mumble to start a new run. On requests the Android `phoneCall` foreground-service type after authentication, alongside microphone and media playback. The app releases the type when the session ends and requests it again after a successful reconnect. The setting applies only to Mumble. Bluetooth routing and PTT work the same in both runs.

In **Socket history**, each new flow shows the selected mode and the service type reported by Android. The standby line shows the policy sampled on login. If the watch disables Low Power Standby, that particular policy cannot explain a difference between runs. Earlier builds did not record these fields.

## Mumble socket history

Tap the channel (or **Details** while offline), then **Socket history**. The watch keeps the ten most recent TCP attempts across app restarts. Each record includes the server, local and remote IP when available, attempt and TCP connect times, Mumble bytes received and sent, and disconnect time and reason. An unfinished flow from an earlier process shows its last saved counters and an unknown end time. While connected, the watch checkpoints counters every 30 seconds.

An interrupted, previously authenticated Mumble session reconnects on the current network. Voice attempts to rejoin the last confirmed channel by ID and name. If the channel changed or the server denies the join, choose a channel. PTT requires a fresh press. Disconnect cancels recovery.

## PTT controls

Hold **PTT**, or use your assigned Back button or headset control. The pad shows **Opening…** while the voice route and microphone start; **On air** appears in red when microphone samples arrive. While offline the pad shows **Connect**. The gear opens **Channels** and **Voice setup**. Choose a live room under **Channels** and tap **Join**. Opening setup during a session asks for confirmation before disconnecting. **Repair** requests focus if lost or runs the headset test when focus is available.

Press PTT and listen for two clear, closely spaced beeps on the watch or connected headset before speaking. The double beep confirms the local microphone and encoder are running on the joined voice channel. A quiet reminder sounds every three seconds while the microphone is open. Transmission stops after five consecutive seconds below the microphone level threshold, or after sixty seconds from pressing PTT. A single lower tone tells you the microphone has closed after either timeout. Release and press again for another transmission.

While disconnected, choose **Voice setup → Headset PTT → Click to start / stop** for earbuds that send a play/pause click. Allow Nearby devices if prompted. Connect the earbuds to the watch, then connect Voice. Check that the first click shows **On air** and the second ends transmission. Galaxy Buds can alternate separate Play and Pause commands on successive squeezes. In click mode a completed squeeze starts PTT and the next squeeze stops it on button down. While transmitting, tap the red screen button to stop immediately. The Bud input counter shows the last key event Voice received. Voice releases the headset microphone route after PTT stops. Incoming speech plays through the normal playback output. The headset button session stays active while the app is connected. If a squeeze goes missing, tap **Repair**. It registers a fresh media-button session and plays one local test tone on the confirmed headset voice route. Squeeze after the tone and check the Bud input counter. The test leaves the microphone closed. The watch chooses the recipient. Check the Bud input counter to see whether a squeeze arrived. Headset PTT defaults to Off.

In **Choose watch PTT button**, tap **Back action · tap to start/stop** to use short Back presses while Voice is ready in the foreground. Connection details shows **Back PTT enabled**. A short Back press while disconnected keeps Voice open and prompts you to connect. A system Back swipe can also toggle PTT. Use **Cancel** to close the chooser. For momentary PTT, choose **Hold a side button**, then hold for one second and release. The dialog displays the key and scan code delivered to it. A system action may intercept a gesture even after the watch detects a press. Android must deliver the matching press and release for assignment.

## Set up voice

| Method | Phone | Watch |
| --- | --- | --- |
| Companion over Wi-Fi | Open **Voice setup → Send over Wi-Fi**, choose a VX ZIP, select the watch ID, and enter the code | Open **Voice setup → Pair over Wi-Fi**, then review and save |
| Companion over BLE | Open **Voice setup**, choose **Send over Bluetooth**, and select the VX ZIP | Open **Channels → Review received channels** and save |
| Direct channel sound | Open **Voice setup**, choose **Play channel as sound**, select a VX ZIP, and choose one channel | Open **Voice setup → Listen for setup sound**, then review and save |
| HTTPS ZIP link sound | Choose **Play HTTPS ZIP link** and paste the HTTPS download link | Listen, confirm the host, then review the downloaded channels before saving |
| Existing TAKS card | Choose **Play TAKS card link**, or play the sound from TAKS | Listen and confirm the TAKS host and setup before saving |
| Manual | | Open **Voice setup → Enter channel manually** |

Direct sound uses the existing WTK1 tones and contains one channel. It carries an address, port, and transport, plus a Mumble room or optional VX data channel. For a new Mumble endpoint, enter the shared server password and leave Username blank. An open server also accepts a blank password if guests are enabled. The watch supplies its saved callsign or a stable name such as WearTAK-1a2b3c4d. For account access, enter the assigned username and password. Selecting another imported room on the same host and port reuses that endpoint’s settings. The maximum sound payload is 384 bytes. Use Wi-Fi, BLE, or an HTTPS ZIP link for larger or multi-channel configurations.

The HTTPS ZIP link must resolve directly to a VX voice ZIP over verified HTTPS. The watch confirms the source and imports the channel fields for review. The TAKS ticket flow remains available for its separate login provisioning.

For local multicast, both devices must use the same IPv4 audio address, port, and protocol. Match the optional data channel on both devices to display participant callsigns. Audio is unencrypted on the local network. The watch requests its own Wi-Fi connection while local voice is active.

Tap **Connect** and wait for **Ready to talk**. Hold the screen button to transmit and release it to listen. Changes take effect on the next connection.

## Build, sign, and test

Use the matching WearTAK host with the V1 voice-package Binder broker for Companion delivery. The standalone app can receive Wi-Fi setup directly and can exercise sound, file import, and manual setup. The supplied APKs are development builds. For Voice Plugin 0.6.19 DEV, install only the plugin on a watch with WearTAK 5.8.4-companion-pause-test1 DEV. The DEV permission admits that host certificate on Android 12 and later. The plugin verifies the host package and calling UID, and the host still checks the plugin identity. An old development APK signed with another key cannot be upgraded in place by Android. Back up its settings before replacing it. The production WearTAK app uses a different package.

For a Gradle build, use Android SDK 34, JDK 17, and the pinned plugin SDK from `python ci/build_plugin_sdk.py`. Then run `./gradlew :core:test :app:assembleStandaloneDebug :app:assemblePluginDebug`. Companion is built and signed separately through the ATAK CIV 5.8 TAK.gov pipeline.

1. Install the standalone watch build and pair stock VX on the phone with one manually configured UDP channel. Check audio in both directions.
2. Repeat with RTP and a data channel. Check the displayed callsign and audio in both directions.
3. Play a direct code from Companion with BLE disconnected. Check that review shows the right channel and that Mumble login, if needed, is requested on the watch.
4. Play a short HTTPS ZIP link and a TAKS card code. Check that cancel leaves the previous channel in place and a valid ZIP stages all channels.
5. With a correctly signed SDK plugin and host, send the same ZIP over bonded BLE. Check **Staged** and **Applied** in Companion and restart the plugin to confirm that it can receive again.

## Pair over Wi-Fi

1. Connect the phone and watch to the same IPv4 Wi-Fi network. The access point must allow mDNS and communication between clients.
2. On the watch, open **Voice setup → Pair over Wi-Fi**. Check that the watch displays a short ID and an eight-digit code.
3. On the phone, open **WearTAK Companion → Voice setup → Send over Wi-Fi**. Choose a VX ZIP and tap the matching watch ID.
4. Enter the code in ATAK. Check for **Channels sent** and review the channel list on the watch before saving.
5. Try an incorrect code, cancelling from each device, and waiting for the window to expire. The current channels should remain available. The watch clears its ad when pairing closes.

Android Network Service Discovery advertises a short random label and protocol version as `_weartak._tcp.`. The watch advertises for up to two minutes and limits the window to four connection attempts. J-PAKE uses the eight-digit code to authenticate both endpoints and their session certificate fingerprints. The channel package and receipt use mutually authenticated TLS. The watch stages the same `voice_channels_v1` envelope as the existing Companion flow.

Wi-Fi carries one voice setup package per successful pairing. CoT, TAK server settings and the normal Companion session use the existing transport. Sound and manual setup are also available. This release uses IPv4 on the local subnet.

The supplied standalone Wi-Fi APK uses a separate development package ID so an older standalone install and its settings can remain on the watch. For the plugin path, use the separately supplied, matched WearTAK Dev host and open Voice's pairing screen. The matched WearTAK Dev host APK is a re-sign of the preceding host payload. The WearTAK host source patch adds **Pair Voice over Wi-Fi** under core Companion settings and forwards through the existing Binder broker. That entry point requires a host build with the patch.
