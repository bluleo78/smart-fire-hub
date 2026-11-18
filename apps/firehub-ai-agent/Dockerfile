# 빌드 컨텍스트는 저장소 루트다: docker build -f apps/firehub-ai-agent/Dockerfile .
#
# === base: 설치 공통 바탕 ===
# 빌드 스테이지에 --platform=$BUILDPLATFORM 를 쓰지 않는다(빌더 네이티브 아키 고정 금지).
# @anthropic-ai/claude-agent-sdk 는 아키별 네이티브 CLI 바이너리(linux-x64-musl 등)를
# optional dep 으로 받는데, 빌드 스테이지를 빌더(arm64) 아키에 고정하면 그 node_modules 가
# 타깃(amd64) 런타임으로 그대로 COPY 되어 "Native CLI binary for linux-x64 not found" 로 죽는다.
# 핀을 빼면 멀티아치 빌드 시 각 타깃이 자기 아키(QEMU 에뮬)로 install 해 올바른 네이티브 패키지를 받는다.
# (prod-deps·build 두 스테이지 모두 이 base 를 쓰므로 둘 다 타깃 아키로 설치한다 — channel 과 다른 점)
FROM node:22-alpine AS base

# puppeteer 의 postinstall Chrome 다운로드를 끈다(WD-702). 런타임은 alpine 패키지 chromium 을 쓰고,
# 빌드 스테이지에서 받아 봐야 런타임으로 복사되지 않으며 glibc 용 Chrome 은 musl(alpine)에서 돌지도 않는다.
ENV PUPPETEER_SKIP_DOWNLOAD=true

# pnpm 은 corepack 으로 루트 package.json 의 packageManager 판(10.11.0)을 쓴다 — lockfile 을 만든 판과 같아야
# --frozen-lockfile 이 판 차이로 어긋나지 않는다
RUN corepack enable && corepack prepare pnpm@10.11.0 --activate

WORKDIR /app

# 워크스페이스 설정·lockfile·npmrc(shamefully-hoist=true)와 ai-agent 의 package.json 만 먼저 복사해 설치 층을 캐시한다
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json .npmrc ./
COPY apps/firehub-ai-agent/package.json ./apps/firehub-ai-agent/

# === prod-deps: 런타임에 실을 운영 의존성 — WD-802 ===
# 왜 `pnpm deploy --legacy --prod` 를 쓰지 않나: legacy deploy 는 lockfile 을 보지 않고 package.json 의 판 범위를
# 다시 해석해, 빌드 시점 최신 판이 들어오고 루트 pnpm.overrides(WD-790 에서 취약 전이 의존성을 올린 것)도 빠진다
# (실측: 빌드 로그에 "configuration forces legacy deploy implementation" 경고 뒤 레지스트리 해석 572개 — 수 분 걸림).
# legacy 가 아닌 deploy 는 pnpm 10.11 에서 overrides 가 있으면 ERR_PNPM_LOCKFILE_CONFIG_MISMATCH 로 실패한다(WD-799 실측).
# 그래서 같은 lockfile 로 --frozen-lockfile --prod 설치를 따로 해 그 node_modules 를 그대로 복사한다 — lockfile 의 판과
# overrides 가 그대로 적용된다(실측: --offline 으로도 스토어만으로 설치 성공 = 범위 재해석 없음).
# --ignore-scripts: 운영 의존성 중 설치 스크립트가 있는 것은 canvas(install: prebuild-install)·puppeteer(postinstall:
# Chrome 다운로드) 둘뿐인데(실측), 둘 다 루트 pnpm.onlyBuiltDependencies(esbuild 뿐) 밖이라 이 플래그가 없어도
# pnpm 10 이 돌리지 않는다 — 결과 node_modules 가 같다. 이 플래그가 실제로 막는 것은 루트 prepare(husky)다:
# husky 는 devDependency 라 --prod 설치에 없어 "husky: not found" 로 설치가 실패한다(실측).
FROM base AS prod-deps
RUN pnpm install --frozen-lockfile --prod --ignore-scripts --filter @smart-fire-hub/firehub-ai-agent

# === build: TypeScript 빌드 ===
FROM base AS build

