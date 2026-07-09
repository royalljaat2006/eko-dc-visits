import type {
  BeatPlan,
  BindingState,
  Device,
  GeoAssignment,
  LocationNode,
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
  findUserByPhone(phone: string): Promise<User | null>;

  // devices (admin records; binding_state transitions allowed)
  insertDevice(d: Device): Promise<void>;
  getDeviceById(tenantId: TenantId, id: string): Promise<Device | null>;
  setDeviceBindingState(tenantId: TenantId, deviceId: string, state: BindingState): Promise<void>;
  markUserDevicesReplaced(tenantId: TenantId, userId: string): Promise<void>;

  // sessions
  insertRefreshToken(t: RefreshToken): Promise<void>;

  // locations (server→client master data, C3 §6)
  insertLocation(l: LocationNode): Promise<void>;
  getLocationById(tenantId: TenantId, id: string): Promise<LocationNode | null>;
  listAllLocations(tenantId: TenantId): Promise<LocationNode[]>;
  listLocationsUpdatedSince(scope: Scope, updatedSince: string | null, limit: number): Promise<LocationPage>;

  // beat plans (server→client, one-directional)
  insertBeatPlan(p: BeatPlan): Promise<void>;
  listBeatPlansForDcs(tenantId: TenantId, dcUserIds: "ALL" | ReadonlySet<string>): Promise<BeatPlan[]>;
  listBeatPlansFromDate(scope: Scope, fromDate: string): Promise<BeatPlan[]>;

  // geo assignments (C6: AM scope = assigned-dcs, time-bounded)
  insertGeoAssignment(a: GeoAssignment): Promise<void>;
  listAssignedDcIds(tenantId: TenantId, amUserId: string, asOfDate: string): Promise<string[]>;

  // evidence — append-only (no update methods, ever)
  insertCheckinEventIfAbsent(e: StoredCheckInEvent): Promise<void>;
  countCheckinEvents(tenantId: TenantId): Promise<number>;
  insertVisitIfAbsent(v: Visit): Promise<void>;
  /** Scoped read: filtering happens HERE, in the query layer — never post-filtered in handlers. */
  listVisitsByIstDate(scope: Scope, istDate: string): Promise<VisitView[]>;

  // sync op dedupe (C3 §3: duplicate replays byte-identical dispositions)
  getOpDisposition(tenantId: TenantId, opId: string): Promise<OpDisposition | null>;
  putOpDispositionIfAbsent(tenantId: TenantId, opId: string, d: OpDisposition): Promise<void>;

  // quarantine — append-only raw store (ADR-0003: never discard)
  insertQuarantineIfAbsent(q: QuarantinedOp): Promise<void>;
  countQuarantined(tenantId: TenantId): Promise<number>;
}
