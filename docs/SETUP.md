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

1. Open **Voice setup → Enter channel manually → Mumble**.
2. Set the Mumble hostname and port (normally 64738).
3. For guest access enter only the shared server password, or leave it empty on an open server. The watch supplies your saved callsign or a stable WearTAK guest name. For account access enter the assigned username and account password, or import an accepted client identity.
4. Tap **Connect** and allow microphone access. Notification permission is optional but
   makes the ongoing disconnect control easier to find.
5. Choose a live channel and tap **Join**.
6. Once the channel is confirmed, hold **PTT**. Release to stop.

PTT also releases when the screen loses focus, the channel changes, the network is lost,
transmission is restricted, or the connection is stopped. A continuous press is limited to
60 seconds. Following a transport failure, a previously authenticated Mumble session reconnects and attempts to rejoin the confirmed room. Press PTT again to speak.

The service can continue receiving while the screen is off, but physical-watch background
and battery behaviour still need testing. Tap **Disconnect** or use the notification action
to end the session. The watch needs its own usable network route; an existing CoT/BLE
Companion link is not itself an Internet connection or voice relay.

## Companion voice onboarding

Choose a voice ZIP **on the phone** and review its Mumble endpoints. Both Bluetooth and Wi-Fi transfers offer **Set Mumble login**. Keep the imported login, or select a destination and change its authentication.

| Choice | What Voice receives |
| --- | --- |
| Mumble server password | The shared password. Leave it blank for an open server. Voice uses the watch callsign or guest name. |
| TAK auto-enrollment | A reference to a WearTAK server profile. On the watch, accept the package, select the channel and confirm enrollment. Voice generates a new key. The updated WearTAK host uses the selected profile’s current username, password, route and CA on HTTPS port 8446 to request a certificate for that key. |
| Import client certificate (.p12) | The explicitly selected soft certificate, its private key and file password. Choose an imported ATAK soft certificate or a PKCS#12 file on the phone. |
| Mumble account login | The Mumble username and password for that endpoint. |

The watch manual Mumble setup offers the same choices. Enrollment using WearTAK credentials requires the Voice plugin and a host with the enrollment capability. For a standalone install, use the server password, Mumble account or an imported identity. After enrollment, tap **Connect** and confirm the joined channel. The Mumble server must accept the issued certificate. Companion does not copy auto-enrolled ATAK private keys.

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

Companion can deliver channels from the phone without staging a ZIP on the watch.
