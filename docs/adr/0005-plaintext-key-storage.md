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

- Default to device-only storage: vehicle keys (P-256) are encrypted with an AES
  key held in the hardware-backed Android Keystore, so they are not readable from
  a backup and do not survive a phone change.
- Keep one key pair per vehicle, not one global key: each car's key is generated
  when that car is paired, so cars can be revoked or re-keyed independently.
- Never re-enroll a key a car no longer has. Pairing always enrolls a freshly
  generated key for that car, so a key removed in the car (for example after a
  phone theft) stays dead even when app data is restored from backup.
- Offer one global in-app opt-in setting (not per car), "Include vehicle keys in
  Android backup". When enabled, the private keys are stored as PKCS#8 base64 in
  app-private SharedPreferences so Android backup can restore pairing on a new
  phone. Android backups are encrypted with the user's Google account and device
  lock, and the switch shows a warning dialog before it takes effect.
- Keep the encryption-gated backup rules: on Android 12+, cloud backup is allowed
  only when client-side encryption is available (`disableIfNoEncryptionCapabilities`);
  on Android 11 and below the keys are excluded from backup.
- Re-save every stored key when the backup mode changes, so keys always match
  the selected mode. There is no reader for the pre-per-car global format: the
  owner's single install was converted to per-car keys once.

## Consequences

- Most users keep hardware-wrapped, device-only keys. Only users who explicitly
  opt in get backup portability.
- Pairing survives device restore on Android 12+ when encrypted backup is
  available and the user opted in; otherwise a re-pair via NFC card tap is needed.
- Rooted or forensically extracted devices expose the keys in portable mode;
  anyone with the encrypted backup and the lock secret can use them (near the car
  for BLE; Fleet API additionally requires Tesla account access).
- Revisit before adding DRIVER/OWNER roles or non-charging scopes: those raise
  the impact of key compromise and justify hardware-backed storage (TEE/StrongBox).
- Supersedes the key-storage bullet of ADR-0001.
