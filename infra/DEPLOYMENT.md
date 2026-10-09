# Production deployment (R730, self-hosted Docker)

Production runs on Eko's Dell PowerEdge R730 as the Docker stack in
[`self-hosted/`](self-hosted/README.md): `postgres` (PostGIS) + `api`, bound to
loopback, behind the server's single Nginx gateway (path-based route). Follow the
R730 production-safe SOP for every change there: inspect → back up → one approved
change at a time → verify → rollback plan.

There is **no demo mode and no demo data**. The API refuses to start in production
without `DATABASE_URL`, and refuses to start without a real OTP source (`EKO_*` SMS
gateway vars, or `PILOT_OTP`) — the `000000` dev stub is local-development only.

## Go-live checklist

- [ ] `migrate` has run (`docker compose exec api npm run migrate`).
- [ ] Real data loaded: `seed:calling-sheet` (CSPs, DCs, circles, assignments), then
      `user:create` for the first admin (see `self-hosted/README.md`).
- [ ] `JWT_SECRET` is a fresh random value; `PILOT_OTP` (if used) is a fresh value —
      never one that appeared in git history.
- [ ] Real SMS OTP (`EKO_*`) configured, and rate limiting verified.
- [ ] DPDP: real staff GPS/attendance is personal data; hosting is India-resident (R730).
- [ ] Backup taken before every change (`/home/deepanshu/backups/dc-visits/<timestamp>/`).

## Updating

```sh
cd /home/deepanshu/Csp-Visit-Application
git fetch https://github.com/royalljaat2006/eko-dc-visits.git main && git reset --hard FETCH_HEAD
cd infra/self-hosted
docker compose build api && docker compose up -d --no-deps api   # API only; postgres untouched
```

Rollback: re-tag the saved image (`self-hosted-api:before-<timestamp>`) as `latest`
and `up -d --no-deps api`; restore data from the backup's `db.sql` if needed.
