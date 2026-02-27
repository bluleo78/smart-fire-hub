# === Stage 1: Build ===
FROM node:22-alpine AS build

RUN corepack enable && corepack prepare pnpm@10.11.0 --activate

WORKDIR /app

# Copy workspace config, lockfile, and npmrc (shamefully-hoist=true)
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json .npmrc ./
COPY apps/firehub-ai-agent/package.json ./apps/firehub-ai-agent/

# Install all dependencies (including devDependencies for TypeScript build)
RUN pnpm install --frozen-lockfile --filter @smart-fire-hub/firehub-ai-agent...

# Copy source
COPY apps/firehub-ai-agent/ ./apps/firehub-ai-agent/

# Build TypeScript
RUN pnpm --filter @smart-fire-hub/firehub-ai-agent build

# Deploy production-only package (flat node_modules, no devDependencies)
RUN pnpm --filter @smart-fire-hub/firehub-ai-agent deploy --legacy --prod /prod

# === Stage 2: Runtime ===
FROM node:22-alpine

WORKDIR /app

# Copy production dependencies from deploy
COPY --from=build /prod ./

# Copy built JavaScript files
COPY --from=build /app/apps/firehub-ai-agent/dist ./dist

EXPOSE 3001

CMD ["node", "dist/index.js"]
