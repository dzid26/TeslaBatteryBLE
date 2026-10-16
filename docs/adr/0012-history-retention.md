# ADR-0012: History retention — raw for N days, then summaries

Status: Proposed (owner chose N, backstop, shared N and the cut-off on 2026-10-16; navigation data and the storage engine are still open)
Date: 2026-10-16
Related: ADR-0002, ADR-0006, ADR-0008, ADR-0009 (slower parked cadence),
ADR-0004 (cut-off trigger), issue #123, `docs/master-plan.md` (TeslaMate export)

## Context

- Every `ChargeState` read is logged, nothing is deduplicated, and each log file
  keeps its newest 20,000 records (`BleLogs.MAX_RECORDS_PER_FILE`). At the
  10 s awake cadence that is roughly 2-7 days of awake time, depending on how
  much of the day the car is awake and charging.
- Battery health and degradation trends need months to years. A count cap
  forgets exactly the data they need, and what it forgets depends on how chatty
  the car was, not on how old the data is.
- Raw records are bulky and mostly redundant for trends: within a charge
  session or a parked period the interesting content is a few numbers.
- The plan already states the local log stays raw and complete, with filtering
  at export, and that TeslaMate sync keeps records until they are synced. The
  retention rule has to coexist with both.

## Decision

- **Two tiers.** Raw `BleRecord` logs are kept by age, not count: records older
  than **N days** are removed from the raw logs once they have been folded into
  summaries. Summaries are kept indefinitely.
- **N = 90 days** (owner's choice, 2026-10-16). Reasoning: three months covers
  a full season of charging habits and gives plenty of room to debug wake and
  drain behaviour from real-car logs. At 10 s awake polling and about 4 awake
  hours per day it is on the order of 130k charge records per vehicle, a few
  tens of MB, which is fine on a phone. The alternatives considered were 30 days
  (a third of the storage) and 7 days (loses the debugging window).
- **Hard backstop.** Keep a record-count ceiling per file (200,000, confirmed by
  the owner) so a car that is awake all day cannot grow a log without bound. At
  90 days it binds for a car awake more than about 6 hours a day on average
  (at the 10 s cadence); then the window shrinks to what the ceiling holds. When the ceiling bites, fold the oldest records into summaries
  first and only then drop them. Never drop records that have not been
  summarised.
- **Unsynced records survive.** Once TeslaMate sync exists, raw records that are
  not yet exported or synced are exempt from age trimming, up to the backstop.
- **Summaries are a separate artifact.** Per ADR-0006, the raw log never holds
  derived values; summaries are a derived store beside it, rebuildable from the
  raw log for as long as the raw records exist. Two kinds:
  - per session (a charge session, or a parked or driven stretch): start and end
    times, start and end range and SOC, energy added or drained, peak charge
    power, charger voltage and current ranges, odometer delta;
  - per day: awake time, min and max range and SOC, and the inputs the health
    estimators need.
- **Resolution comes from range, not integer SOC.** Summaries store range-based
  quantities (rated range as the car reports it) and the sufficient statistics
  for the slope fit, so an estimator can combine days exactly and uncertainty
  is never derived from the integer SOC step. The SOC resolution work (range-
  based fit) can then change the estimator without invalidating stored
  summaries.
- **Estimators read summaries**, not raw records, for anything older than the
  raw window; recent data may still come from raw.
- **Export** offers the raw window as-is plus the summaries; it is honest about
  the window instead of implying the log is complete.
- **Format versioning.** Summary files carry a version from day one, since the
  cut-off in ADR-0004 will make format changes costly.

## Privacy: navigation data in the raw drive log

The raw `<car>.drive.pblog` stores each DriveState reply verbatim (ADR-0008),
and while the car navigates that includes `active_route_destination` (a name)
and `active_route_coordinates` (the destination's latitude and longitude). Both
are location data. Nothing in the app reads them today, and the destination
stats read model (#207) deliberately does not keep them, but the raw log does.

- Age-based retention bounds how long they stay on the phone (N days) but does
  not remove them sooner.
- Summaries never carry them: they hold range, energy and odometer figures only.
- Export is where they would leave the phone, so the raw export should strip
  the destination name and coordinates by default (an opt-in could keep them),
  and the TeslaMate sync must not send them.
- The coordinates could also be dropped at write time, because no feature needs
  them. That changes the raw-log rule (ADR-0006: log what the car sends) and is
  the owner's decision, not part of this ADR.

## Open choices (for the owner)

1. ~~N~~ Decided: 90 days.
2. ~~Count backstop value~~ Decided: 200,000.
3. ~~Whether the raw window of the command and app-state logs follows the same
   N~~ Decided: yes, they share the cap code today.
4. Navigation destination and coordinates in the raw drive log: keep as logged
   but strip on export (recommended), or drop them when the record is written?

## Consequences

- The 20k cap in `BleLogs` and `HistoryStore.MAX_SAMPLES` goes away in favour of
  age plus a backstop; the in-memory sample cache needs its own bound.
- Code waits for #229 (separate Drive chart) to merge so the history read model
  settles first. This ADR is docs only.
- Old data in the raw window is still lost on reinstall; Android backup rules
  exclude the logs today (ADR-0006). Backing up summaries only is a follow-up
  option once they exist.
- Slower parked polling (ADR-0009) stretches the same N further and needs no
  change here.
