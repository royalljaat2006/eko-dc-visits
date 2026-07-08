# ADR-0001: One monorepo with territory ownership
Status: Accepted (locked by product owner)
One repo: contracts / backend / android / web / fixtures / infra / docs. Contracts change
atomically with consumers; CI is path-filtered; codegen keeps clients in lockstep.
Deploy boundaries are not repo boundaries.
