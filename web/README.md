# @eko-dc-visits/web — AM Dashboard (Lane E, M0)

Minimal Area Manager dashboard for the M0 walking skeleton. Vite + vanilla TypeScript
(no framework), Leaflet for the map. Ugly is fine; correct is mandatory.

## Run

```sh
cd web
npm install
npm run dev        # http://localhost:5173 — proxies /api → http://localhost:3000 (Lane A backend)
npm run build      # tsc typecheck + vite build → dist/
npm test           # node:test unit tests (IST formatting, chip mapping)
```

Login: a 10-digit phone registered in the backend; the OTP arrives by SMS
(C2 `/auth/otp/request`). Locally, without SMS keys, the backend's dev OTP applies.

## Screens

- `#/login` — phone + OTP (`POST /auth/otp/request`, `POST /auth/otp/verify`). Tokens
  held in memory and mirrored to `sessionStorage`. Problem-details errors shown inline.
- `#/day` — AM day view: IST date picker (defaults to today in Asia/Kolkata), visits
  table (DC, location name+code, check-in time in IST `DD-MM-YYYY HH:mm`, geofence chip,
  planned/unplanned, sync chip) and a Leaflet map with one marker per visit at the
  check-in fix. Polls `GET /dashboard/visits?date=` every 10 s (polling, not websockets,
  per plan); polling pauses while the tab is hidden. States: loading, empty
  ("No visits synced yet for this date"), and error with a Retry button — the UI never
  fabricates or cache-fakes data when the API is unreachable.

## Contract sections consumed

- `contracts/c2-api/openapi.yaml` v0.1.0 (the only API truth):
  `POST /auth/otp/request`, `POST /auth/otp/verify`, `GET /dashboard/visits`;
  schemas `Problem`, `User`, `Location`, `Visit`, plus `geoPoint` from
  `contracts/c1-entities/common.schema.json`.
- `contracts/c6-permissions/matrix.yaml`: AM scope is `assigned-dcs`, enforced
  **server-side**. The dashboard performs **no client-side filtering** of visits —
  it renders exactly what the API returns (see the comment in `src/views/day.ts`).
- ADR-0009: timestamps stored UTC, rendered IST (Asia/Kolkata).

## Architecture rules

- **All network I/O lives in `src/api/client.ts`** — no `fetch` anywhere else
  ("no raw HTTP to internal endpoints", CONTRIBUTING.md). The client maps
  problem-details bodies to a typed `ApiError` and turns any `401` on an
  authenticated call into session-clear + redirect to `#/login`.
- No `any` on API data paths.

## M0 deviations / notes (to revisit in M1)

- **Hand-transcribed types**: `src/api/client.ts` transcribes the C2 shapes it needs.
  M1 replaces this with spec-driven codegen (e.g. `openapi-typescript`) so the client
  can never drift from the contract.
- **No token refresh**: C2 v0.1.0 returns a `refresh_token` but defines no refresh
  endpoint, so expiry (≈1 h JWT) simply forces re-login via the 401 path.
- `Visit.dc_name`, `location_name`, `location_code`, `planned` are optional in the
  spec; absent values render as `—` / "Unplanned" is only shown when `planned=false`.
- `POST /auth/otp/verify` requires `device.hardware` (schema is phone-oriented);
  the web client sends browser-derived stand-ins (`manufacturer: "web"`, user agent
  as model). `public_key` is omitted (optional; recorded-not-enforced in M0).
- Map tiles come from openstreetmap.org (network dependency; fine for M0 dev).
- Tests use `node:test` per ADR-0009, running `.ts` directly via Node 22 type
  stripping (hence `allowImportingTsExtensions` + explicit `.ts` import specifiers).
