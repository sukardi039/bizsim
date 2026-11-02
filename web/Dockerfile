# Console container: static bundle behind nginx, which also proxies /api to the backend.
# Building nginx in front of the API means one origin for the browser, so no CORS and no
# build-time knowledge of the API host.

# ---------------------------------------------------------------- 1. build
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Optional: only set this if the browser must call the API on a different origin (e.g. a separately
# hosted API). Left empty, the console calls "/api" on its own origin and nginx forwards it.
# It is a build arg because Vite inlines env vars at build time, not at runtime.
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

RUN npm run build

# ---------------------------------------------------------------- 2. serve
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
