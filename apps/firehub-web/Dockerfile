# === Stage 1: Build ===
FROM node:22-alpine AS build

RUN corepack enable && corepack prepare pnpm@10.11.0 --activate

WORKDIR /app

# Copy workspace config, lockfile, and npmrc (shamefully-hoist=true)
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json .npmrc ./
COPY apps/firehub-web/package.json ./apps/firehub-web/

# Install dependencies
RUN pnpm install --frozen-lockfile --filter @smart-fire-hub/firehub-web...

# Copy source
COPY apps/firehub-web/ ./apps/firehub-web/

# Build
RUN pnpm --filter @smart-fire-hub/firehub-web build

# === Stage 2: Nginx ===
FROM nginx:alpine

# Copy built static files
COPY --from=build /app/apps/firehub-web/dist /usr/share/nginx/html

# Copy nginx config
COPY apps/firehub-web/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
