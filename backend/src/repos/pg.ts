/**
 * Postgres Repos implementation (ADR-0002/0009): plain SQL over `pg`, no ORM.
 * Selected when DATABASE_URL is set. Cannot be exercised in the M0 dev
 * environment (no local Postgres) — kept thin and literal; the in-memory impl
 * is the behavioral reference and both share the Repos contract tests once a
 * database is available (M1).
 *
 * Append-only evidence (ADR-0003): this file contains NO UPDATE statement for
 * checkin_events, visits, sync_op_dispositions or quarantined_ops — inserts
 * use ON CONFLICT DO NOTHING (first-writer-wins, convergence-safe).
 */
import pg from "pg";
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
import type { LocationPage, Repos, Scope } from "./types.js";
import { istDateOf } from "../geo.js";

type Row = Record<string, unknown>;

function userFromRow(r: Row): User {
  return {
    id: r.id as string,
    tenant_id: r.tenant_id as string,
    name: r.name as string,
    phone: r.phone as string,
    employee_code: (r.employee_code as string | null) ?? undefined,
    role: r.role as User["role"],
    scope_location_id: r.scope_location_id as string | null,
    status: r.status as User["status"],
  };
}

function locationFromRow(r: Row): LocationNode {
  return {
    id: r.id as string,
    tenant_id: r.tenant_id as string,
    type: r.type as LocationNode["type"],
    parent_id: r.parent_id as string | null,
    name: r.name as string,
    code: r.code as string,
    address: (r.address as string | null) ?? undefined,
    state: (r.state as string | null) ?? undefined,
    district: (r.district as string | null) ?? undefined,
    pin_code: (r.pin_code as string | null) ?? undefined,
    coordinates: { lat: r.coord_lat as number, lng: r.coord_lng as number },
    radius_m: r.radius_m as number,
    coordinate_confidence: r.coordinate_confidence as LocationNode["coordinate_confidence"],
    status: r.status as LocationNode["status"],
    updated_at: (r.updated_at as Date).toISOString(),
  };
}

/** Expands a Scope's dc set into (paramSql, values) or null for tenant-root. */
function dcFilter(scope: Scope, startIndex: number): { sql: string; values: string[] } | null {
  if (scope.dc_user_ids === "ALL") return null;
  const ids = [...scope.dc_user_ids];
  return { sql: `dc_user_id = ANY($${startIndex}::uuid[])`, values: [`{${ids.join(",")}}`] as unknown as string[] };
}

export class PgRepos implements Repos {
  private pool: pg.Pool;

  constructor(databaseUrl: string) {
    this.pool = new pg.Pool({ connectionString: databaseUrl });
  }

  async close(): Promise<void> {
    await this.pool.end();
  }

  // --- users
  async insertUser(u: User): Promise<void> {
    await this.pool.query(
      `INSERT INTO users (id, tenant_id, name, phone, employee_code, role, scope_location_id, status)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8) ON CONFLICT (id) DO NOTHING`,
      [u.id, u.tenant_id, u.name, u.phone, u.employee_code ?? null, u.role, u.scope_location_id ?? null, u.status],
    );
  }
  async getUserById(tenantId: TenantId, id: string): Promise<User | null> {
    const { rows } = await this.pool.query(`SELECT * FROM users WHERE tenant_id = $1 AND id = $2`, [tenantId, id]);
    return rows[0] ? userFromRow(rows[0] as Row) : null;
  }
  async findUserByPhone(phone: string): Promise<User | null> {
    const { rows } = await this.pool.query(`SELECT * FROM users WHERE phone = $1 LIMIT 1`, [phone]);
    return rows[0] ? userFromRow(rows[0] as Row) : null;
  }

