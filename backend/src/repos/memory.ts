import type {
  AttendanceDay,
  Bank,
  BeatPlan,
  BindingState,
  Circle,
  CircleMembership,
  CspAssignment,
  Device,
  LocationNode,
  StoredAttendanceEvent,
  OpDisposition,
  QuarantinedOp,
  RefreshToken,
  StoredCheckInEvent,
  TenantId,
  User,
  Visit,
  VisitView,
} from "../domain/types.js";
import type { LocationPage, Repos, Scope } from "./types.js";

function inDcScope(scope: Scope, dcUserId: string): boolean {
  return scope.dc_user_ids === "ALL" || scope.dc_user_ids.has(dcUserId);
}
function inLocationScope(scope: Scope, locationId: string): boolean {
  return scope.location_ids === "ALL" || scope.location_ids.has(locationId);
}

/**
 * In-memory Repos implementation (ADR-0009): used by tests and `npm run dev`
 * without DATABASE_URL. Evidence maps are only ever written by *IfAbsent
 * inserts — append-only by construction.
 */
export class MemoryRepos implements Repos {
  private users = new Map<string, User>(); // key tenant:id
  private devices = new Map<string, Device>();
  private refreshTokens = new Map<string, RefreshToken>();
  private locations = new Map<string, LocationNode>();
  private beatPlans = new Map<string, BeatPlan>();
  private banks = new Map<string, Bank>();
  private circles = new Map<string, Circle>();
  private circleMemberships = new Map<string, CircleMembership>();
  private cspAssignments = new Map<string, CspAssignment>();
  private checkinEvents = new Map<string, StoredCheckInEvent>();
  private attendanceEvents = new Map<string, StoredAttendanceEvent>();
  private attendanceDays = new Map<string, AttendanceDay>(); // key tenant:dc:istDate
  private visits = new Map<string, Visit>();
  private opDispositions = new Map<string, OpDisposition>();
  private quarantine = new Map<string, QuarantinedOp>();

  private key(tenantId: TenantId, id: string): string {
    return `${tenantId}:${id}`;
  }

  // --- users
  async insertUser(u: User): Promise<void> {
    this.users.set(this.key(u.tenant_id, u.id), u);
  }
  async getUserById(tenantId: TenantId, id: string): Promise<User | null> {
    return this.users.get(this.key(tenantId, id)) ?? null;
  }
  async findUserByPhone(phone: string): Promise<User | null> {
    for (const u of this.users.values()) if (u.phone === phone) return u;
    return null;
  }
  async listDcUsers(tenantId: TenantId, dcUserIds: "ALL" | ReadonlySet<string>): Promise<User[]> {
    return [...this.users.values()]
      .filter((u) => u.tenant_id === tenantId && u.role === "DC" && u.status === "ACTIVE")
      .filter((u) => dcUserIds === "ALL" || dcUserIds.has(u.id))
      .sort((a, b) => a.name.localeCompare(b.name));
  }

  // --- devices
  async insertDevice(d: Device): Promise<void> {
    this.devices.set(this.key(d.tenant_id, d.id), d);
  }
  async getDeviceById(tenantId: TenantId, id: string): Promise<Device | null> {
    return this.devices.get(this.key(tenantId, id)) ?? null;
  }
  async setDeviceBindingState(tenantId: TenantId, deviceId: string, state: BindingState): Promise<void> {
    const d = this.devices.get(this.key(tenantId, deviceId));
    if (d) this.devices.set(this.key(tenantId, deviceId), { ...d, binding_state: state });
  }
  async markUserDevicesReplaced(tenantId: TenantId, userId: string): Promise<void> {
    for (const [k, d] of this.devices) {
      if (d.tenant_id === tenantId && d.user_id === userId && d.binding_state === "BOUND") {
        this.devices.set(k, { ...d, binding_state: "REPLACED" });
      }
    }
  }

  // --- sessions
  async insertRefreshToken(t: RefreshToken): Promise<void> {
    this.refreshTokens.set(t.token, t);
  }

  // --- locations
  async insertLocation(l: LocationNode): Promise<void> {
    this.locations.set(this.key(l.tenant_id, l.id), l);
  }
  async getLocationById(tenantId: TenantId, id: string): Promise<LocationNode | null> {
    return this.locations.get(this.key(tenantId, id)) ?? null;
  }
  async listAllLocations(tenantId: TenantId): Promise<LocationNode[]> {
    return [...this.locations.values()].filter((l) => l.tenant_id === tenantId);
  }
  async listLocationsUpdatedSince(scope: Scope, updatedSince: string | null, limit: number): Promise<LocationPage> {
    const since = updatedSince ? new Date(updatedSince).getTime() : null;
    const all = [...this.locations.values()]
      .filter((l) => l.tenant_id === scope.tenant_id)
      .filter((l) => inLocationScope(scope, l.id))
      .filter((l) => since === null || new Date(l.updated_at).getTime() > since)
      .sort((a, b) => a.updated_at.localeCompare(b.updated_at) || a.id.localeCompare(b.id));
    const items = all.slice(0, limit);
    const last = items[items.length - 1];
    const next_cursor = all.length > items.length && last ? last.updated_at : null;
    return { items, next_cursor };
  }

