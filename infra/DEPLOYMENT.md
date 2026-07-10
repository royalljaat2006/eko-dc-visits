# Pilot deployment (Vercel)

The site deploys as: static web (`web/dist`) + one serverless function
(`api/index.js`, prebundled Fastify API with C1 schemas and fixtures inlined).
`vercel.json` rewrites `/api/*` to the function and everything else to the SPA.

## One-time setup

```sh
npm i -g vercel
vercel login                    # browser/email auth, once
```

## Deploy

```sh
cd eko-dc-visits
npm install && npm run bundle:function   # regenerate api/index.js from backend/
npm run build:web                        # web/dist
vercel --prod --yes                      # first run creates+links the project
```

## Environment variables (Vercel dashboard → Settings → Environment Variables)

| Var | Required | Purpose |
|-----|----------|---------|
| `JWT_SECRET` | YES before real users | Session token signing (random 32+ chars) |
| `PILOT_OTP`  | YES before real users | Login OTP for enrolled pilot users (replaces dev 000000) |
| `DATABASE_URL` | YES for the pilot | Postgres + PostGIS. Without it the API runs in **DEMO MODE**: seeded in-memory, resets on cold starts |

## Attaching a durable database (required for the weeks-long pilot)

1. Create a free Postgres on [Neon](https://neon.tech) (or Vercel Marketplace →
   Neon). Pick the **Singapore** region until an India region is available —
   note the DPDP data-residency caveat below.
2. Enable PostGIS + apply schema + seed from a trusted machine:
   ```sh
   cd backend
   DATABASE_URL='postgres://…' npm run migrate
   DATABASE_URL='postgres://…' npm run seed     # or load real pilot CSPs/users
   ```
3. Set `DATABASE_URL` on Vercel and redeploy (`vercel --prod`).

## Pilot readiness checklist (from BUILD_PLAN)

- [ ] `PILOT_OTP` + `JWT_SECRET` set (never run public with dev defaults)
- [ ] Real pilot users/CSPs loaded (replace Nandpur fixtures with real data;
      coordinates may start `UNVERIFIED` — first-visit capture bootstraps them)
- [ ] **DPDP residency**: attendance/GPS of real staff is personal data. The
      locked plan requires India-region hosting for production; a short pilot
      on Singapore infra is a product-owner risk call — record it.
- [ ] Backups: enable Neon PITR/branch snapshots
- [ ] Rate limiting + real SMS OTP before scaling beyond the pilot cohort

## Netlify (previous attempt)

Site `eko-dc-visits-pilot` was created but the account hit plan limits; the
Netlify function/db scaffolding was removed in favor of this Vercel setup.
