# Sessions, crypto, and pairing

## Key material

- Vehicle keys are P-256 (secp256r1) key pairs generated on-device.
- Default mode is device-only: the private keys are encrypted with an AES key
  held in the hardware-backed Android Keystore, so they are not readable from a
  backup.
- Optional portable mode (one global setting, user opt-in in Settings) stores the
  private keys as PKCS#8 base64 in app-private SharedPreferences so Android's
  encrypted backup can restore pairing on a new phone (ADR-0005).
- Keys are written in the mode selected at write time. Changing the setting
  re-saves every stored key in the new format; loading never converts between
  modes. A device-only key that cannot be decrypted (for example one restored
  onto another phone) is discarded on load, and that car has to be paired again.
- Keys never leave the device and are never logged. The default enrolled role is
  `CHARGING_MANAGER` (read + charge control, no unlock/drive). TEE/StrongBox-backed
  storage is a future hardening step if higher-privilege roles are added.

## Session handshake (per domain)

Each domain (VCSEC, Infotainment) has its own session:

1. **Request**: the client sends its P-256 public key and a random 16-byte request
   UUID. The UUID is the challenge.
2. **Session info**: the vehicle answers with the session info (its own public key,
   epoch, counter, clock time) and an HMAC-SHA256 tag over the VIN, the challenge and
   the session info, keyed from the ECDH-derived key.
3. **Verification**: the client runs ECDH with the vehicle's public key, recomputes the
   tag, and drops the answer if it does not match.
4. **Traffic**: messages are encrypted and authenticated, with per-epoch counters and
   clock synchronization for session validity.

The handshake does not prove that the client holds its private key; that is shown only
implicitly, when the vehicle accepts the client's first encrypted command.

| Transport | Symmetric crypto | Replay protection |
| --- | --- | --- |
| BLE | AES-GCM (12-byte nonce) | Per-epoch counters + anti-replay window + clock sync |
| Fleet API (reference only) | HMAC-SHA256 | Same counters |

Sessions are not persisted: each one lives in memory only, per car and domain, so
there is no encrypted session cache and no stored counter. After an app restart the
handshake runs again, and its session info returns the vehicle's current epoch and
counter. While the app keeps running it reuses a session across BLE reconnects, and
handshakes again before a wake and after repeated decryption failures (the car may
have rotated its session). Implementation:
[`TeslaSession.kt`](../../core/src/main/kotlin/com/dzid26/teslable/core/protocol/TeslaSession.kt),
[`TeslaCrypto.kt`](../../core/src/main/kotlin/com/dzid26/teslable/core/protocol/TeslaCrypto.kt),
[`Metadata.kt`](../../core/src/main/kotlin/com/dzid26/teslable/core/protocol/Metadata.kt),
[`AntiReplayWindow.kt`](../../core/src/main/kotlin/com/dzid26/teslable/core/protocol/AntiReplayWindow.kt).

## Pairing (enrolling a key)

1. The car is put in pairing mode: hold the NFC card on the console until the car
   prompts.
2. The app sends an unsigned `addKey` request over BLE (the `SIGNATURE_TYPE_PRESENT_KEY`
   bootstrap path) with the desired role — default `CHARGING_MANAGER`.
3. The vehicle shows a confirmation prompt; after confirming, the app polls
   `GET_WHITELIST_INFO` until the new key slot appears, then verifies with
   `GET_WHITELIST_ENTRY_INFO`.

Requests can stay in `OPERATIONSTATUS_WAIT` while the car waits for the physical tap,
so the client uses bounded timeouts and clears the state on failure.
Implementation:
[`TeslaPairing.kt`](../../core/src/main/kotlin/com/dzid26/teslable/core/protocol/TeslaPairing.kt).

## Ground rules

- Never log VINs, keys, session keys, or decrypted payloads.
- Crypto changes require deterministic test vectors (`tools/go-fixtures`) and
  negative tests; the Go implementation is the oracle.
- Crypto edge cases that are easy to get wrong in a port: the 12-byte AES-GCM nonce,
  the truncated SHA-1 KDF, metadata construction, counters, and clock skew.
