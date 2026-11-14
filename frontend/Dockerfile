# syntax=docker/dockerfile:1
# Web image (M1 step 11): the Angular build served by Caddy, which also proxies /api to the backend (same origin,
# ADR-0010). Built by deploy/compose.yaml, which supplies the `deploy` build context holding the Caddyfile. To build
# it alone: docker build --build-context deploy=deploy frontend

# --- Build. Node matches .nvmrc's major version.
FROM node:26.13.1-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# --- Runtime: static files and the reverse proxy.
FROM caddy:2.11.9-alpine
COPY --from=deploy Caddyfile /etc/caddy/Caddyfile
COPY --from=build /workspace/dist/frontend/browser /srv
