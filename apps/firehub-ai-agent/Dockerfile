# === Stage 1: Build ===
# 빌드 스테이지에 --platform=$BUILDPLATFORM 를 쓰지 않는다(빌더 네이티브 아키 고정 금지).
# @anthropic-ai/claude-agent-sdk 는 아키별 네이티브 CLI 바이너리(linux-x64-musl 등)를
# optional dep 으로 받는데, 빌드 스테이지를 빌더(arm64) 아키에 고정하면 그 node_modules 가
# 타깃(amd64) 런타임으로 그대로 COPY 되어 "Native CLI binary for linux-x64 not found" 로 죽는다.
# 핀을 빼면 멀티아치 빌드 시 각 타깃이 자기 아키(QEMU 에뮬)로 install 해 올바른 네이티브 패키지를 받는다.
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
