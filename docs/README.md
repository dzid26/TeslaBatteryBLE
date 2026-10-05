# Documentation layout

Where things go, so agents and humans file docs in the right place.

- [`master-plan.md`](master-plan.md) — phases, locked decisions, and open work.
- [`requirements/`](requirements/) — **product and UX requirements**: behavior,
  flows, open questions, and resolved decisions. Living documents; edit them
  freely. Requirements say *what the product should do and why*.
- [`adr/`](adr/) — **architecture decision records**: one numbered, immutable
  decision per file (context → decision → consequences). ADRs say *which
  technical approach we chose and why*, including trade-offs; supersede an ADR
  with a new one instead of rewriting it. Requirements link to the ADRs that
  implement them, and ADRs link back to the requirement.
- [`protocol/`](protocol/) — wire protocol notes: transport, session/pairing,
  domains.
- [`reference/`](reference/) — external references (Fleet Telemetry vs BLE,
  signal matrix).
- [`research/`](research/) — competitive and exploratory research. The only
  place competitor names appear.
- [`images/`](images/) — screenshots used by the README.
- [`compatibility.md`](compatibility.md), [`faq.md`](faq.md), and
  [`release-checklist.md`](release-checklist.md) — supporting docs.
