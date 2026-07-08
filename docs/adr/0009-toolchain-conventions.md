# ADR-0009: Toolchain conventions
Status: Accepted
Backend/web: Node 22, TypeScript, npm workspaces (zero-bootstrap on dev machines), Fastify for
HTTP, node:test for tests, SQL migration files (no ORM migrations). Android: Kotlin + Gradle,
standard layout. Timestamps stored UTC, rendered IST (Asia/Kolkata). Repository interfaces
abstract storage so tests run without Docker (in-memory impl) while prod runs Postgres+PostGIS.
