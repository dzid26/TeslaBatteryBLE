# Requirements: key backup and restore UX

Status: Draft for a UX pass (implementation can be delegated)
Date: 2026-10-05
Related: ADR-0005, `docs/faq.md`, Settings screen ("Vehicle keys" card)

## Why

The key-backup mechanics exist, but the experience only answers the easy part
("is backup on?"). Users keep asking:

1. "How do I know a backup actually happened?"
2. "If I reinstall - Obtainium, browser APK, `adb install` - will my pairing
   come back?"
3. "I want a fresh install without losing the key. How do I test that?"

The app cannot answer (1) authoritatively: Android exposes no per-app
last-backup timestamp to apps. (2) is a restore problem: sideloaded installs do
not auto-restore. (3) is a workflow worth guiding step by step.

## Goals

- Make the backup decision and its consequences understandable where the
  decision is made (pairing time and Settings).
- Make the fresh-install / restore path explicit and testable, including the
  manual-APK case, without promising a restore Android will not perform.
- Give a user who just reinstalled a clear "what now?" screen instead of a
  silent "Not paired".

## Non-goals

- A custom cloud backup, or reading system backup state (not available).
- Replacing the system backup UI.

## Proposed UX

1. **Settings → Vehicle keys** (exists; keep): toggle, mode explanation, and
   the "Android decides when to back up..." line just shipped.
2. **Backup status line**: no timestamp is possible. Offer a "Where does it
   go?" hint: explain that the device-level backup time lives in system
   Settings → Backup, and deep-link there if a public intent exists (check
   `Settings.ACTION_*`; otherwise plain text). Never show a fake "last backed
   up" time.
3. **Pairing card**: show backup mode as part of the pairing summary
   ("Backup: on/off"). After a fresh install that restored vehicles but not the
   key, show a **"Pairing not restored"** card with two actions: *Pair again
   (NFC card)* and *How to restore* (help article). This is the missing state
   today: the app looks merely unpaired, with no explanation why.
4. **Onboarding**: a one-time card or sheet at/after first pairing:
   "Should Android back up this key?" - default off, explain the trade-off in
   two sentences, "Change later in Settings". One global choice, not per car.
5. **Manual-install help**: an in-app help article with the `bmgr` restore
   steps and the manual app-data restore alternative (already in the FAQ), plus
   a short "why doesn't it restore by itself?" explanation.
6. **Fresh-install checklist** (owner-facing docs, not UI): enable backup mode,
   force a backup, uninstall, install, restore, verify no NFC tap is needed.
   `tools/test-key-restore.ps1` automates this on a connected device.

## Copy guidelines

- Say "Android backup", not "our backup" - there is no server.
- Distinguish the three cases: store install (auto-restore possible), device
  transfer (works), manual APK (manual restore only).
- Never claim the key is backed up unless the toggle is on and the device has
  backup enabled.
- The default (device-only) is not a bug; say why it exists: the key is
  hardware-wrapped and cannot leave the device.

## Acceptance criteria

- A user who turns backup on can answer "what happens on a new phone / after a
  reinstall?" without leaving Settings.
- A user who installs via Obtainium can find the exact restore steps in-app.
- A fresh install with restored data shows pairing restored; without data it
  shows the re-pair card with the NFC walkthrough - never a silent failure.
- No screen implies the app can report when Android last backed up.

## Open questions for the UX pass

- Should the backup opt-in be asked during pairing onboarding, or stay
  Settings-only?
- Is an in-app encrypted export/import of the key worth it for non-adb users,
  or is the FAQ + NFC re-pair enough?
- Where should the "Pairing not restored" card live: cars list, car view, or
  both?
