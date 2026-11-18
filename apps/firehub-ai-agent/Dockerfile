# === Stage 1: Build ===
# 빌드 스테이지에 --platform=$BUILDPLATFORM 를 쓰지 않는다(빌더 네이티브 아키 고정 금지).
# @anthropic-ai/claude-agent-sdk 는 아키별 네이티브 CLI 바이너리(linux-x64-musl 등)를
# optional dep 으로 받는데, 빌드 스테이지를 빌더(arm64) 아키에 고정하면 그 node_modules 가
# 타깃(amd64) 런타임으로 그대로 COPY 되어 "Native CLI binary for linux-x64 not found" 로 죽는다.
# 핀을 빼면 멀티아치 빌드 시 각 타깃이 자기 아키(QEMU 에뮬)로 install 해 올바른 네이티브 패키지를 받는다.
FROM node:22-alpine AS build

# puppeteer 의 postinstall Chrome 다운로드를 끈다(WD-702). 런타임은 alpine 패키지 chromium 을 쓰고,
# 빌드 스테이지에서 받아 봐야 런타임으로 복사되지 않으며 glibc 용 Chrome 은 musl(alpine)에서 돌지도 않는다.
ENV PUPPETEER_SKIP_DOWNLOAD=true

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

# Claude Code CLI(@anthropic-ai/claude-agent-sdk 가 spawn)와 OpenCode CLI(`opencode` 에이전트 타입)를 설치한다.
# opencode npm 래퍼가 플랫폼에 맞는 바이너리를 받아 alpine(musl)에서도 동작한다.
# 모델 인증은 테넌트별 opencode 자격증명에서 온다(옵션 3 폐기, 2026-09-19 이슈 #693) — 요청마다
# buildOpenCodeConfig(agent-opencode.ts)가 provider 블록을 OPENCODE_CONFIG_CONTENT 로 자식
# 프로세스에 주입한다. 이 컨테이너의 env 에는 opencode 모델 인증용 값을 두지 않는다.
# 설치가 끝나면 같은 RUN 에서 npm·npx·corepack 을 지운다(WD-790 — 번들된 npm 의 취약 의존성이 이미지 검사에 잡힌다).
# 지워도 되는 근거: 런타임은 `claude`·`opencode` 바이너리와 MCP stdio 서버(`node dist/mcp/stdio-server.js`,
# stdio-server-command.ts)만 실행하고 npm/npx 를 부르는 곳이 없다. opencode 의 설정 로드도 npm 없이 끝남을 이미지에서 확인했다.
# 지운 뒤 npm·npx·corepack 이 PATH 에 남았는지 확인한다 — 베이스 이미지가 설치 위치를 바꾸면 rm 이 조용히 빗나가므로 빌드를 실패시킨다.
# 확인은 새 셸(sh -c)에서 한다 — 같은 RUN 셸은 앞서 실행한 npm 의 경로를 해시해 두어, 파일을 지운 뒤에도 command -v 가 그 경로를 내기 때문이다.
RUN npm install -g @anthropic-ai/claude-code opencode-ai \
    && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
              /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
              /root/.npm \
    && sh -c '! command -v npm && ! command -v npx && ! command -v corepack'

# HTML → PDF 변환(POST /agent/html-to-pdf, puppeteer)용 브라우저와 한글 폰트(WD-702).
# puppeteer 가 받는 Chrome 은 glibc 용이라 alpine 에서 돌지 않고 이미지에 들어오지도 않아 "Could not find Chrome" 500 이 났다.
# 그래서 alpine 의 chromium 을 설치하고, 폰트가 하나도 없으면 한글이 두부(□)로 찍히므로 CJK 폰트를 함께 넣는다.
RUN apk add --no-cache chromium font-noto-cjk

# puppeteer 는 이 환경변수를 launch 때마다 읽어(25 부터 설정을 지연 로드) 위 chromium 을 실행 파일로 쓰고, Chrome 다운로드도 하지 않는다
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium-browser \
    PUPPETEER_SKIP_DOWNLOAD=true

# Claude Code CLI 의 자동 업데이트를 끈다(WD-790). 위에서 npm 을 지웠으므로 CLI 가 spawn 될 때마다 npm 으로
# 새 버전을 받으려다 실패한다(자식마다 헛된 시도·오류 로그). 이미지 안의 CLI 버전은 이미지 빌드로만 바꾼다.
# 자식 env 는 buildClaudeChildEnv(claude-child-env.ts)가 부모 env 를 복사한 뒤 인증 경로만 걷어내므로
# 이 값은 SDK·CLI·검증 라우트의 모든 claude 자식에 그대로 전달된다(claude-child-env.test.ts CCE-08).
ENV DISABLE_AUTOUPDATER=1

# OpenCode CLI 의 자동 업데이트도 끈다(WD-799 — 이유는 agent-opencode.ts 의 OpenCodeConfig.autoupdate 주석).
# 앱이 spawn 하는 opencode 에는 이 ENV 가 닿지 않아 요청 설정 autoupdate:false 로 끄고, 이 ENV 는 운영자가 컨테이너 안에서
# opencode 를 직접 실행하는 경로를 위한 것이다(이름은 설치된 opencode-ai 바이너리에서 확인).
ENV OPENCODE_DISABLE_AUTOUPDATE=1

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
