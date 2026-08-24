# === Stage 1: Build ===
# firehub-web 과 1:1 대응 — 리포 루트를 빌드 컨텍스트로 하는 pnpm 워크스페이스 빌드
FROM --platform=$BUILDPLATFORM node:22-alpine AS build

RUN corepack enable && corepack prepare pnpm@10.11.0 --activate

WORKDIR /app

# 워크스페이스 설정·락파일·npmrc(shamefully-hoist=true) 먼저 복사 — 레이어 캐시 극대화
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json .npmrc ./
COPY apps/firehub-admin/package.json ./apps/firehub-admin/

# 의존성 설치 (admin 및 그 워크스페이스 의존성만 필터링)
RUN pnpm install --frozen-lockfile --filter @smart-fire-hub/firehub-admin...

# 소스 복사
COPY apps/firehub-admin/ ./apps/firehub-admin/

# 빌드 (tsc -b && vite build)
RUN pnpm --filter @smart-fire-hub/firehub-admin build

# === Stage 2: Nginx ===
FROM nginx:alpine

# 빌드된 정적 파일 복사
COPY --from=build /app/apps/firehub-admin/dist /usr/share/nginx/html

# nginx 설정 복사
COPY apps/firehub-admin/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