  // --- devices (admin records; UPDATE allowed)
  async insertDevice(d: Device): Promise<void> {
    await this.pool.query(
      `INSERT INTO devices (id, tenant_id, user_id, hardware, public_key, binding_state)
       VALUES ($1,$2,$3,$4,$5,$6)`,
      [d.id, d.tenant_id, d.user_id, d.hardware ? JSON.stringify(d.hardware) : null, d.public_key ?? null, d.binding_state],
    );
  }
  async getDeviceById(tenantId: TenantId, id: string): Promise<Device | null> {
    const { rows } = await this.pool.query(`SELECT * FROM devices WHERE tenant_id = $1 AND id = $2`, [tenantId, id]);
    const r = rows[0] as Row | undefined;
    if (!r) return null;
    return {
      id: r.id as string,
      tenant_id: r.tenant_id as string,
      user_id: r.user_id as string,
      hardware: (r.hardware as Device["hardware"]) ?? undefined,
      public_key: (r.public_key as string | null) ?? undefined,
      binding_state: r.binding_state as BindingState,
    };
  }
  async setDeviceBindingState(tenantId: TenantId, deviceId: string, state: BindingState): Promise<void> {
    await this.pool.query(`UPDATE devices SET binding_state = $3 WHERE tenant_id = $1 AND id = $2`, [
      tenantId,
      deviceId,
      state,
    ]);
  }
  async markUserDevicesReplaced(tenantId: TenantId, userId: string): Promise<void> {
    await this.pool.query(
      `UPDATE devices SET binding_state = 'REPLACED' WHERE tenant_id = $1 AND user_id = $2 AND binding_state = 'BOUND'`,
      [tenantId, userId],
    );
  }

  // --- sessions
  async insertRefreshToken(t: RefreshToken): Promise<void> {
    await this.pool.query(
      `INSERT INTO refresh_tokens (token, tenant_id, user_id, device_id, expires_at) VALUES ($1,$2,$3,$4,$5)`,
      [t.token, t.tenant_id, t.user_id, t.device_id, t.expires_at],
    );
  }

  // --- locations
  async insertLocation(l: LocationNode): Promise<void> {
    await this.pool.query(
      `INSERT INTO locations (id, tenant_id, type, parent_id, name, code, address, state, district, pin_code,
                              coord_lat, coord_lng, coordinates, radius_m, coordinate_confidence, status, updated_at)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,
               ST_SetSRID(ST_MakePoint($12,$11),4326)::geography,$13,$14,$15,$16)
       ON CONFLICT (id) DO NOTHING`,
      [
        l.id, l.tenant_id, l.type, l.parent_id ?? null, l.name, l.code, l.address ?? null, l.state ?? null,
        l.district ?? null, l.pin_code ?? null, l.coordinates.lat, l.coordinates.lng, l.radius_m,
        l.coordinate_confidence, l.status ?? "ACTIVE", l.updated_at,
      ],
    );
  }
  async getLocationById(tenantId: TenantId, id: string): Promise<LocationNode | null> {
    const { rows } = await this.pool.query(`SELECT * FROM locations WHERE tenant_id = $1 AND id = $2`, [tenantId, id]);
    return rows[0] ? locationFromRow(rows[0] as Row) : null;
  }
  async listAllLocations(tenantId: TenantId): Promise<LocationNode[]> {
    const { rows } = await this.pool.query(`SELECT * FROM locations WHERE tenant_id = $1`, [tenantId]);
    return (rows as Row[]).map(locationFromRow);
  }
  async listLocationsUpdatedSince(scope: Scope, updatedSince: string | null, limit: number): Promise<LocationPage> {
    const values: unknown[] = [scope.tenant_id];
    let sql = `SELECT * FROM locations WHERE tenant_id = $1`;
    if (scope.location_ids !== "ALL") {
      values.push([...scope.location_ids]);
      sql += ` AND id = ANY($${values.length}::uuid[])`;
    }
    if (updatedSince !== null) {
      values.push(updatedSince);
      sql += ` AND updated_at > $${values.length}`;
    }
    values.push(limit + 1);
    sql += ` ORDER BY updated_at, id LIMIT $${values.length}`;
    const { rows } = await this.pool.query(sql, values);
    const all = (rows as Row[]).map(locationFromRow);
    const items = all.slice(0, limit);
    const last = items[items.length - 1];
    return { items, next_cursor: all.length > items.length && last ? last.updated_at : null };
  }

