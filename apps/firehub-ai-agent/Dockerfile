# === Stage 1: Build ===
FROM --platform=$BUILDPLATFORM node:22-alpine AS build

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

# Install Claude Code CLI (required by @anthropic-ai/claude-agent-sdk)
RUN npm install -g @anthropic-ai/claude-code

# Install OpenCode CLI (required by the `opencode` agent type)
# npm 래퍼가 플랫폼에 맞는 바이너리를 받아 alpine(musl)에서도 동작한다.
# 모델 인증은 배포 환경의 opencode 전역 설정/환경변수에 의존(옵션 3).
RUN npm install -g opencode-ai

# Create non-root user (Claude Code blocks --dangerously-skip-permissions as root)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy production dependencies from deploy
COPY --from=build /prod ./

# Copy built JavaScript files
COPY --from=build /app/apps/firehub-ai-agent/dist ./dist

RUN chown -R appuser:appgroup /app

# Pre-create Claude SDK directories (volume mount may override with empty dir)
RUN mkdir -p /home/appuser/.claude/debug /home/appuser/.claude/projects \
    && echo '{}' > /home/appuser/.claude/remote-settings.json \
    && chown -R appuser:appgroup /home/appuser/.claude

USER appuser

EXPOSE 3001

CMD ["node", "dist/index.js"]
