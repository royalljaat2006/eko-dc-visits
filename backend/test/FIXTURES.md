# Test fixtures (synthetic)

Invented people, phones and places used ONLY by the automated tests
(`test/support/fixtures.ts`) — files in `test/fixtures/` and `npm run contracts:check` (which validates
these files against the C1 schemas). None of it is loaded by the server in any
environment — production runs on real data from the calling sheet and the pilot
roster (see `backend/README.md`, `pilot-data/`, gitignored).
