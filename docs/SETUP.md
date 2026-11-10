# Install and use

For **0.3 audio onboarding alpha**, install the new signed APK as an update to 0.2 (same
application ID and signing key). Tap **Choose PTT button** to learn a delivered side button.
For no-typing setup, use **Listen for setup** and **Play audio code** on your TAKS onboarding
card after the server update is deployed. See [sound setup and buttons](AUDIO_ONBOARDING.md).

The current server deployment is not verified; if your card has no audio button, it still
needs the matching TAKS change. The 0.3 APK alone does not add that button to an older node.

# Install and use the standalone alpha

## Installation

The standalone app's package is `se.takcampus.voice.standalone`. It installs separately
from the 0.1 importer prototype (`se.takcampus.voice.watch`), so that prototype can remain
installed. Look for **WearTAK Voice with the teal microphone icon**.

On a computer already paired with the watch through wireless ADB:

```bash
adb connect WATCH_IP:CONNECTION_PORT
adb -s WATCH_IP:CONNECTION_PORT install -r weartak-voice-0.3.0-audio-onboarding-alpha.apk
```

The computer and watch must be on the same Wi-Fi network. Pairing and connection ports
are different on current Wear OS; use the port from the main Wireless debugging screen
for `adb connect`. Android API 30 or later is required.

## Connect and talk

1. Open the app and tap **Server & login**.
2. Set the Mumble hostname, port (normally 64738), and server-authorized watch username.
3. Supply a password if the server requires one, or import an accepted client identity.
4. Tap **Connect** and allow microphone access. Notification permission is optional but
   makes the ongoing disconnect control easier to find.
5. Choose a live channel and tap **Join selected channel**.
6. Once the state is **READY**, hold **HOLD TO TALK**. Release to stop.

PTT also releases when the screen loses focus, the channel changes, the network is lost,
transmission is restricted, or the connection is stopped. A continuous press is limited to
60 seconds. There is no automatic reconnect or automatic resume of a transmission.

The service can continue receiving while the screen is off, but physical-watch background
and battery behaviour still need testing. Tap **Disconnect** or use the notification action
to end the session. The watch needs its own usable network route; an existing CoT/BLE
Companion link is not itself an Internet connection or voice relay.

## Certificates and trust

Use **Client certificate / trust → Import client identity (.p12)** to import a certificate
and private key accepted by your Mumble server. Enter the PKCS#12 file password when asked.
This is distinct from a Mumble login password. An imported client identity does not by itself
make a server trust that user, and the app cannot extract an identity from WearTAK.

For a private server CA, choose **Import trusted private CA**, inspect the displayed CA
identity and confirm. Public system CAs are used by default. Hostname and certificate-chain
checks remain enabled. Use **Use public system CAs** to remove a private trust configuration.

Certificate-only login was verified against a registered certificate identity on the local
Mumble test server. Acceptance by the current TAKS authenticator is a separate, unverified
integration; this change does not modify its certificate-to-user mapping.

## Channel and certificate files

The app supports the Android document picker when one is available. Many watch installations
have no picker. In that case, open the app once and copy files into its own import folder:

```bash
adb -s WATCH_IP:CONNECTION_PORT push voice.zip /sdcard/Android/data/se.takcampus.voice.standalone/files/imports/
adb -s WATCH_IP:CONNECTION_PORT push client.p12 /sdcard/Android/data/se.takcampus.voice.standalone/files/imports/
```

Tap the corresponding import command and select the staged filename. A successfully imported
PKCS#12 staging copy is removed after its contents have been stored encrypted. Files selected
through another provider are not deleted.

Review an imported channel package and tap **Accept channels**. **Choose mission server**
selects one of its endpoints; importing does not automatically connect. Saved channel IDs are
checked against their saved names before joining, so a reused server ID cannot silently
select a different channel.

Automatic Martine delivery and Companion forwarding are not part of this alpha. Acoustic setup
is implemented in 0.3 and requires the matching TAKS update described above.
