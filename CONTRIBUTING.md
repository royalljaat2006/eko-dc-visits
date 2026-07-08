# Contributing — Agent Operating Manual

This repo is built by parallel agents working in lanes. These rules keep lanes from colliding.

## Prime directives

1. **Read the contract before writing code.** Every behavioral claim in a ticket cites a
   `contracts/` section. If the contract is silent, raise an RFC — do not invent shapes.
2. **Directory ownership.** Touch only your lane's territory. `contracts/` changes go through
   the RFC process below. `backend/` migrations are owned solely by Lane A.
3. **Evidence rules.** Field-originated records are append-only, client-UUIDv7-identified,
   triple-timestamped. No UPDATE path on evidence, ever. Judgments are annotations;
   corrections are amendments.
4. **Never block the field on network.** Any DC-facing operation must complete offline.
5. **The hard-gate list is closed** (`contracts/c6-permissions/matrix.yaml#hard_gates`).
   No new gates without a product-owner RFC.

## Contract changes (RFC micro-process)

- A contract change is a PR touching only `contracts/` + `CHANGELOG.md` entry + migration notes.
- Additive within a minor version: merge freely; consumers adopt at leisure.
- Breaking: bump minor (pre-1.0), record an ADR, create adoption tickets for every impacted lane.
- Mobile skew rule: the server accepts payloads ≥2 contract minor-versions old.

## Ticket format

```
TICKET: <lane>-<number> — <imperative title>
LANE / PHASE
CONTEXT: 2–5 sentences + links to contract sections and ADRs
CONTRACT REFERENCES: exact artifacts + versions
SCOPE — IN / SCOPE — OUT
INTERFACES: seams/fixtures/endpoints exposed or consumed
ACCEPTANCE CRITERIA: numbered, observable, fixture-referenced
SELF-VERIFICATION: exact commands; all must pass
```

## Merge discipline

Trunk-based; branches ≤2 days; feature flags, not long branches. Merge gates: territory
build+tests green, contract conformance if API-adjacent code changed, docs updated in the
same PR. A red nightly full-stack run freezes non-fix merges: keep the skeleton walking.

## Definition of done

Code + tests + README/ADR updates in one PR; self-verification commands pass; no `any` on
API data paths; no raw HTTP to internal endpoints (generated/typed clients only).
