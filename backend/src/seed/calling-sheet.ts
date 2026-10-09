/**
 * Calling-sheet loader — seeds ANY Repos implementation from an export of
 * Eko's "Calling Sheet" (tab "Calling Sheet New", CSV).
 *
 * What it takes from the sheet (and only this):
 *   CSP ID, Full Address, District, State, Circle (LHO), Population,
 *   Circle Head Name, District Coordinator, Mobile Number DC.
 * DCs are named Eko officials, so their name + phone are real. CSP operators are
 * anonymous: CSP name / mobile / email columns are NEVER read, and a CSP's
 * `name` is its CSP ID.
 *
 * The sheet is the source of truth for order: `csp_profile.sheet_row` keeps the
 * 1-based data-row number so the admin list can follow the calling sheet.
 *
 * Ids are deterministic (sha1-derived UUIDs) so re-running converges — the repos
 * insert-if-absent. CSPs have no coordinates in the sheet: they get 0,0 with
 * coordinate_confidence UNVERIFIED (improved later by approved DC edits /
 * first-visit capture). The sheet export is real personal data — it is read
 * from a local path and never committed (see .gitignore: pilot-data/).
 */
import { createHash } from "node:crypto";
import type { Bank, Circle, CircleMembership, CspAssignment, LocationNode, User } from "../domain/types.js";
import type { Repos } from "../repos/types.js";
import { istDateOf } from "../geo.js";

const TENANT_ID = "eko";
const REQUIRED_HEADERS = ["CSP ID", "Full Address", "District", "State", "Circle (LHO)", "District Coordinator", "Mobile Number DC"] as const;

