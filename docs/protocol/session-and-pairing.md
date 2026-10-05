# Sessions, crypto, and pairing

## Key material

- Vehicle keys are P-256 (secp256r1) key pairs generated on-device.
- Default mode is device-only: the private key is encrypted with an AES key held
  in the Android Keystore, so it is not readable from a backup.
- Optional portable mode (user opt-in in Settings) stores the private key as
  PKCS#8 base64 in app-private SharedPreferences so Android's encrypted backup can
  restore pairing on a new phone (ADR-0005).
- Keystore-encrypted material is decrypted and migrated transparently on first
  load into the currently selected mode.
- Keys never leave the device and are never logged. The default enrolled role is
  `CHARGING_MANAGER` (read + charge control, no unlock/drive). TEE/StrongBox-backed
  storage is a future hardening step if higher-privilege roles are added.

## Session handshake (per domain)

Each domain (VCSEC, Infotainment) has its own session:

1. **ECDH**: client and vehicle exchange P-256 public keys and derive a shared secret.
2. **Session info**: authenticated metadata binds the session to the vehicle VIN and
   the client's identity (HMAC over metadata with the ECDH-derived key).
3. **Challenge/response**: the vehicle issues a challenge; the client proves possession
   of the enrolled private key.
4. **Traffic**: messages are encrypted and authenticated, with per-epoch counters and
   clock synchronization for session validity.

| Transport | Symmetric crypto | Replay protection |
| --- | --- | --- |
| BLE | AES-GCM (4-byte nonce) | Per-epoch counters + anti-replay window + clock sync |
| Fleet API (reference only) | HMAC-SHA256 | Same counters |

Sessions can be cached (encrypted) to skip handshakes on reconnect; counters must
persist so replays are rejected across restarts. Implementation:
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
- Crypto edge cases historically mis-handled by ports: 4-byte AES-GCM nonces,
  truncated SHA-1 KDF, metadata construction, counter persistence, and clock skew.
