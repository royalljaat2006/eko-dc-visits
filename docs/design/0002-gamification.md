# Design 0002 — Gamification (DC Scorecard)

Status: ACCEPTED (product-owner request 2026-07-09). Constrained by BUILD_PLAN
§15 #10 (DC dignity floor) and kill-list #2 (no opaque scores).

## Principles

1. **Transparent by construction**: every point is a published rule in
   contracts/c7-kpis (dc_score_v1). The DC sees the same number and the same
   breakdown their managers see — gamification is the self-view parity rule
   with celebration on top.
2. **Celebrate compliance, never automate punishment**: points, streaks and
   badges reward visits done, geo-verified evidence, on-time starts. Scores
   NEVER feed pay, discipline, or enforcement (those run on the governed C7
   compliance formulas with their exception workflows).
3. **Effort the DC controls**: no points for things rural reality controls
   (network, GPS quality). A flagged-outside visit still earns visit points —
   it just misses the verification bonus.
4. **Cohort fairness before comparison**: circle-level views show progress
   cards, not a punitive ranking; cross-cohort leaderboards wait for the
   cohort-normalization work (M3 productivity formula).

## dc_score_v1 (published formula — see c7-kpis/KPIS.md)

+50 per visit · +20 geo-verified bonus per INSIDE visit · +30 on-time start
(≤09:30 IST) · +10 × attendance-streak day (capped at 7).

Badges: EARLY_BIRD (start ≤09:00 IST) · PERFECT_DAY (≥3 visits, all INSIDE) ·
STREAK_3 / STREAK_7 (consecutive days with a Start Day event).

## Surfaces

- Web: "Scorecard" tab (DC self; Circle Head & National Head see their scope's
  cards). Android (M1 app module): home-screen points + streak.
- Server-computed at read time from existing evidence read models — no new
  evidence types, no sync-protocol changes.
