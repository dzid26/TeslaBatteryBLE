# ADR-0011: Vico for new charts

Status: Accepted
Date: 2026-10-15

## Context

- The battery history chart is hand-drawn on a Compose `Canvas` (~150 lines):
  axis labels, grid, charging-coloured segments, dashed projections.
- The drive chart (#187) plots the sub-percent SOC over
  miles driven, with regeneration marked, and the owner plans
  gradient fills and touch cursors. Each of those is more hand-drawn code:
  hit testing, nice axis steps, label placement, marker drawing.

## Decision

- New charts use [Vico](https://github.com/patrykandpatrick/vico)
  (`com.patrykandpatrick.vico:compose-m3`, Apache-2.0): Compose-native, follows
  the Material 3 colour scheme, renders under Paparazzi, and ships line
  layers with area gradients, several series per layer, and press-to-show
  markers.
- The drive chart is the first user. It renders from a static
  `CartesianChartModel` (no async model producer), with scroll and zoom off
  and the content fitted to the card, so snapshots are deterministic.
- The battery history chart stays on `Canvas` for now; porting it is a
  separate change once Vico has proven itself on the drive chart.

## Consequences

- One more runtime dependency (Apache-2.0, compatible with AGPL-3.0-only),
  listed in `THIRD_PARTY_NOTICES.md`.
- Cursors and gradients become configuration rather than drawing code.
- Two chart styles coexist until the history chart is ported.
