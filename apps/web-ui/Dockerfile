# Stage 1: build
FROM node:20-alpine AS builder

# VITE_* vars are baked into the static bundle at build time.
# Pass them as build args when building the K8s image:
#   docker build \
#     --build-arg VITE_KEYCLOAK_URL=/auth \
#     --build-arg VITE_API_BASE_URL= \
#     --build-arg VITE_API_PATH_PREFIX=/api \
#     --build-arg VITE_WS_URL= \
#     --build-arg VITE_LICHESS_BOT_WS_URL= \
#     -t searchess/web-ui:local .
#
# Default values match the deployed Envoy scenario: same-origin API and WS URLs.
ARG VITE_KEYCLOAK_URL=/auth
ARG VITE_KEYCLOAK_REALM=searchess
ARG VITE_KEYCLOAK_CLIENT_ID=searchess-web
ARG VITE_API_BASE_URL=
ARG VITE_API_PATH_PREFIX=/api
ARG VITE_LICHESS_BOT_WS_URL=
ARG VITE_WS_URL=

ENV VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL
ENV VITE_KEYCLOAK_REALM=$VITE_KEYCLOAK_REALM
ENV VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_API_PATH_PREFIX=$VITE_API_PATH_PREFIX
ENV VITE_LICHESS_BOT_WS_URL=$VITE_LICHESS_BOT_WS_URL
ENV VITE_WS_URL=$VITE_WS_URL

# Build context is the repo root (not apps/web-ui) because the prebuild step
# (npm run sync:chess-assets -> tools/sync-chess-assets.mjs) copies chess
# assets from shared/chess-assets/, both of which live outside apps/web-ui.
WORKDIR /repo/apps/web-ui
COPY apps/web-ui/package.json apps/web-ui/package-lock.json* ./
RUN npm ci

WORKDIR /repo
COPY tools/sync-chess-assets.mjs tools/sync-chess-assets.mjs
COPY shared/ shared/
COPY apps/web-ui/ apps/web-ui/

WORKDIR /repo/apps/web-ui
RUN npm run build

# Stage 2: serve
FROM nginx:1.27-alpine AS runtime
COPY --from=builder /repo/apps/web-ui/dist /usr/share/nginx/html
COPY apps/web-ui/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
