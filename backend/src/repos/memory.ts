import type {
  AttendanceDay,
  Bank,
  BeatPlan,
  BindingState,
  Circle,
  CircleMembership,
  CspAssignment,
  CspChangeRequest,
  DcLiveLocation,
  Device,
  LocationNode,
  StoredAttendanceEvent,
  StoredCheckoutEvent,
  StoredTrackChunk,
  StoredVisitPhoto,
  TrackPoint,
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
  private trackChunks = new Map<string, StoredTrackChunk>();
  private liveLocations = new Map<string, DcLiveLocation>(); // key tenant:dc
  private visitPhotos = new Map<string, StoredVisitPhoto>();
  private checkoutEvents = new Map<string, StoredCheckoutEvent>();
  private cspChangeRequests = new Map<string, CspChangeRequest>();
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
  async listUsersByIds(tenantId: TenantId, ids: readonly string[]): Promise<User[]> {
    const want = new Set(ids);
    return [...this.users.values()].filter((u) => u.tenant_id === tenantId && want.has(u.id));
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
  async getRefreshToken(token: string): Promise<RefreshToken | null> {
    return this.refreshTokens.get(token) ?? null;
  }
  async deleteRefreshToken(token: string): Promise<void> {
    this.refreshTokens.delete(token);
  }

  // --- locations
  async insertLocation(l: LocationNode): Promise<void> {
    this.locations.set(this.key(l.tenant_id, l.id), l);
  }
  async getLocationById(tenantId: TenantId, id: string): Promise<LocationNode | null> {
    return this.locations.get(this.key(tenantId, id)) ?? null;
  }
  async listLocationsByIds(tenantId: TenantId, ids: readonly string[]): Promise<LocationNode[]> {
    const want = new Set(ids);
    return [...this.locations.values()].filter((l) => l.tenant_id === tenantId && want.has(l.id));
  }
  async findLocationByCode(tenantId: TenantId, code: string): Promise<LocationNode | null> {
    for (const l of this.locations.values()) {
      if (l.tenant_id === tenantId && l.code === code) return l;
    }
    return null;
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
  async getAttendanceDaysForDcDates(
    tenantId: TenantId,
    dcIds: readonly string[],
    istDates: readonly string[],
  ): Promise<Map<string, AttendanceDay>> {
    const wantDc = new Set(dcIds);
    const wantDate = new Set(istDates);
    const out = new Map<string, AttendanceDay>();
    for (const d of this.attendanceDays.values()) {
      if (d.tenant_id === tenantId && wantDc.has(d.dc_user_id) && wantDate.has(d.ist_date)) {
        out.set(`${d.dc_user_id}|${d.ist_date}`, d);
      }
    }
    return out;
  }

  // --- CSP change requests (v0.8.0 — admin records)
  async insertCspChangeRequest(r: CspChangeRequest): Promise<void> {
    this.cspChangeRequests.set(this.key(r.tenant_id, r.id), r);
  }
  async getCspChangeRequest(tenantId: TenantId, id: string): Promise<CspChangeRequest | null> {
    return this.cspChangeRequests.get(this.key(tenantId, id)) ?? null;
  }
  async listCspChangeRequests(
    tenantId: TenantId,
    requesterIds: "ALL" | ReadonlySet<string>,
    status?: CspChangeRequest["status"],
  ): Promise<CspChangeRequest[]> {
    return [...this.cspChangeRequests.values()]
      .filter((r) => r.tenant_id === tenantId)
      .filter((r) => requesterIds === "ALL" || requesterIds.has(r.requested_by_user_id))
      .filter((r) => status === undefined || r.status === status)
      .sort((a, b) => b.created_at.localeCompare(a.created_at));
  }
  async decideCspChangeRequest(tenantId: TenantId, decided: CspChangeRequest): Promise<void> {
    this.cspChangeRequests.set(this.key(tenantId, decided.id), decided);
  }
  async updateLocationFields(
    tenantId: TenantId,
    locationId: string,
    patch: { name?: string; address?: string; lat?: number; lng?: number; profile?: Record<string, string>; updated_at: string },
  ): Promise<void> {
    const k = this.key(tenantId, locationId);
    const cur = this.locations.get(k);
    if (!cur) return;
    const coordsChanged = patch.lat !== undefined || patch.lng !== undefined;
    this.locations.set(k, {
      ...cur,
      name: patch.name ?? cur.name,
      address: patch.address ?? cur.address,
      coordinates: coordsChanged
        ? { ...cur.coordinates, lat: patch.lat ?? cur.coordinates.lat, lng: patch.lng ?? cur.coordinates.lng }
        : cur.coordinates,
      coordinate_confidence: coordsChanged ? "FIELD_CAPTURED" : cur.coordinate_confidence,
      csp_profile: patch.profile ? { ...cur.csp_profile, ...patch.profile } : cur.csp_profile,
      updated_at: patch.updated_at,
    });
  }
  async updateUserHomeLocation(tenantId: TenantId, userId: string, homeLat: number, homeLng: number): Promise<void> {
    const k = this.key(tenantId, userId);
    const cur = this.users.get(k);
    if (cur) this.users.set(k, { ...cur, home_lat: homeLat, home_lng: homeLng });
  }
  async lastVisitDatesForDc(tenantId: TenantId, dcUserId: string): Promise<Map<string, string>> {
    const IST_OFFSET_MS = 5.5 * 3600 * 1000;
    const istDateOf = (iso: string) => new Date(new Date(iso).getTime() + IST_OFFSET_MS).toISOString().slice(0, 10);
    const out = new Map<string, string>();
    for (const v of this.visits.values()) {
      if (v.tenant_id !== tenantId || v.dc_user_id !== dcUserId) continue;
      const d = istDateOf(v.occurred_at);
      const cur = out.get(v.location_id);
      if (!cur || d > cur) out.set(v.location_id, d);
    }
    return out;
  }

  // --- GPS track (v0.7.0; append-only; km derived at read time)
  async insertTrackChunkIfAbsent(c: StoredTrackChunk): Promise<void> {
    const k = this.key(c.tenant_id, c.id);
    if (!this.trackChunks.has(k)) this.trackChunks.set(k, c);
  }
  async listTrackPointsForDcDate(tenantId: TenantId, dcUserId: string, istDate: string): Promise<TrackPoint[]> {
    return (await this.listTrackPointsForDcsDate(tenantId, [dcUserId], istDate)).get(dcUserId) ?? [];
  }
  async listTrackPointsForDcsDate(
    tenantId: TenantId,
    dcIds: readonly string[],
    istDate: string,
  ): Promise<Map<string, TrackPoint[]>> {
    const IST_OFFSET_MS = 5.5 * 3600 * 1000;
    const istDateOf = (iso: string) => new Date(new Date(iso).getTime() + IST_OFFSET_MS).toISOString().slice(0, 10);
    const want = new Set(dcIds);
    const out = new Map<string, TrackPoint[]>();
    for (const c of this.trackChunks.values()) {
      if (c.tenant_id !== tenantId || !want.has(c.dc_user_id)) continue;
      const bucket = out.get(c.dc_user_id) ?? [];
      for (const p of c.points) if (istDateOf(p.t) === istDate) bucket.push(p);
      out.set(c.dc_user_id, bucket);
    }
    for (const list of out.values()) list.sort((a, b) => a.t.localeCompare(b.t));
    return out;
  }

  async upsertDcLiveLocationIfNewer(loc: DcLiveLocation): Promise<void> {
    const k = this.key(loc.tenant_id, loc.dc_user_id);
    const cur = this.liveLocations.get(k);
    if (!cur || loc.captured_at > cur.captured_at) this.liveLocations.set(k, loc);
  }
  async listLiveLocationsForDcs(tenantId: TenantId, dcIds: "ALL" | ReadonlySet<string>): Promise<Map<string, DcLiveLocation>> {
    const out = new Map<string, DcLiveLocation>();
    for (const loc of this.liveLocations.values()) {
      if (loc.tenant_id !== tenantId) continue;
      if (dcIds !== "ALL" && !dcIds.has(loc.dc_user_id)) continue;
      out.set(loc.dc_user_id, loc);
    }
    return out;
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
  async insertVisitPhotoIfAbsent(p: StoredVisitPhoto): Promise<void> {
    const k = this.key(p.tenant_id, p.id);
    if (!this.visitPhotos.has(k)) this.visitPhotos.set(k, p);
  }
  async countPhotosForVisits(tenantId: TenantId, visitIds: string[]): Promise<Map<string, number>> {
    const want = new Set(visitIds);
    const out = new Map<string, number>();
    for (const p of this.visitPhotos.values()) {
      if (p.tenant_id !== tenantId || !want.has(p.visit_id)) continue;
      out.set(p.visit_id, (out.get(p.visit_id) ?? 0) + 1);
    }
    return out;
  }

  async insertCheckoutEventIfAbsent(e: StoredCheckoutEvent): Promise<void> {
    const k = this.key(e.tenant_id, e.id);
    if (!this.checkoutEvents.has(k)) this.checkoutEvents.set(k, e);
  }
  async checkoutTimesForVisits(tenantId: TenantId, visitIds: string[]): Promise<Map<string, string>> {
    const want = new Set(visitIds);
    // Earliest wall-time wins, id as a deterministic tie-break — the result
    // never depends on iteration/insertion order (C3 §3 convergence).
    const best = new Map<string, { wall: string; id: string }>();
    for (const e of this.checkoutEvents.values()) {
      if (e.tenant_id !== tenantId || !want.has(e.visit_id)) continue;
      const wall = e.timestamps.device_wall_time;
      const cur = best.get(e.visit_id);
      if (!cur || wall < cur.wall || (wall === cur.wall && e.id < cur.id)) {
        best.set(e.visit_id, { wall, id: e.id });
      }
    }
    return new Map([...best].map(([visitId, v]) => [visitId, v.wall]));
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
