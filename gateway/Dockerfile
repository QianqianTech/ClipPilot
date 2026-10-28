# CommentModelGateway: zero-dependency Node.js service. No secrets are baked in;
# inject MODEL_BASE_URL / MODEL_API_KEY / MODEL_GATEWAY_CLIENT_TOKEN /
# GATEWAY_ADMIN_TOKEN at runtime (e.g. `docker run --env-file`).
FROM node:22-alpine

ENV NODE_ENV=production \
    GATEWAY_BIND_HOST=0.0.0.0 \
    GATEWAY_PORT=8787 \
    GATEWAY_PROFILE_FILE=/app/data/model-profiles.json

WORKDIR /app

COPY package.json ./
COPY src ./src
COPY public ./public
COPY config ./config

# Code and shipped config stay root-owned and read-only; only the mutable
# model-profile directory belongs to the unprivileged runtime user.
RUN mkdir -p /app/data && chown node:node /app/data && chmod 700 /app/data

USER node

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:' + (process.env.GATEWAY_PORT || 8787) + '/health').then((r) => process.exit(r.ok ? 0 : 1), () => process.exit(1))"]

# exec form: node is PID 1's direct child target and receives SIGTERM for graceful shutdown.
CMD ["node", "src/server.mjs"]
