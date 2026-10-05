# ADR-0005: Optional plaintext key storage for backup portability

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
  by the user's Google account and, on Android 12+, end-to-end encrypted with the
  device lock secret. Older versions cannot require that encryption condition in
  backup rules.

## Decision

- Default to device-only storage: the P-256 private key is encrypted with an AES
  key held in the Android Keystore, so it is not readable from a backup and does
  not survive a phone change.
- Offer an in-app opt-in setting, "Include key in Android backup". When enabled,
  the private key is stored as PKCS#8 base64 in app-private SharedPreferences so
  Android backup can restore pairing on a new phone. The switch shows a warning
  dialog before it takes effect.
- Keep the encryption-gated backup rules: on Android 12+, cloud backup is allowed
  only when client-side encryption is available (`disableIfNoEncryptionCapabilities`);
  on Android 11 and below the key is excluded from backup.
- Keep the legacy Keystore-encrypted format readable for one release cycle and
  migrate it transparently on first load into the currently selected mode.

## Consequences

- Most users keep hardware-wrapped, device-only keys. Only users who explicitly
  opt in get backup portability.
- Pairing survives device restore on Android 12+ when encrypted backup is
  available and the user opted in; otherwise a re-pair via NFC card tap is needed.
- Rooted or forensically extracted devices expose the key in portable mode;
  anyone with the encrypted backup and the lock secret can use it (near the car
  for BLE; Fleet API additionally requires Tesla account access).
- Revisit before adding DRIVER/OWNER roles or non-charging scopes: those raise
  the impact of key compromise and justify hardware-backed storage (TEE/StrongBox).
- Supersedes the key-storage bullet of ADR-0001.