  // --- beat plans
  async insertBeatPlan(p: BeatPlan): Promise<void> {
    await this.pool.query(
      `INSERT INTO beat_plans (id, tenant_id, dc_user_id, plan_date, version, updated_at)
       VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (id) DO NOTHING`,
      [p.id, p.tenant_id, p.dc_user_id, p.plan_date, p.version, p.updated_at],
    );
    for (const s of p.stops) {
      await this.pool.query(
        `INSERT INTO beat_plan_stops (id, tenant_id, beat_plan_id, seq, location_id, visit_type)
         VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (id) DO NOTHING`,
        [s.id, p.tenant_id, p.id, s.seq, s.location_id, s.visit_type],
      );
    }
  }
  private async plansFromRows(rows: Row[]): Promise<BeatPlan[]> {
    const plans: BeatPlan[] = [];
    for (const r of rows) {
      const { rows: stopRows } = await this.pool.query(
        `SELECT * FROM beat_plan_stops WHERE tenant_id = $1 AND beat_plan_id = $2 ORDER BY seq`,
        [r.tenant_id, r.id],
      );
      plans.push({
        id: r.id as string,
        tenant_id: r.tenant_id as string,
        dc_user_id: r.dc_user_id as string,
        plan_date: typeof r.plan_date === "string" ? r.plan_date : (r.plan_date as Date).toISOString().slice(0, 10),
        version: r.version as number,
        updated_at: (r.updated_at as Date).toISOString(),
        stops: (stopRows as Row[]).map((s) => ({
          id: s.id as string,
          seq: s.seq as number,
          location_id: s.location_id as string,
          visit_type: s.visit_type as BeatPlan["stops"][number]["visit_type"],
        })),
      });
    }
    return plans;
  }
  async listBeatPlansForDcs(tenantId: TenantId, dcUserIds: "ALL" | ReadonlySet<string>): Promise<BeatPlan[]> {
    const values: unknown[] = [tenantId];
    let sql = `SELECT * FROM beat_plans WHERE tenant_id = $1`;
    if (dcUserIds !== "ALL") {
      values.push([...dcUserIds]);
      sql += ` AND dc_user_id = ANY($${values.length}::uuid[])`;
    }
    const { rows } = await this.pool.query(sql, values);
    return this.plansFromRows(rows as Row[]);
  }
  async listBeatPlansFromDate(scope: Scope, fromDate: string): Promise<BeatPlan[]> {
    const values: unknown[] = [scope.tenant_id, fromDate];
    let sql = `SELECT * FROM beat_plans WHERE tenant_id = $1 AND plan_date >= $2`;
    if (scope.dc_user_ids !== "ALL") {
      values.push([...scope.dc_user_ids]);
      sql += ` AND dc_user_id = ANY($${values.length}::uuid[])`;
    }
    sql += ` ORDER BY plan_date`;
    const { rows } = await this.pool.query(sql, values);
    return this.plansFromRows(rows as Row[]);
  }

  // --- geo assignments
  async insertGeoAssignment(a: GeoAssignment): Promise<void> {
    await this.pool.query(
      `INSERT INTO geo_assignments (id, tenant_id, am_user_id, dc_user_id, valid_from, valid_to)
       VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (id) DO NOTHING`,
      [a.id, a.tenant_id, a.am_user_id, a.dc_user_id, a.valid_from, a.valid_to],
    );
  }
  async listAssignedDcIds(tenantId: TenantId, amUserId: string, asOfDate: string): Promise<string[]> {
    const { rows } = await this.pool.query(
      `SELECT dc_user_id FROM geo_assignments
       WHERE tenant_id = $1 AND am_user_id = $2 AND valid_from <= $3 AND (valid_to IS NULL OR valid_to >= $3)`,
      [tenantId, amUserId, asOfDate],
    );
    return (rows as Row[]).map((r) => r.dc_user_id as string);
  }

