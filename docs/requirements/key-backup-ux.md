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
- A danger-zone "forget key" action. The use cases are already covered, and a
  local delete would overpromise:
  - Selling or handing over the phone: the car's key list (Controls → Locks)
    is the real revocation; uninstall or a factory reset removes the local
    copy. An in-app delete cannot touch the car's whitelist.
  - Key compromise or a lost phone: same car-side removal; a lost phone should
    be locked or remotely wiped.
  - Testing pairing: "Clear pairing cache" already resets the app's pairing
    state, and re-pairing re-enrolls the same key.
  - Getting the key out of Google backup: turn the setting off so the next
    backup pass replaces the cloud copy, or `bmgr wipe` over adb for an
    immediate purge. The app cannot verify the cloud copy, so a button would
    give false confidence.
  - Privacy "delete my data": uninstall or system "Clear storage" covers it.
- Revisit if the app ever enrolls OWNER/DRIVER-scoped keys, where key loss has
  a bigger blast radius.