  // --- beat plans
  async insertBeatPlan(p: BeatPlan): Promise<void> {
    this.beatPlans.set(this.key(p.tenant_id, p.id), p);
  }
  async listBeatPlansForDcs(tenantId: TenantId, dcUserIds: "ALL" | ReadonlySet<string>): Promise<BeatPlan[]> {
    return [...this.beatPlans.values()].filter(
      (p) => p.tenant_id === tenantId && (dcUserIds === "ALL" || dcUserIds.has(p.dc_user_id)),
    );
  }
  async listBeatPlansFromDate(scope: Scope, fromDate: string): Promise<BeatPlan[]> {
    return [...this.beatPlans.values()]
      .filter((p) => p.tenant_id === scope.tenant_id)
      .filter((p) => inDcScope(scope, p.dc_user_id))
      .filter((p) => p.plan_date >= fromDate)
      .sort((a, b) => a.plan_date.localeCompare(b.plan_date));
  }

  // --- banks & circles (design 0001)
  async insertBank(b: Bank): Promise<void> {
    this.banks.set(this.key(b.tenant_id, b.id), b);
  }
  async insertCircle(c: Circle): Promise<void> {
    this.circles.set(this.key(c.tenant_id, c.id), c);
  }
  async insertCircleMembership(m: CircleMembership): Promise<void> {
    this.circleMemberships.set(this.key(m.tenant_id, m.id), m);
  }
  private activeAsOf(valid_from: string, valid_to: string | null, asOfDate: string): boolean {
    return valid_from <= asOfDate && (valid_to === null || valid_to >= asOfDate);
  }
  async listBanks(tenantId: TenantId): Promise<Bank[]> {
    return [...this.banks.values()].filter((b) => b.tenant_id === tenantId).sort((a, b) => a.name.localeCompare(b.name));
  }
  async listCircles(tenantId: TenantId): Promise<Circle[]> {
    return [...this.circles.values()].filter((c) => c.tenant_id === tenantId).sort((a, b) => a.name.localeCompare(b.name));
  }
  async listActiveCircleMemberships(tenantId: TenantId, asOfDate: string): Promise<CircleMembership[]> {
    return [...this.circleMemberships.values()]
      .filter((m) => m.tenant_id === tenantId)
      .filter((m) => this.activeAsOf(m.valid_from, m.valid_to, asOfDate));
  }
  async listCircleDcIds(tenantId: TenantId, headUserId: string, asOfDate: string): Promise<string[]> {
    const all = [...this.circleMemberships.values()].filter((m) => m.tenant_id === tenantId);
    const headedCircles = new Set(
      all
        .filter((m) => m.user_id === headUserId && m.role_in_circle === "CIRCLE_HEAD")
        .filter((m) => this.activeAsOf(m.valid_from, m.valid_to, asOfDate))
        .map((m) => m.circle_id),
    );
    return all
      .filter((m) => headedCircles.has(m.circle_id) && m.role_in_circle === "DC")
      .filter((m) => this.activeAsOf(m.valid_from, m.valid_to, asOfDate))
      .map((m) => m.user_id);
  }

  async getActiveDcCircleId(tenantId: TenantId, dcUserId: string, asOfDate: string): Promise<string | null> {
    for (const m of this.circleMemberships.values()) {
      if (m.tenant_id === tenantId && m.user_id === dcUserId && m.role_in_circle === "DC" && this.activeAsOf(m.valid_from, m.valid_to, asOfDate)) {
        return m.circle_id;
      }
    }
    return null;
  }

  // --- CSP assignments (design 0001 §3)
  async insertCspAssignment(a: CspAssignment): Promise<void> {
    this.cspAssignments.set(this.key(a.tenant_id, a.id), a);
  }
  async listActiveCspAssignments(
    tenantId: TenantId,
    dcUserIds: "ALL" | ReadonlySet<string>,
    asOfDate: string,
  ): Promise<CspAssignment[]> {
    return [...this.cspAssignments.values()]
      .filter((a) => a.tenant_id === tenantId)
      .filter((a) => dcUserIds === "ALL" || dcUserIds.has(a.dc_user_id))
      .filter((a) => this.activeAsOf(a.valid_from, a.valid_to, asOfDate))
      .sort((a, b) => a.id.localeCompare(b.id));
  }

