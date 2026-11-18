# 빌드 컨텍스트는 저장소 루트다(WD-799): docker build -f apps/firehub-channel/Dockerfile .
# 왜: 예전에는 앱 폴더만 컨텍스트로 lockfile 없이 `pnpm install` 해, 루트 pnpm-lock.yaml 이 고정한 판과
# 루트 package.json 의 pnpm.overrides(WD-790 에서 취약 전이 의존성을 올린 것)가 이미지에 적용되지 않았다 —
# 빌드할 때마다 그 시점 최신 판이 들어왔다. ai-agent Dockerfile 처럼 루트 lockfile·워크스페이스 설정으로
# --frozen-lockfile 설치를 해, 저장소에서 시험한 판 그대로가 이미지에 들어가게 한다.
#
# 빌드 스테이지는 빌더 네이티브 아키(--platform=$BUILDPLATFORM)에 고정해 둔다. 런타임으로 복사하는 운영 의존성
# (axios·cors·dotenv·express·nodemailer 와 그 전이 의존성)에 아키별 네이티브 모듈이 없어 결과물이 타깃 아키와 무관하기 때문이다
# (ai-agent 는 claude-agent-sdk 의 아키별 네이티브 CLI 때문에 이 고정을 뺐다 — 그 Dockerfile 머리 주석 참고).
FROM --platform=$BUILDPLATFORM node:22-alpine AS base

# pnpm 은 corepack 으로 루트 package.json 의 packageManager 판(10.11.0)을 쓴다 — lockfile 을 만든 판과 같아야
# --frozen-lockfile 이 판 차이로 어긋나지 않는다(ai-agent Dockerfile 과 같은 방식)
RUN corepack enable && corepack prepare pnpm@10.11.0 --activate

WORKDIR /app

# 워크스페이스 설정·lockfile·npmrc(shamefully-hoist=true)와 channel 의 package.json 만 먼저 복사해 설치 층을 캐시한다
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json .npmrc ./
COPY apps/firehub-channel/package.json ./apps/firehub-channel/

# 런타임에 실을 운영 의존성 스테이지 — WD-799.
# 왜 `pnpm deploy` 를 쓰지 않나: `deploy --legacy` 는 lockfile 을 보지 않고 package.json 의 판 범위(axios ^1.20.0 등)를
# 다시 해석해, 빌드 시점 최신 판이 들어오고 루트 pnpm.overrides 도 빠진다(실측: --offline 으로 돌리면
# ERR_PNPM_NO_OFFLINE_META 「axios@>=1.20.0 <2.0.0-0」 — 저장소 밖 메타데이터를 찾는다는 뜻). legacy 가 아닌 deploy 는
# pnpm 10.11 에서 overrides 가 있으면 ERR_PNPM_LOCKFILE_CONFIG_MISMATCH 로 실패한다(실측).
# 그래서 같은 lockfile 로 --frozen-lockfile --prod 설치를 따로 해 그 node_modules 를 그대로 복사한다 — lockfile 의 판과
# overrides 가 그대로 적용된다(실측: --offline 으로도 스토어만으로 85개 설치 성공 = 범위 재해석 없음).
# --ignore-scripts: 운영 의존성 중 설치 스크립트가 있는 패키지가 없고(실측 0개), 루트 prepare(husky — devDependency 라
# 이 설치에 없음)가 돌다 실패하는 것을 막는다.
FROM base AS prod-deps
RUN pnpm install --frozen-lockfile --prod --ignore-scripts --filter @smart-fire-hub/firehub-channel

FROM base AS build

# TypeScript 빌드에 필요한 devDependencies 까지 설치한다.
# 예전 --ignore-scripts(600f3e6d3)는 앱 폴더 단독 설치에서 전이 의존성 esbuild 의 빌드 스크립트가 막혀
# ERR_PNPM_IGNORED_BUILDS 로 실패하던 것을 피하려던 것이다. 이제는 워크스페이스 설치라 루트 package.json 의
# pnpm.onlyBuiltDependencies(esbuild)가 적용되어 esbuild 스크립트가 허용되므로 우회가 필요 없다(ai-agent 와 같음).
RUN pnpm install --frozen-lockfile --filter @smart-fire-hub/firehub-channel...

# 소스 복사(루트 .dockerignore 가 *.test.ts·node_modules·dist 를 거른다) 후 빌드
COPY apps/firehub-channel/ ./apps/firehub-channel/
RUN pnpm --filter @smart-fire-hub/firehub-channel build

FROM node:22-alpine
# 런타임에 쓰지 않는 npm·npx·corepack 을 지운다(WD-790 — 번들된 npm 의 취약 의존성이 이미지 검사에 잡힌다).
# 지워도 되는 근거: 런타임은 `node dist/index.js` 하나만 실행하고, 의존성은 빌드 스테이지에서 pnpm 으로 받아 복사한다.
# 지운 뒤 npm·npx·corepack 이 PATH 에 남았는지 확인한다 — 베이스 이미지가 설치 위치를 바꾸면 rm 이 조용히 빗나가므로 빌드를 실패시킨다.
# 확인은 새 셸(sh -c)에서 한다 — 같은 RUN 셸은 앞서 실행한 npm 의 경로를 해시해 두어, 파일을 지운 뒤에도 command -v 가 그 경로를 내기 때문이다.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
           /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
    && sh -c '! command -v npm && ! command -v npx && ! command -v corepack'
WORKDIR /app
# 운영 의존성은 prod-deps 스테이지의 워크스페이스 배치를 그대로 옮긴다 — WD-799.
# pnpm node_modules 는 .pnpm 가상 저장소를 가리키는 상대 심볼릭 링크라 같은 상대 위치를 지켜야 한다:
#  - /app/node_modules: .pnpm 실제 파일 + (shamefully-hoist) 끌어올린 링크
#  - /app/apps/firehub-channel/node_modules: 직접 의존성 링크(../../node_modules/.pnpm/... 를 가리킴)
# package.json 은 "type": "module" 이 있어야 dist 의 ESM 이 그대로 돈다(dist 의 가장 가까운 package.json 이 이것이다).
COPY --from=prod-deps /app/node_modules ./node_modules
COPY --from=prod-deps /app/apps/firehub-channel/node_modules ./apps/firehub-channel/node_modules
COPY --from=prod-deps /app/apps/firehub-channel/package.json ./apps/firehub-channel/
COPY --from=build /app/apps/firehub-channel/dist ./apps/firehub-channel/dist
WORKDIR /app/apps/firehub-channel
EXPOSE 3002
CMD ["node", "dist/index.js"]
