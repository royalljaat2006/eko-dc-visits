# ADR-0002: Postgres(+PostGIS) + object storage + pg-boss is the entire backend state
Status: Accepted (locked)
5M GPS points/day at 5k-DC ceiling ≈ 58 writes/sec average (bursty; design for herds).
No Kafka, no Mongo, no OLAP, no mandatory Redis. Any second datastore requires
pilot-measured evidence. Modular monolith + worker entrypoint, one docker-compose.
