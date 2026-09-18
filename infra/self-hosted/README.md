# Self-hosted: one central backend on your own server

Goal: hand the same APK to many people, and every install talks to **one**
backend running on your rack, with data that persists.

```
 phones ──HTTPS──▶  Caddy  :443   ──▶  api :3000 (Node/Fastify)  ──▶  Postgres + PostGIS
 (the APK has        auto TLS            HOST=0.0.0.0                    durable volume
  ONE url baked in)   (Let's Encrypt)     DATABASE_URL / JWT_SECRET / PILOT_OTP
```

The app **only speaks HTTPS** to real devices (plain HTTP is allowed to
`localhost` / `10.0.2.2` only, for dev). So you need a domain + a real
certificate. Two ways to get that — pick one.

---

## Option A — server has a public IP (or a port you can forward)

1. **DNS**: point `dcvisits.yourdomain.com` (A / AAAA record) at the server.
   Open inbound **80** and **443**.
2. On the server, from the repo root:
   ```sh
   cd infra/self-hosted
   cp .env.example .env      # edit: API_DOMAIN, POSTGRES_PASSWORD, JWT_SECRET, PILOT_OTP
   docker compose up -d --build
   docker compose exec api npm run migrate      # once — creates the schema
   docker compose exec api npm run seed         # once — demo users, OR load your own (below)
   ```
   Caddy fetches the TLS cert on first request. Check:
   `curl https://dcvisits.yourdomain.com/api/v1/master-data/locations` → 401 (means it's up).
3. Your API base URL is `https://dcvisits.yourdomain.com/api/v1/`.

## Option B — no public IP / can't forward ports (Cloudflare Tunnel)

Free, no open ports, Cloudflare gives the HTTPS cert.

1. Add your domain to Cloudflare. Install `cloudflared` on the server.
2. Run only Postgres + api from compose (skip Caddy):
   ```sh
   docker compose up -d --build postgres api
   docker compose exec api npm run migrate && docker compose exec api npm run seed
   ```
   Expose the api port to the host: add `ports: ["127.0.0.1:3000:3000"]` to the
   `api` service first.
3. `cloudflared tunnel create dcvisits`
   `cloudflared tunnel route dns dcvisits dcvisits.yourdomain.com`
   `cloudflared tunnel run --url http://localhost:3000 dcvisits`
   (or a config file + `systemctl enable --now cloudflared`).
4. Your API base URL is `https://dcvisits.yourdomain.com/api/v1/`.

---

## Load your real users / CSPs (instead of the demo seed)

`npm run seed` loads the fake "Nandpur" fixtures. Two ways to bring in real data:

**Circle 1A85 pilot roster** (spec §7 — the 7 real DCs with their phones +
per-user dashboard links, from `fixtures/circle-1a85-roster.json`):

```sh
docker compose exec api npm run seed:pilot   # Nandpur base + Circle 1A85 DCs
```

Then, once the DC users exist, everything else is bulk-loadable from the
**Circle Head workbench** on the web dashboard (Circle 1A85 still needs a
Circle Head user + CSPs first — see below):

| Web upload button | Loads | Endpoint |
|---|---|---|
| Upload assignments | CSP → DC mapping (`csp_code`, `dc_phone`) | `/circle/csp-assignments/import` |
| Bulk CSP details | CSP master fields — address + §3.1 profile (dry-run diff, then commit) | `/circle/csp-details/import` |
| Home locations | DC / CH `home_lat` / `home_lng` ("Excel sheet for Lat Long") | `/circle/home-locations/import` |

**Everything else** (Banks, Circles, Circle Head users, the CSP Location tree
with `coordinates` + `radius_m`): edit copies of the JSON under
`fixtures/nandpur/` and point the loader at them, or `psql` rows in directly
(schema is `backend/migrations/001…007`). Users need: `phone` (10 digits, the
login id), `name`, `role` (`DC` / `CIRCLE_HEAD` / `NATIONAL_HEAD` / `HR_ADMIN` /
`CORPORATE_ADMIN`), `status: ACTIVE`, optional `dashboard_url`.

---

## Build the APK everyone installs

One URL is compiled in. Rebuild whenever the URL changes.

```sh
cd android

# Testing / internal (no OTP screen — number only):
./gradlew :app:assembleDebug -PapiBaseUrl=https://dcvisits.yourdomain.com/api/v1/
#   -> app/build/outputs/apk/debug/app-debug.apk   (debug-signed)

# Real distribution (2-step OTP with your PILOT_OTP, release-signed):
./gradlew :app:assembleRelease -PapiBaseUrl=https://dcvisits.yourdomain.com/api/v1/
#   -> app/build/outputs/apk/release/app-release.apk
```

You can also put the URL in `android/local.properties` instead of the flag:
`api.base.url=https://dcvisits.yourdomain.com/api/v1/`

### Signing the release APK (do this once)

Debug builds are signed with a throwaway key — fine for a handful of testers,
but for wide distribution use a real keystore so updates install over each
other and Play Protect is quieter.

```sh
keytool -genkey -v -keystore eko-dcvisits.jks -alias eko \
  -keyalg RSA -keysize 2048 -validity 10000
```

Add to `android/app/build.gradle.kts` (read secrets from `local.properties` or
env — never commit the keystore or passwords):

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("EKO_KEYSTORE") ?: "eko-dcvisits.jks")
            storePassword = System.getenv("EKO_KEYSTORE_PW")
            keyAlias = "eko"
            keyPassword = System.getenv("EKO_KEY_PW")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
}
```

**Bump `versionCode` on every release** (in `app/build.gradle.kts`) or phones
won't accept the update. Keep the `.jks` + passwords backed up — lose them and
you can't ship updates to existing installs.

---

## Distribute the file

Everyone installs the **same** `.apk`; the backend URL is inside it.

- **Simplest**: drop the apk in `infra/self-hosted/download/` on the server —
  the Caddyfile already serves `https://<API_DOMAIN>/download/<file>.apk`. Send
  people that link. They enable "install unknown apps" once, then tap it.
- **Nicer**: Firebase App Distribution (free) or Diawi — gives testers a page,
  install tracking, and update notifications.
- **Play Store internal testing** track if you want managed rollout.

---

## Before non-test users

- [ ] `PILOT_OTP` + `JWT_SECRET` set to real secrets (never the defaults).
- [ ] Use the **release** build (2-step OTP), not the SKIP_OTP debug build, for
      anyone outside your own team.
- [ ] Rate limiting + a real SMS gateway before this grows past a controlled
      cohort (right now everyone shares one `PILOT_OTP`).
- [ ] Backups: `docker compose exec postgres pg_dump -U dcvisits dcvisits`
      on a cron, off-box. Test a restore.
- [ ] Data-residency: real staff GPS + attendance is personal data (DPDP). If
      the server or its backups leave India, that's a documented risk call.
- [ ] `SQLCipher`-at-rest on the phone and real device binding approval are
      still M1 hardening TODOs (see android/README.md).
