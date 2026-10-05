# Requirements: key backup and restore UX

Status: Settings-only scope (2026-10-05)
Related: ADR-0005, `docs/faq.md`

## Scope

One place only: Settings → Vehicle keys. No onboarding sheet, no extra cards,
no export/import, no deep links.

## What Settings must say

- Whether Android may back up the vehicle key (toggle; exists).
- What each mode means, in one sentence (exists).
- When backups run, and where to check or trigger one (exists).
- How restores happen: phone setup, device transfer, or an app install when
  Android's automatic restore is on; otherwise restore manually or re-pair.

## Tested behavior (2026-10-05, Galaxy S24 Ultra)

- Forced a Google backup with `bmgr backupnow`; `settings get secure
  backup_auto_restore` = 1.
- `adb uninstall` + `adb install` restored app data and the portable key
  automatically: identical key bytes, `Phone key: Paired · slot 8`, no NFC
  re-pair. Battery history is excluded from Android backup on purpose, so a
  restored install starts with an empty history and no stale battery reading.
- `bmgr restore <token> <package>` remains the fallback when automatic restore
  is off or the install did not restore.

## Decided against (for now)

- Onboarding flow, "pairing not restored" card, in-app key export/import.
- A "restore now" button: apps cannot run `bmgr`, and there is no public
  restore API (`BackupManager` can request a backup pass, not a restore).
  Auto-restore at install covers the common case; the FAQ documents the adb
  fallback.
- A plain "forget key" action: uninstall or system "Clear storage" covers it.

## Needed: replace the key after a theft

Removing the key in the car (Controls → Locks) revokes it, but a restored
backup brings the same key back. Pairing reuses the stored key
(`loadOrCreate`), so tapping Pair re-enrolls the old public key and a stolen
copy becomes valid again. The theft case is therefore different from the
others:

- Settings → Pairing gets a **Replace key** action: discard the stored key,
  generate a fresh one, and keep cars/VIN/history. Pairing then enrolls only
  the new key.
- The confirmation must say: remove the old key in the car first; this does not
  touch the car's whitelist or existing Google backup copies (turn backup off
  and let a pass replace them, or `bmgr wipe` via adb).
- FAQ: phone stolen → remove the key in the car → on the new phone replace the
  key before pairing → re-enable backup.
- Revisit scopes beyond CHARGING_MANAGER, where key loss has a bigger blast
  radius.
