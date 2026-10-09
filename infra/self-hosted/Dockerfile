# Backend image for a self-hosted (rack / on-prem) deployment.
# Build context is the repo root:
#   docker build -f infra/self-hosted/Dockerfile -t eko-dc-visits-api .
# (A root .dockerignore keeps node_modules / android / .git out of the context —
#  copying host node_modules in would drag Windows shims into a Linux image.)

FROM node:22-slim AS deps
WORKDIR /app/backend
COPY backend/package.json backend/package-lock.json ./
# Full install (incl. tsx/typescript) — tsx runs the TS directly, no build step,
# exactly as the repo does everywhere else (backend/package.json "test").
RUN npm ci --no-audit --no-fund

FROM node:22-slim
WORKDIR /app
ENV NODE_ENV=production HOST=0.0.0.0 PORT=3000

COPY --from=deps /app/backend/node_modules ./backend/node_modules
# Only the sources the server needs at runtime. Contracts are
# imported by the backend via JSON import attributes, so they must be present.
COPY backend/src ./backend/src
COPY backend/scripts ./backend/scripts
COPY backend/migrations ./backend/migrations
COPY backend/package.json ./backend/package.json
COPY contracts ./contracts

EXPOSE 3000
WORKDIR /app/backend
CMD ["node", "--import", "tsx", "src/index.ts"]