  // --- evidence (append-only: INSERT ... ON CONFLICT DO NOTHING only)
  async insertCheckinEventIfAbsent(e: StoredCheckInEvent): Promise<void> {
    await this.pool.query(
      `INSERT INTO checkin_events (id, tenant_id, dc_user_id, device_id, location_id, planned_stop_id,
                                   beat_plan_version, fix, device_wall_time, monotonic_ms, server_received_at,
                                   out_of_radius_reason, remarks)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
       ON CONFLICT (id) DO NOTHING`,
      [
        e.id, e.tenant_id, e.dc_user_id, e.device_id, e.location_id, e.planned_stop_id ?? null,
        e.beat_plan_version ?? null, JSON.stringify(e.fix), e.timestamps.device_wall_time,
        e.timestamps.monotonic_ms, e.timestamps.server_received_at, e.out_of_radius_reason ?? null,
        e.remarks ?? null,
      ],
    );
  }
  async countCheckinEvents(tenantId: TenantId): Promise<number> {
    const { rows } = await this.pool.query(`SELECT count(*)::int AS n FROM checkin_events WHERE tenant_id = $1`, [tenantId]);
    return (rows[0] as { n: number }).n;
  }
  async insertVisitIfAbsent(v: Visit): Promise<void> {
    await this.pool.query(
      `INSERT INTO visits (id, tenant_id, dc_user_id, location_id, planned, occurred_at, server_received_at,
                           fix, distance_from_master_m, geofence_result, out_of_radius_reason, sync_state, occurred_ist_date)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
       ON CONFLICT (id) DO NOTHING`,
      [
        v.id, v.tenant_id, v.dc_user_id, v.location_id, v.planned, v.occurred_at, v.server_received_at,
        JSON.stringify(v.fix), v.distance_from_master_m, v.geofence_result, v.out_of_radius_reason,
        v.sync_state, istDateOf(v.occurred_at),
      ],
    );
  }
  async listVisitsByIstDate(scope: Scope, istDate: string): Promise<VisitView[]> {
    // Scoping applied HERE in SQL (single choke-point Scope) — never post-filtered.
    const values: unknown[] = [scope.tenant_id, istDate];
    let sql = `SELECT v.*, u.name AS dc_name, l.name AS location_name, l.code AS location_code
               FROM visits v
               JOIN users u ON u.tenant_id = v.tenant_id AND u.id = v.dc_user_id
               JOIN locations l ON l.tenant_id = v.tenant_id AND l.id = v.location_id
               WHERE v.tenant_id = $1 AND v.occurred_ist_date = $2`;
    if (scope.dc_user_ids !== "ALL") {
      values.push([...scope.dc_user_ids]);
      sql += ` AND v.dc_user_id = ANY($${values.length}::uuid[])`;
    }
    sql += ` ORDER BY v.occurred_at, v.id`;
    const { rows } = await this.pool.query(sql, values);
    return (rows as Row[]).map((r) => ({
      id: r.id as string,
      dc_user_id: r.dc_user_id as string,
      dc_name: r.dc_name as string,
      location_id: r.location_id as string,
      location_name: r.location_name as string,
      location_code: r.location_code as string,
      planned: r.planned as boolean,
      checkin: {
        occurred_at: (r.occurred_at as Date).toISOString(),
        server_received_at: (r.server_received_at as Date).toISOString(),
        fix: r.fix as VisitView["checkin"]["fix"],
      },
      distance_from_master_m: r.distance_from_master_m as number,
      geofence_result: r.geofence_result as VisitView["geofence_result"],
      out_of_radius_reason: r.out_of_radius_reason as VisitView["out_of_radius_reason"],
      sync_state: r.sync_state as VisitView["sync_state"],
    }));
  }

  // --- sync op dedupe
  async getOpDisposition(tenantId: TenantId, opId: string): Promise<OpDisposition | null> {
    const { rows } = await this.pool.query(
      `SELECT disposition FROM sync_op_dispositions WHERE tenant_id = $1 AND op_id = $2`,
      [tenantId, opId],
    );
    return rows[0] ? ((rows[0] as Row).disposition as OpDisposition) : null;
  }
  async putOpDispositionIfAbsent(tenantId: TenantId, opId: string, d: OpDisposition): Promise<void> {
    await this.pool.query(
      `INSERT INTO sync_op_dispositions (tenant_id, op_id, disposition, first_seen_at)
       VALUES ($1,$2,$3, now()) ON CONFLICT (tenant_id, op_id) DO NOTHING`,
      [tenantId, opId, JSON.stringify(d)],
    );
  }

  // --- quarantine
  async insertQuarantineIfAbsent(q: QuarantinedOp): Promise<void> {
    await this.pool.query(
      `INSERT INTO quarantined_ops (tenant_id, op_id, batch_id, device_id, submitted_by_user_id, reason, errors, raw, received_at)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) ON CONFLICT (tenant_id, op_id) DO NOTHING`,
      [
        q.tenant_id, q.op_id, q.batch_id, q.device_id, q.submitted_by_user_id, q.reason,
        JSON.stringify(q.errors), q.raw === undefined ? null : JSON.stringify(q.raw), q.received_at,
      ],
    );
  }
  async countQuarantined(tenantId: TenantId): Promise<number> {
    const { rows } = await this.pool.query(`SELECT count(*)::int AS n FROM quarantined_ops WHERE tenant_id = $1`, [tenantId]);
    return (rows[0] as { n: number }).n;
  }
}
