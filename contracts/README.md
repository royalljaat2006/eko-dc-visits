# Contracts — the sacred ground

Nothing in here depends on anything else in the repo; everything else depends on this.
One semver for the whole set (see CHANGELOG.md). Additive-only within a minor; breaking
changes need an RFC + ADR + adoption tickets. The server accepts payloads >=2 minor
versions old (mobile skew rule) and enforces a min-version floor via remote config.

| Artifact | File(s) | Status |
|----------|---------|--------|
| C1 entity schemas | c1-entities/*.schema.json | v0.1 (M0 entities) |
| C2 OpenAPI | c2-api/openapi.yaml | v0.1 (M0 endpoints) |
| C3 sync protocol | c3-sync/PROTOCOL.md | v0.1 |
| C4 watermark spec | c4-watermark/ | M1 (stub) |
| C5 form template language | c5-templates/ | M1 (stub) |
| C6 permissions + hard gates | c6-permissions/matrix.yaml | v0.1 |
| C7 KPI formulas | c7-kpis/KPIS.md | v0.1 |

Every schema pairs with prose; examples in prose must validate against schemas in CI.
Consumers use generated/typed clients only — raw HTTP to internal endpoints is lint-banned.