  // --- attendance (v0.3.0)
  async insertAttendanceEventIfAbsent(e: StoredAttendanceEvent): Promise<void> {
    const k = this.key(e.tenant_id, e.id);
    if (!this.attendanceEvents.has(k)) this.attendanceEvents.set(k, e);
  }
  async mergeAttendanceDay(tenantId: TenantId, dcUserId: string, istDate: string, kind: "START" | "END", occurredAt: string): Promise<void> {
    const k = `${tenantId}:${dcUserId}:${istDate}`;
    const cur = this.attendanceDays.get(k) ?? { tenant_id: tenantId, dc_user_id: dcUserId, ist_date: istDate, started_at: null, ended_at: null };
    // Commutative: earliest START / latest END — converges under any op order.
    const next: AttendanceDay = {
      ...cur,
      started_at: kind === "START" ? (cur.started_at === null || occurredAt < cur.started_at ? occurredAt : cur.started_at) : cur.started_at,
      ended_at: kind === "END" ? (cur.ended_at === null || occurredAt > cur.ended_at ? occurredAt : cur.ended_at) : cur.ended_at,
    };
    this.attendanceDays.set(k, next);
  }
  async getAttendanceDay(tenantId: TenantId, dcUserId: string, istDate: string): Promise<AttendanceDay | null> {
    return this.attendanceDays.get(`${tenantId}:${dcUserId}:${istDate}`) ?? null;
  }

  // --- CSP assignment mutations (design 0001 §6)
  async endActiveCspAssignment(tenantId: TenantId, cspLocationId: string, validTo: string): Promise<string | null> {
    for (const [k, a] of this.cspAssignments) {
      if (a.tenant_id === tenantId && a.csp_location_id === cspLocationId && a.valid_to === null) {
        this.cspAssignments.set(k, { ...a, valid_to: validTo, updated_at: new Date().toISOString() });
        return a.id;
      }
    }
    return null;
  }

  // --- evidence (append-only)
  async insertCheckinEventIfAbsent(e: StoredCheckInEvent): Promise<void> {
    const k = this.key(e.tenant_id, e.id);
    if (!this.checkinEvents.has(k)) this.checkinEvents.set(k, e);
  }
  async countCheckinEvents(tenantId: TenantId): Promise<number> {
    return [...this.checkinEvents.values()].filter((e) => e.tenant_id === tenantId).length;
  }
  async insertVisitIfAbsent(v: Visit): Promise<void> {
    const k = this.key(v.tenant_id, v.id);
    if (!this.visits.has(k)) this.visits.set(k, v);
  }
  async listVisitsByIstDate(scope: Scope, istDate: string): Promise<VisitView[]> {
    const IST_OFFSET_MS = 5.5 * 3600 * 1000;
    const istDateOf = (iso: string) => new Date(new Date(iso).getTime() + IST_OFFSET_MS).toISOString().slice(0, 10);
    return [...this.visits.values()]
      .filter((v) => v.tenant_id === scope.tenant_id)
      .filter((v) => inDcScope(scope, v.dc_user_id)) // scoping enforced in the query layer
      .filter((v) => istDateOf(v.occurred_at) === istDate)
      .sort((a, b) => a.occurred_at.localeCompare(b.occurred_at) || a.id.localeCompare(b.id))
      .map((v): VisitView => {
        const dc = this.users.get(this.key(v.tenant_id, v.dc_user_id));
        const loc = this.locations.get(this.key(v.tenant_id, v.location_id));
        return {
          id: v.id,
          dc_user_id: v.dc_user_id,
          dc_name: dc?.name ?? "(unknown)",
          location_id: v.location_id,
          location_name: loc?.name ?? "(unknown)",
          location_code: loc?.code ?? "(unknown)",
          planned: v.planned,
          checkin: { occurred_at: v.occurred_at, server_received_at: v.server_received_at, fix: v.fix },
          distance_from_master_m: v.distance_from_master_m,
          geofence_result: v.geofence_result,
          out_of_radius_reason: v.out_of_radius_reason,
          sync_state: v.sync_state,
        };
      });
  }

  // --- sync op dedupe
  async getOpDisposition(tenantId: TenantId, opId: string): Promise<OpDisposition | null> {
    return this.opDispositions.get(this.key(tenantId, opId)) ?? null;
  }
  async putOpDispositionIfAbsent(tenantId: TenantId, opId: string, d: OpDisposition): Promise<void> {
    const k = this.key(tenantId, opId);
    if (!this.opDispositions.has(k)) this.opDispositions.set(k, d);
  }

  // --- quarantine
  async insertQuarantineIfAbsent(q: QuarantinedOp): Promise<void> {
    const k = this.key(q.tenant_id, q.op_id);
    if (!this.quarantine.has(k)) this.quarantine.set(k, q);
  }
  async countQuarantined(tenantId: TenantId): Promise<number> {
    return [...this.quarantine.values()].filter((q) => q.tenant_id === tenantId).length;
  }

  /**
   * Canonical deterministic dump of evidence-affecting state — used by the
   * C3 §3 convergence property test to assert deep equality across
   * permutations + duplications.
   */
  dumpConvergentState(): unknown {
    const sortEntries = <V>(m: Map<string, V>) => [...m.entries()].sort(([a], [b]) => a.localeCompare(b));
    return {
      checkinEvents: sortEntries(this.checkinEvents),
      visits: sortEntries(this.visits),
      opDispositions: sortEntries(this.opDispositions),
      quarantine: sortEntries(this.quarantine),
    };
  }
}
