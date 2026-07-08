# ADR-0006: Tenant-scoped from day one; v1 operates single-tenant (Eko)
Status: Accepted (locked; multiple BC companies confirmed)
Every entity carries tenant_id. Authorization = tenant → role × scope-node on the geo tree,
enforced at ONE data-access choke point. No query path bypasses it.
