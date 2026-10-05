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
  automatically: identical key bytes, `Phone key: Paired · slot 8`, history
  intact, no NFC re-pair.
- `bmgr restore <token> <package>` remains the fallback when automatic restore
  is off or the install did not restore.

## Decided against (for now)

- Onboarding flow, "pairing not restored" card, in-app key export/import.
- A danger-zone "forget key" action: uninstall or system "clear storage"
  already removes local keys, and the car's key list removes the whitelist
  entry. Deleting locally would not purge the Google backup copy, so the
  button would give false confidence.
