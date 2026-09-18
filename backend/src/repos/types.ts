import type {
  AttendanceDay,
  Bank,
  BeatPlan,
  BindingState,
  Circle,
  CircleMembership,
  CspAssignment,
  CspChangeRequest,
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

/**
 * Resolved visibility for a principal (ADR-0006: ONE data-access choke point).
 * Produced only by src/scope.ts#resolveScope; every scoped read query takes it.
 * "ALL" = tenant root (CORPORATE_ADMIN).
 */
export interface Scope {
  tenant_id: TenantId;
  dc_user_ids: "ALL" | ReadonlySet<string>;
  location_ids: "ALL" | ReadonlySet<string>;
}

export interface LocationPage {
  items: LocationNode[];
  next_cursor: string | null;
}

/**
 * Storage abstraction (ADR-0009): in-memory impl for tests/dev, Postgres impl
 * for prod. Evidence (check-in events, visits, quarantine) is APPEND-ONLY:
 * there is deliberately no update method for it (ADR-0003, CONTRIBUTING #3).
 * All insert*IfAbsent methods are idempotent first-writer-wins, which is what
 * makes the C3 §3 convergence invariant hold under permutation + duplication.
 */
export interface Repos {
  // users (admin records)
  insertUser(u: User): Promise<void>;
  getUserById(tenantId: TenantId, id: string): Promise<User | null>;
  /** Batch lookup — one round-trip instead of N (dashboard joins). */
  listUsersByIds(tenantId: TenantId, ids: readonly string[]): Promise<User[]>;
  findUserByPhone(phone: string): Promise<User | null>;

  // devices (admin records; binding_state transitions allowed)
  insertDevice(d: Device): Promise<void>;
  getDeviceById(tenantId: TenantId, id: string): Promise<Device | null>;
  setDeviceBindingState(tenantId: TenantId, deviceId: string, state: BindingState): Promise<void>;
  markUserDevicesReplaced(tenantId: TenantId, userId: string): Promise<void>;

  // sessions
  insertRefreshToken(t: RefreshToken): Promise<void>;
  getRefreshToken(token: string): Promise<RefreshToken | null>;
  deleteRefreshToken(token: string): Promise<void>;

  // locations (server→client master data, C3 §6)
  insertLocation(l: LocationNode): Promise<void>;
  getLocationById(tenantId: TenantId, id: string): Promise<LocationNode | null>;
  /** Batch lookup — one round-trip instead of N (CSP-details / dashboards). */
  listLocationsByIds(tenantId: TenantId, ids: readonly string[]): Promise<LocationNode[]>;
  /** Lookup by external code (bulk import rows reference CSPs by code, not uuid). */
  findLocationByCode(tenantId: TenantId, code: string): Promise<LocationNode | null>;
  listAllLocations(tenantId: TenantId): Promise<LocationNode[]>;
  listLocationsUpdatedSince(scope: Scope, updatedSince: string | null, limit: number): Promise<LocationPage>;

  // beat plans (server→client, one-directional)
  insertBeatPlan(p: BeatPlan): Promise<void>;
  listBeatPlansForDcs(tenantId: TenantId, dcUserIds: "ALL" | ReadonlySet<string>): Promise<BeatPlan[]>;
  listBeatPlansFromDate(scope: Scope, fromDate: string): Promise<BeatPlan[]>;

  // banks & circles (design 0001 — admin records, server-authoritative)
  insertBank(b: Bank): Promise<void>;
  insertCircle(c: Circle): Promise<void>;
  insertCircleMembership(m: CircleMembership): Promise<void>;
  listBanks(tenantId: TenantId): Promise<Bank[]>;
  listCircles(tenantId: TenantId): Promise<Circle[]>;
  /** All memberships active as of date (admin overview rollups). */
  listActiveCircleMemberships(tenantId: TenantId, asOfDate: string): Promise<CircleMembership[]>;
  /** DCs in circles the given user heads, as of date (C6 CIRCLE_HEAD scope). */
  listCircleDcIds(tenantId: TenantId, headUserId: string, asOfDate: string): Promise<string[]>;
  /** The circle a DC actively belongs to, as of date (design 0001: exactly one). */
  getActiveDcCircleId(tenantId: TenantId, dcUserId: string, asOfDate: string): Promise<string | null>;

  // CSP assignments (design 0001 §3; effective-dated — transfers end+start, never delete)
  insertCspAssignment(a: CspAssignment): Promise<void>;
  /** Active assignments for the given DC set, as of date. Feeds DC scope + the dwell matcher list. */
  listActiveCspAssignments(
    tenantId: TenantId,
    dcUserIds: "ALL" | ReadonlySet<string>,
    asOfDate: string,
  ): Promise<CspAssignment[]>;

  // users (read helpers for boards)
  /** DC-role users within a dc scope; feeds the attendance board's NOT_STARTED rows. */
  listDcUsers(tenantId: TenantId, dcUserIds: "ALL" | ReadonlySet<string>): Promise<User[]>;

  // attendance (evidence append-only + commutative read model, v0.3.0)
  insertAttendanceEventIfAbsent(e: StoredAttendanceEvent): Promise<void>;
  /**
   * Commutative merge into AttendanceDay: earliest START / latest END per
   * (dc, IST date). Order-independent by construction — this is what keeps the
   * C3 §3 convergence invariant holding with attendance ops in the mix.
   */
  mergeAttendanceDay(tenantId: TenantId, dcUserId: string, istDate: string, kind: "START" | "END", occurredAt: string): Promise<void>;
  getAttendanceDay(tenantId: TenantId, dcUserId: string, istDate: string): Promise<AttendanceDay | null>;
  /** Batch: one row per (dc, date) that has any events. Keyed `${dcUserId}|${istDate}`. */
  getAttendanceDaysForDcDates(tenantId: TenantId, dcIds: readonly string[], istDates: readonly string[]): Promise<Map<string, AttendanceDay>>;

  // CSP assignment mutations (design 0001 §6 — end-old + start-new, never edit)
  /** Ends the active assignment for a CSP (sets valid_to). Returns the ended assignment's id, or null if none was active. */
  endActiveCspAssignment(tenantId: TenantId, cspLocationId: string, validTo: string): Promise<string | null>;

  // CSP change requests (v0.8.0 — admin records: decision update is allowed)
  insertCspChangeRequest(r: CspChangeRequest): Promise<void>;
  getCspChangeRequest(tenantId: TenantId, id: string): Promise<CspChangeRequest | null>;
  listCspChangeRequests(
    tenantId: TenantId,
    requesterIds: "ALL" | ReadonlySet<string>,
    status?: CspChangeRequest["status"],
  ): Promise<CspChangeRequest[]>;
  decideCspChangeRequest(tenantId: TenantId, decided: CspChangeRequest): Promise<void>;
  /** Applies an APPROVED request to the location master (admin data — updatable). */
  updateLocationFields(
    tenantId: TenantId,
    locationId: string,
    patch: { name?: string; address?: string; lat?: number; lng?: number; profile?: Record<string, string>; updated_at: string },
  ): Promise<void>;
  /** Sets a user's reference home location (spec §3 — "Excel sheet for Lat Long"). Admin data. */
  updateUserHomeLocation(tenantId: TenantId, userId: string, homeLat: number, homeLng: number): Promise<void>;
  /** Per-CSP most recent visit IST date for a DC (spec §3: last-visit date on each card). */
  lastVisitDatesForDc(tenantId: TenantId, dcUserId: string): Promise<Map<string, string>>;

  // GPS track (v0.7.0; evidence append-only; km derived at READ time so
  // ingest order never matters — C3 §3 convergence)
  insertTrackChunkIfAbsent(c: StoredTrackChunk): Promise<void>;
  /** All points for a DC whose fix time falls on the IST date, sorted by t. */
  listTrackPointsForDcDate(tenantId: TenantId, dcUserId: string, istDate: string): Promise<TrackPoint[]>;
  /** Batch: points per DC for one IST date, each list sorted by t. Keyed by dcUserId. */
  listTrackPointsForDcsDate(tenantId: TenantId, dcIds: readonly string[], istDate: string): Promise<Map<string, TrackPoint[]>>;

  // evidence — append-only (no update methods, ever)
  insertCheckinEventIfAbsent(e: StoredCheckInEvent): Promise<void>;
  countCheckinEvents(tenantId: TenantId): Promise<number>;
  insertVisitIfAbsent(v: Visit): Promise<void>;
  /** Scoped read: filtering happens HERE, in the query layer — never post-filtered in handlers. */
  listVisitsByIstDate(scope: Scope, istDate: string): Promise<VisitView[]>;

  // visit photos (v0.9.0; append-only; T2). Linked by visit_id, never ordered.
  insertVisitPhotoIfAbsent(p: StoredVisitPhoto): Promise<void>;
  /** photo count per visit id, for the dashboard read model (computed at READ time like km). */
  countPhotosForVisits(tenantId: TenantId, visitIds: string[]): Promise<Map<string, number>>;

  // visit checkout (v0.10.0; append-only; T1). Linked by visit_id, never ordered.
  insertCheckoutEventIfAbsent(e: StoredCheckoutEvent): Promise<void>;
  /** Earliest checkout device_wall_time per visit id — deterministic regardless of arrival order. */
  checkoutTimesForVisits(tenantId: TenantId, visitIds: string[]): Promise<Map<string, string>>;

  // sync op dedupe (C3 §3: duplicate replays byte-identical dispositions)
  getOpDisposition(tenantId: TenantId, opId: string): Promise<OpDisposition | null>;
  putOpDispositionIfAbsent(tenantId: TenantId, opId: string, d: OpDisposition): Promise<void>;

  // quarantine — append-only raw store (ADR-0003: never discard)
  insertQuarantineIfAbsent(q: QuarantinedOp): Promise<void>;
  countQuarantined(tenantId: TenantId): Promise<number>;
}
