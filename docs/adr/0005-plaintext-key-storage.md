# ADR-0005: Plaintext key storage for backup portability

Status: Accepted
Date: 2026-10-05

## Context

- ADR-0001 chose Keystore-encrypted key storage. Keystore keys are hardware-bound
  and never included in Android backup, so a backed-up ciphertext can never be
  decrypted on a new device: pairing cannot survive a phone change.
- The app enrolls with CHARGING_MANAGER by default. A leaked key can read vehicle
  data and control charging, but cannot unlock, drive, or add new keys (that needs
  the NFC card tap plus vehicle confirmation).
- App-private storage is sandboxed from other apps. Android Auto Backup is gated
  by the user's Google account and, on modern Android, end-to-end encrypted with
  the device lock secret.

## Decision

- Store the P-256 private key as PKCS#8 base64 in app-private SharedPreferences,
  not wrapped by a Keystore key, so Android backup can restore pairing on a new
  phone.
- Keep the legacy Keystore-encrypted format readable for one release cycle and
  migrate it transparently on first load.

## Consequences

- Pairing survives device restore; no NFC re-pair is needed.
- Rooted or forensically extracted devices expose the key; anyone with the backup
  and the lock secret can use it (near the car for BLE; Fleet API additionally
  requires Tesla account access).
- Revisit before adding DRIVER/OWNER roles or non-charging scopes: those raise the
  impact of key compromise and justify hardware-backed storage (TEE/StrongBox).
- Supersedes the key-storage bullet of ADR-0001.
