# Requirements: multi-phone clones (draft, decide later)

Status: Captured direction (2026-10-05); not scheduled
Related: `docs/requirements/key-backup-ux.md`, `docs/requirements/multi-vehicle.md`

## Direction

Two or three phones should act as clones of one install: each collects battery
data when the others are not in range, and the data merges so the history looks
complete on every phone. Key cloning would avoid pairing each phone separately.

## Decisions already made

- Vehicle keys stay per car (ADR-0005): one key per car, generated at pairing.
- Whether the key is also per phone or cloned/shared is **TBD**.

## Open questions

- **Sync transport.** The app promises "nothing leaves the phone". Options:
  direct phone-to-phone (Nearby, Wi-Fi LAN, QR/export), a user-owned cloud, or
  manual CSV export/import. This decides the privacy story.
- **Connection coordination.** The car allows about three concurrent BLE
  connections and stops advertising when full. Three tracking phones could
  occupy all slots; clones may need to coordinate who connects (for example one
  active poller at a time).
- **Key model if cloning.** A shared key means no extra pairing but shared
  revocation and a wider theft blast radius; per-phone keys mean one NFC tap
  per phone but independent revocation. Decide together with the sync design.
- **Merge rules.** Dedupe by (vehicle, timestamp, percent), handle clock skew
  between phones, and define conflict handling.
- **Which phone notifies.** Only one phone should run the foreground service
  and notification when several are in range.

## Constraints

- Each phone stores its own per-car key today; clones must not break the
  fresh-key-on-pair rule: a cloned key that a car no longer has must never be
  re-enrolled.
- A clone must not become a second source of truth for the same car's state;
  merged history is the shared record.
