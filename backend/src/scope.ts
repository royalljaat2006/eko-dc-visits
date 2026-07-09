/**
 * THE authorization choke point (ADR-0006, C6 matrix.yaml).
 *
 * Given a principal, resolves the visible DC-user-id set and location-id set.
 * Every scoped read query in the repository layer takes the resulting Scope —
 * no endpoint writes its own ad-hoc WHERE logic.
 *
 *   DC              → self (C6 roles.DC.scope: self); territory = own beat-plan
 *                     locations + their ancestors
 *   AM              → assigned DCs via time-bounded GeoAssignments
 *                     (C6 roles.AM.scope: assigned-dcs)
 *   CORPORATE_ADMIN → tenant root (C6 roles.CORPORATE_ADMIN.scope: tenant-root)
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

  if (principal.role === "CORPORATE_ADMIN") {
    return { tenant_id, dc_user_ids: "ALL", location_ids: "ALL" };
  }

  let dcIds: Set<string>;
  if (principal.role === "DC") {
    dcIds = new Set([principal.user_id]);
  } else if (principal.role === "AM") {
    dcIds = new Set(await repos.listAssignedDcIds(tenant_id, principal.user_id, istDateOf(now)));
  } else {
    // RM / STATE_HEAD / SBI_OFFICIAL are out of M0 scope (C6 matrix lists DC, AM,
    // CORPORATE_ADMIN only) — resolve to an empty scope rather than guessing.
    dcIds = new Set();
  }

  const plans = await repos.listBeatPlansForDcs(tenant_id, dcIds);
  const stopLocationIds = new Set<string>(plans.flatMap((p) => p.stops.map((s) => s.location_id)));
  const location_ids = withAncestors(stopLocationIds, await repos.listAllLocations(tenant_id));

  return { tenant_id, dc_user_ids: dcIds, location_ids };
}
