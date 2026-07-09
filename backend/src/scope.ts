/**
 * THE authorization choke point (ADR-0006, C6 matrix.yaml).
 *
 * Given a principal, resolves the visible DC-user-id set and location-id set.
 * Every scoped read query in the repository layer takes the resulting Scope —
 * no endpoint writes its own ad-hoc WHERE logic.
 *
 *   DC              → self; territory = assigned CSPs (CspAssignment) ∪ own
 *                     beat-plan stops, plus ancestors
 *   CIRCLE_HEAD     → DCs in circles this user heads (time-bounded
 *                     CircleMembership; design 0001 §2)
 *   NATIONAL_HEAD   → tenant root, read (design 0001 §7)
 *   CORPORATE_ADMIN → tenant root
 *   HR_ADMIN        → attendance-only (no visit/location scope in M0/M1)
 *   BANK_OFFICIAL   → per-bank read (M2; empty scope until then)
 */
import type { LocationNode, Principal } from "./domain/types.js";
import type { Repos, Scope } from "./repos/types.js";
import { istDateOf } from "./geo.js";

function withAncestors(locationIds: Set<string>, all: LocationNode[]): Set<string> {
  const byId = new Map(all.map((l) => [l.id, l]));
  const out = new Set<string>();
  for (const id of locationIds) {
    let cur: LocationNode | undefined = byId.get(id);
    while (cur && !out.has(cur.id)) {
      out.add(cur.id);
      cur = cur.parent_id ? byId.get(cur.parent_id) : undefined;
    }
  }
  return out;
}

export async function resolveScope(repos: Repos, principal: Principal, now: Date): Promise<Scope> {
  const tenant_id = principal.tenant_id;

  if (principal.role === "CORPORATE_ADMIN" || principal.role === "NATIONAL_HEAD") {
    // NATIONAL_HEAD is read-everything (C6); writes are gated per-endpoint
    // (e.g. sync-batch create is DC-only), not via scope.
    return { tenant_id, dc_user_ids: "ALL", location_ids: "ALL" };
  }

  const asOf = istDateOf(now);
  let dcIds: Set<string>;
  if (principal.role === "DC") {
    dcIds = new Set([principal.user_id]);
  } else if (principal.role === "CIRCLE_HEAD") {
    dcIds = new Set(await repos.listCircleDcIds(tenant_id, principal.user_id, asOf));
  } else {
    // HR_ADMIN (attendance-only) and BANK_OFFICIAL (M2) have no visit/location
    // scope yet — resolve empty rather than guessing.
    dcIds = new Set();
  }

  // Territory = assigned CSPs ∪ beat-plan stop locations, plus ancestors.
  const [assignments, plans] = await Promise.all([
    repos.listActiveCspAssignments(tenant_id, dcIds, asOf),
    repos.listBeatPlansForDcs(tenant_id, dcIds),
  ]);
  const baseLocationIds = new Set<string>([
    ...assignments.map((a) => a.csp_location_id),
    ...plans.flatMap((p) => p.stops.map((s) => s.location_id)),
  ]);
  const location_ids = withAncestors(baseLocationIds, await repos.listAllLocations(tenant_id));

  return { tenant_id, dc_user_ids: dcIds, location_ids };
}