# TypeScript 빌드에 필요한 devDependencies 까지 설치한다(이 스테이지의 node_modules 는 런타임으로 복사하지 않는다)
RUN pnpm install --frozen-lockfile --filter @smart-fire-hub/firehub-ai-agent...

# 소스 복사(루트 .dockerignore 가 *.test.ts·node_modules·dist 를 거른다)
COPY apps/firehub-ai-agent/ ./apps/firehub-ai-agent/

# TypeScript 빌드 — dist 와 그 안에 복사되는 서브에이전트 정의만 런타임으로 옮긴다
RUN pnpm --filter @smart-fire-hub/firehub-ai-agent build

# === 런타임 ===
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

# 운영 의존성은 prod-deps 스테이지의 워크스페이스 배치를 그대로 옮긴다 — WD-802(channel 의 WD-799 와 같은 방식).
# pnpm node_modules 는 .pnpm 가상 저장소를 가리키는 상대 심볼릭 링크라 같은 상대 위치를 지켜야 한다:
#  - /app/node_modules: .pnpm 실제 파일 + (shamefully-hoist) 끌어올린 링크
#  - /app/apps/firehub-ai-agent/node_modules: 직접 의존성 링크(../../node_modules/.pnpm/... 를 가리킴)
# package.json 은 "type": "module" 이 있어야 dist 의 ESM 이 그대로 돈다(dist 의 가장 가까운 package.json 이 이것이다).
# 그래서 앱 파일은 /app/apps/firehub-ai-agent 아래에 둔다. dist 안에서 경로를 푸는 곳은 dist 기준 상대라 영향이 없다:
# MCP stdio 서버(stdio-server-command.ts — dist/mcp/stdio-server.js 를 같은 폴더에서 찾음),
# 서브에이전트 정의(subagent-loader.ts — dist/agent/subagents, 빌드가 dist 안에 복사).
# 소유자는 COPY --chown 으로 바로 appuser 로 둔다 — 복사 뒤 `chown -R` 를 따로 돌리면 node_modules 전체가 새 층으로 한 번 더
# 실려 이미지가 수백 MB 커지고 그 층을 만드는 데도 오래 걸린다.
COPY --from=prod-deps --chown=appuser:appgroup /app/node_modules ./node_modules
COPY --from=prod-deps --chown=appuser:appgroup /app/apps/firehub-ai-agent/node_modules ./apps/firehub-ai-agent/node_modules
COPY --from=prod-deps --chown=appuser:appgroup /app/apps/firehub-ai-agent/package.json ./apps/firehub-ai-agent/
COPY --from=build --chown=appuser:appgroup /app/apps/firehub-ai-agent/dist ./apps/firehub-ai-agent/dist

# 작업 폴더(/app)와 앱 폴더 자체도 appuser 가 쓰게 둔다(예전 `chown -R /app` 과 같은 권한 — 파일은 위 --chown 이 맡았다)
RUN chown appuser:appgroup /app /app/apps /app/apps/firehub-ai-agent

# Pre-create Claude SDK directories (volume mount may override with empty dir)
RUN mkdir -p /home/appuser/.claude/debug /home/appuser/.claude/projects \
    && echo '{}' > /home/appuser/.claude/remote-settings.json \
    && chown -R appuser:appgroup /home/appuser/.claude

USER appuser

# 작업 폴더(cwd)는 예전처럼 /app 으로 둔다 — 앱 파일만 /app/apps/firehub-ai-agent 로 옮겼다. cwd 를 바꾸면 두 곳이 조용히 달라진다:
#  - Claude CLI 가 대화 기록을 cwd 기준 폴더(~/.claude/projects/-app)에 쓰고 찾는다. SDK 경로(agent-sdk.ts)는 cwd 를 주지 않아
#    이 컨테이너의 cwd 를 쓰므로, 바뀌면 배포 전에 시작한 대화를 이어 가지 못한다(resume 실패).
#  - index.ts 가 .env.local 을 cwd 기준으로 읽는다 — /app/.env.local 에 붙여 둔 설정이 무시된다.
EXPOSE 3001

CMD ["node", "apps/firehub-ai-agent/dist/index.js"]