/** RFC-4180 CSV parser (quoted fields may contain commas, quotes and newlines). */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  const src = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  for (let i = 0; i < src.length; i++) {
    const ch = src[i]!;
    if (quoted) {
      if (ch === '"') {
        if (src[i + 1] === '"') {
          field += '"';
          i++;
        } else quoted = false;
      } else field += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ",") {
      row.push(field);
      field = "";
    } else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && src[i + 1] === "\n") i++;
      row.push(field);
      field = "";
      rows.push(row);
      row = [];
    } else field += ch;
  }
  if (field !== "" || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

/** Stable UUID from a namespace + key (sha1, RFC-4122 v5 layout). */
function stableUuid(ns: string, key: string): string {
  const h = createHash("sha1").update(`${ns}:${key}`).digest();
  h[6] = (h[6]! & 0x0f) | 0x50;
  h[8] = (h[8]! & 0x3f) | 0x80;
  const x = h.subarray(0, 16).toString("hex");
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20, 32)}`;
}

/** Indian mobile: last 10 digits, must start 6–9. Returns "" when unusable. */
export function normalizeDcPhone(raw: string): string {
  const digits = raw.replace(/\D/g, "");
  const last10 = digits.slice(-10);
  return /^[6-9][0-9]{9}$/.test(last10) ? last10 : "";
}

export interface CallingSheetSummary {
  csps: number;
  dcs: number;
  circles: number;
  assigned: number;
  /** Rows with a usable address (the sheet fills this in gradually). */
  with_address: number;
  skipped: Array<{ sheet_row: number; csp_code: string; reason: string }>;
}

export async function seedCallingSheet(repos: Repos, csvText: string, opts: { now?: Date } = {}): Promise<CallingSheetSummary> {
  const now = opts.now ?? new Date();
  const nowIso = now.toISOString();
  const today = istDateOf(now);

  const rows = parseCsv(csvText);
  const headerIdx = rows.findIndex((r) => r[0]?.trim() === "CSP ID");
  if (headerIdx < 0) throw new Error('Calling sheet: no header row starting with "CSP ID"');
  const header = rows[headerIdx]!.map((h) => h.trim());
  const col = (name: string): number => header.indexOf(name); // first occurrence wins (the sheet repeats some names)
  for (const h of REQUIRED_HEADERS) if (col(h) < 0) throw new Error(`Calling sheet: missing column "${h}"`);
  const cell = (r: string[], name: string): string => (r[col(name)] ?? "").trim();
  const chCol = col("Circle Head Name");

  // The sheet leaves some DC phones blank on rows whose DC (same name, same
  // circle) has a phone elsewhere. Fill only when that phone is unambiguous.
  const isPlaceholder = (name: string): boolean => name === "" || /^(tba|tbd|na|n\/a|-)$/i.test(name);
  const phonesByDc = new Map<string, Set<string>>();
  for (const r of rows.slice(headerIdx + 1)) {
    const name = cell(r, "District Coordinator");
    const phone = normalizeDcPhone(cell(r, "Mobile Number DC"));
    if (!cell(r, "CSP ID") || isPlaceholder(name) || !phone) continue;
    const key = `${cell(r, "Circle (LHO)")}|${name.toLowerCase()}`;
    (phonesByDc.get(key) ?? phonesByDc.set(key, new Set()).get(key)!).add(phone);
  }

  const bank: Bank = {
    id: stableUuid("bank", "SBI"), tenant_id: TENANT_ID, name: "State Bank of India", code: "SBI", status: "ACTIVE", updated_at: nowIso,
  };
  await repos.insertBank(bank);

  const circles = new Map<string, Circle>();
  const dcs = new Map<string, User>(); // by phone
  const skipped: CallingSheetSummary["skipped"] = [];
  let sheetRow = 0;
  let csps = 0;
  let assigned = 0;
  let withAddress = 0;

  for (const r of rows.slice(headerIdx + 1)) {
    const code = cell(r, "CSP ID");
    if (!code) continue; // trailing/blank rows
    sheetRow++;

    const circleName = cell(r, "Circle (LHO)");
    const dcName = cell(r, "District Coordinator");
    const placeholderDc = isPlaceholder(dcName); // "TBA" etc. = no DC yet, never a user
    let phone = placeholderDc ? "" : normalizeDcPhone(cell(r, "Mobile Number DC"));
    if (!phone && !placeholderDc) {
      const known = phonesByDc.get(`${circleName}|${dcName.toLowerCase()}`);
      if (known?.size === 1) phone = [...known][0]!;
    }
    if (!circleName) {
      skipped.push({ sheet_row: sheetRow, csp_code: code, reason: "no Circle (LHO)" });
      continue;
    }

    let circle = circles.get(circleName);
    if (!circle) {
      circle = { id: stableUuid("circle", circleName), tenant_id: TENANT_ID, name: circleName, status: "ACTIVE", updated_at: nowIso };
      circles.set(circleName, circle);
      await repos.insertCircle(circle);
    }

    const address = cell(r, "Full Address");
    if (address) withAddress++;
    const profile: Record<string, string> = { sheet_row: String(sheetRow), circle: circleName };
    const population = cell(r, "Population");
    if (population) profile.population = population;
    const circleHead = chCol >= 0 ? (r[chCol] ?? "").trim() : "";
    if (circleHead) profile.circle_head_name = circleHead;

    const csp: LocationNode = {
      id: stableUuid("csp", code),
      tenant_id: TENANT_ID,
      bank_id: bank.id,
      type: "CSP",
      parent_id: null,
      name: code, // CSP operators stay anonymous: the CSP ID is the name
      code,
      ...(address ? { address } : {}),
      state: cell(r, "State"),
      district: cell(r, "District"),
      coordinates: { lat: 0, lng: 0 },
      radius_m: 150,
      coordinate_confidence: "UNVERIFIED",
      status: "ACTIVE",
      csp_profile: profile,
      updated_at: nowIso,
    };
    await repos.insertLocation(csp);
    csps++;

    if (!phone) {
      skipped.push({
        sheet_row: sheetRow,
        csp_code: code,
        reason: placeholderDc
          ? `DC is "${dcName || "blank"}" — CSP loaded, not assigned`
          : `DC phone unusable ("${cell(r, "Mobile Number DC")}") — CSP loaded, not assigned`,
      });
      continue;
    }
    let dc = dcs.get(phone);
    if (!dc) {
      dc = {
        id: stableUuid("dc", phone), tenant_id: TENANT_ID, name: dcName || phone, phone, role: "DC", status: "ACTIVE", scope_location_id: null,
      };
      dcs.set(phone, dc);
      await repos.insertUser(dc);
      await repos.insertCircleMembership({
        id: stableUuid("membership", `${circle.id}:${dc.id}`), tenant_id: TENANT_ID, circle_id: circle.id, user_id: dc.id,
        role_in_circle: "DC", valid_from: today, valid_to: null,
      } satisfies CircleMembership);
    }
    await repos.insertCspAssignment({
      id: stableUuid("assignment", `${csp.id}:${dc.id}`), tenant_id: TENANT_ID, circle_id: circle.id, csp_location_id: csp.id,
      dc_user_id: dc.id, assigned_by_user_id: dc.id, reason: "INITIAL_ALLOCATION", valid_from: today, valid_to: null, updated_at: nowIso,
    } satisfies CspAssignment);
    assigned++;
  }

  return { csps, dcs: dcs.size, circles: circles.size, assigned, with_address: withAddress, skipped };
}
