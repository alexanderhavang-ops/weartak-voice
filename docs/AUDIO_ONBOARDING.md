# Sound onboarding and watch buttons

## User flow (requires the matching TAKS update)

1. Open WearTAK Voice on the watch and tap **Listen for setup** (or **Set up from phone**).
2. Open the existing TAKS onboarding card on the phone. Tap **Play audio code**.
3. Hold the phone speaker close to the watch for about 10–15 seconds. Use media volume,
   and disconnect headphones if sound is going to them instead of the phone speaker.
4. On the watch, check the TAKS hostname and tap **Download**. Review the account,
   voice server and assigned channels, then tap **Accept**.
5. Tap **Connect**. The first assigned channel is joined automatically. Once **Ready to
   talk** appears, hold the screen PTT or your assigned physical button.

No text entry on the watch is needed. The watch still needs working internet access and
publicly trusted HTTPS/TLS for the TAKS and Mumble endpoints. No phone companion is needed.
A failed transfer changes no saved settings. Play a fresh code after an expired or spent link.
The listener closes on cancellation, after 60 seconds, or when the activity is paused.

## Passwords and trust

The sound contains a **bearer enrollment ticket**, not the Mumble password, TAK password,
private key or long-lived card link. It is not public data: someone nearby who records it
could redeem it first. Perform onboarding privately, as you would with a credential QR code.
The ticket expires after three minutes and succeeds only once, atomically across web workers.
The original card and user are rechecked when it is redeemed.

The watch POSTs the ticket in a JSON body to verified HTTPS on the heard origin. The ticket
is in the acoustic URL fragment, avoiding ordinary URL/access logs. Redirects, HTTP, non-443
onboarding endpoints, userinfo and arbitrary paths are rejected. The hostname is confirmed
before any download. Passwords are fetched only over TLS and saved in the Android Keystore-
encrypted settings vault. Audio PCM is processed in memory and is not saved.

TAKS issues a separate random 256-bit Mumble password for this watch, stored server-side only
as a SHA-256 digest. It is not the operator's TAK password or the shared service password.
Watch logins expire after 30 days; repeat onboarding to renew. **Reset watch access** on the
phone card revokes all watch credentials for that account. Account deletion also revokes them.
Revocation/expiry blocks subsequent logins; it does not kick an already connected session.
The existing Vx authentication branch is retained. TAK mTLS reuse is a separate future task.

The server profile includes actual Murmur channel IDs, selected through existing TAKS policy;
missing or ambiguous channel names fail closed. The app also checks the saved channel name
when joining. A profile replaces the saved voice login and uses system trust; importing an
unrelated private CA or TAK key is not part of this onboarding path.

## Physical PTT

Tap **Choose PTT button**, then press and release the side button you want. The app can bind
Wear OS `KEYCODE_STEM_1`, `_2`, `_3`, or a delivered `KEYCODE_BACK`. It reports which key it
received. Home/power remain reserved. **Screen only** clears the mapping.

The mapped button transmits only while held, with this app in front, connected and joined.
Repeats do not retrigger PTT or extend the 60-second limit. Release, focus loss, leaving the
screen, disconnection and channel changes stop transmission. After focus loss a fresh press
is required. It is not a system-wide or screen-off PTT shortcut.

On Samsung Ultra-family firmware the Quick button may be intercepted by Samsung, including
its long-hold action. If it opens another app instead of appearing in the learning dialog,
try the other side button. Do not assume that a short press mapping overrides a system long
press. SM-L715F/WT-WearOS-9114 button delivery and physical audio still need device testing.

## Transport contract: WTK1

This is an experimental, application-independent HTTPS-link transport, so a future WearTAK
receiver can share it. The voice receiver accepts only its own voice setup path; another app
must explicitly implement its own profile validation and enrollment contract.

- 48 kHz mono PCM, 144 samples (3 ms) per symbol.
- Low tone 2 kHz = 0, high tone 4 kHz = 1; Manchester bit 0 = 01, bit 1 = 10.
- Each frame: 16 alternating training symbols, 32-bit `0xD391C5A7` sync MSB-first using
  Manchester, 44 frame bytes MSB-first using Manchester, then 24 quiet symbols.
- Frame bytes, network byte order: CRC32 of complete link (4), UTF-8 link length (2),
  frame index (1), frame count (1), padded link chunk (32), CRC32 of preceding 40 bytes (4).
- Maximum UTF-8 link length 384 bytes, 12 frames, receiver duration 60 seconds.
- Eight sample phases, per-frame CRC, whole-message CRC, bounded reassembly. A new valid
  first frame can replace an incomplete transfer. CRC is error detection, not authentication.
- Voice link: `https://HOST/api/onboarding/cards/watch-voice#TICKET`.
- Redeem: POST `/api/onboarding/cards/watch-voice/redeem`, JSON `{"ticket":"..."}`.
- Response schema 1: `host`, `port`, `username`, `password`, `displayName`,
  `credentialExpiresAt` (Unix seconds), `missionName`, `channels: [{id, name}]`.

The actual TAKS browser encoder is tested against the Java decoder with
`python3 scripts/check_browser_audio.py --taks-source /path/to/taks` (Node required).
Unit tests cover offsets, low amplitude/noise, clock drift, corruption, bounds, URL rejection
and PTT state transitions. These tests do not replace a phone-speaker/watch-microphone trial.
