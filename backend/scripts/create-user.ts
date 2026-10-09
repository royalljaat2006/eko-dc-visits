/**
 * Create (or find) a real user — the way an admin / circle head / national head /
 * HR account comes into existence now that nothing is seeded.
 *   DATABASE_URL=… npm run user:create -- --name "Full Name" --phone 9XXXXXXXXX --role CORPORATE_ADMIN
 * Roles: DC | CIRCLE_HEAD | NATIONAL_HEAD | HR_ADMIN | CORPORATE_ADMIN
 * Idempotent: an existing phone is left untouched (never edited here).
 */
import { randomUUID } from "node:crypto";
import { parseArgs } from "node:util";
import { PgRepos } from "../src/repos/pg.js";
import type { Role } from "../src/domain/types.js";

const ROLES: Role[] = ["DC", "CIRCLE_HEAD", "NATIONAL_HEAD", "HR_ADMIN", "CORPORATE_ADMIN"];
const { values } = parseArgs({ options: { name: { type: "string" }, phone: { type: "string" }, role: { type: "string" } } });
const url = process.env.DATABASE_URL;
const phone = (values.phone ?? "").replace(/\D/g, "").slice(-10);
const role = values.role as Role | undefined;
if (!url || !values.name || !/^[6-9][0-9]{9}$/.test(phone) || !role || !ROLES.includes(role)) {
  console.error('usage: DATABASE_URL=… npm run user:create -- --name "Full Name" --phone 9XXXXXXXXX --role <' + ROLES.join("|") + ">");
  process.exit(1);
}
const repos = new PgRepos(url);
const existing = await repos.findUserByPhone(phone);
if (existing) {
  console.log(`already exists: ${existing.name} (${existing.role}) — not modified`);
  process.exit(0);
}
await repos.insertUser({ id: randomUUID(), tenant_id: "eko", name: values.name, phone, role, status: "ACTIVE", scope_location_id: null });
console.log(`created ${role}: ${values.name} (${phone.slice(0, 2)}******${phone.slice(-2)})`);
process.exit(0);
