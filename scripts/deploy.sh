#!/usr/bin/env bash
set -euo pipefail

# Smart Fire Hub 운영 배포 스크립트
# Usage: ./scripts/deploy.sh [api|executor|db-executor|web|ai-agent|channel|admin|db|minio|all]
# all = api + executor + web + ai-agent + channel (5개 앱 전부, db/minio/admin/db-executor 제외 — 모두 개별 배포로만 재기동)
# admin(firehub-admin, 플랫폼 슈퍼관리자 콘솔)은 권한상승 경로를 배포 수준에서도 분리하기 위해
# 의도적으로 all 밖에 둔다 — 배포는 항상 `./scripts/deploy.sh admin`으로 명시적으로 트리거한다.

REGISTRY="ghcr.io/bluleo78/smart-fire-hub"
PROD_DIR="$HOME/prod/smart-fire-hub"

# 색상
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[deploy]${NC} $1"; }
warn() { echo -e "${YELLOW}[warn]${NC} $1"; }
error() { echo -e "${RED}[error]${NC} $1"; exit 1; }

# 각 앱의 빌드 context와 Dockerfile 경로
# API: context = apps/firehub-api/ (Dockerfile이 상대경로 COPY 사용)
# WEB, AI-AGENT: context = . (프로젝트 루트, Dockerfile이 apps/ 절대경로 COPY 사용)
PLATFORM="linux/amd64,linux/arm64"
# 모든 빌드에 --pull 을 준다(WD-794): 빌더 캐시에 남은 옛 베이스 이미지(nginx:alpine·node·postgis 등)를 그대로 쓰면
# 베이스의 보안 수정이 운영 이미지에 들어가지 않는다. --pull 은 베이스 태그의 최신 digest 를 받아 오고,
# 베이스가 바뀌지 않았으면 레이어 캐시는 그대로 쓰므로 빌드 시간은 거의 늘지 않는다.
# web·admin·db 는 OS 패키지 갱신 단계(apk/apt upgrade)가 있어, 빌드 날짜를 OS_PATCH_DATE 로 넘겨 그 층만 날마다 다시 돌린다
# (베이스 digest 가 그대로면 캐시된 옛 갱신 층이 재사용돼 그 뒤 나온 보안 수정이 빠지기 때문이다).
OS_PATCH_ARG=(--build-arg "OS_PATCH_DATE=$(date +%F)")

ensure_builder() {
  if ! docker buildx inspect multiplatform &>/dev/null; then
    log "Creating multiplatform builder"
    docker buildx create --name multiplatform --use
  else
    docker buildx use multiplatform
  fi
}

build_and_push() {
  local app=$1
  case $app in
    api)
      log "Building + pushing $app (context: apps/firehub-api/)"
      docker buildx build --pull --platform "$PLATFORM" -t "$REGISTRY/api:latest" --push apps/firehub-api/
      ;;
    web)
      log "Building + pushing $app (context: project root)"
      docker buildx build --pull "${OS_PATCH_ARG[@]}" --platform "$PLATFORM" -t "$REGISTRY/web:latest" -f apps/firehub-web/Dockerfile --push .
      ;;
    ai-agent)
      log "Building + pushing $app (context: project root)"
      docker buildx build --pull --platform "$PLATFORM" -t "$REGISTRY/ai-agent:latest" -f apps/firehub-ai-agent/Dockerfile --push .
      ;;
    executor)
      log "Building + pushing $app (context: apps/firehub-executor/)"
      docker buildx build --pull --platform "$PLATFORM" -t "$REGISTRY/executor:latest" --push apps/firehub-executor/
      ;;
    db-executor)
      log "Building + pushing $app (context: apps/firehub-db-executor/)"
      docker buildx build --pull --platform "$PLATFORM" -t "$REGISTRY/db-executor:latest" --push apps/firehub-db-executor/
      ;;
    channel)
      # 루트 컨텍스트(WD-799): 루트 pnpm-lock.yaml·pnpm.overrides 로 --frozen-lockfile 설치해 시험한 판 그대로 이미지에 넣는다
      log "Building + pushing $app (context: project root)"
      docker buildx build --pull --platform "$PLATFORM" -t "$REGISTRY/channel:latest" -f apps/firehub-channel/Dockerfile --push .
      ;;
    admin)
      log "Building + pushing $app (context: project root)"
      docker buildx build --pull "${OS_PATCH_ARG[@]}" --platform "$PLATFORM" -t "$REGISTRY/admin:latest" -f apps/firehub-admin/Dockerfile --push .
      ;;
    db)
      # 옛 볼륨을 새 베이스로 처음 열 때 손으로 할 절차가 있어 잊지 않게 알린다(WD-794).
      # 떠 있는 db 의 PostGIS 확장이 이미 3.6.1 이면 전환을 마친 볼륨이라 알리지 않는다(WD-798) — 판을 못 읽으면 알린다.
      if [ "$(current_postgis_version)" != "3.6.1" ]; then
        warn "db 베이스가 PostGIS 3.6.1·debian 13 입니다 — 옛 볼륨에 처음 배포하면 .claude/docs/deploy.md 의 WD-794 전환 절차(스냅샷·postgis_extensions_upgrade·REINDEX)를 먼저 따르세요"
      fi
      log "Building + pushing $app (context: project root, image tag: postgres)"
      docker buildx build --pull "${OS_PATCH_ARG[@]}" --platform "$PLATFORM" -t "$REGISTRY/postgres:latest" -f docker/postgres/Dockerfile --push .
      ;;
    minio)
      log "$app is a public image (cgr.dev/chainguard/minio — MinIO 공식 이미지 배포 중단) — skip build/push, pull only"
      ;;
    *)
      error "Unknown app: $app (valid: api, web, ai-agent, executor, db-executor, channel, admin, db, minio)"
      ;;
  esac
}

# 떠 있는 운영 db 의 PostGIS 확장 판(pg_extension.extversion)을 출력한다(WD-798). db 배포 때 WD-794 전환 절차 경고를
# 이미 전환한 볼륨에는 내지 않으려는 것이다. 사용자·DB 이름은 db 컨테이너 자신의 POSTGRES_USER·POSTGRES_DB 를 쓴다(운영 .env 를
# 따로 읽지 않는다). db 가 떠 있지 않거나 psql 이 실패하면 빈 문자열을 낸다 — set -e 아래에서도 배포를 멈추지 않고, 판을 모르면
# 경고를 내는 쪽으로 간다. db 가 떠 있지만 멈춰 있으면(복구 중·잠금) psql 이 끝나지 않아 배포 전체가 멈추므로 10초로 자른다
# (timeout 이 없는 맥 기본 셸이면 명령이 실패해 판 모름 → 경고로 간다).
current_postgis_version() {
  local version
  version=$(cd "$PROD_DIR" 2>/dev/null \
    && timeout 10 docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$1"' sh \
      "select extversion from pg_extension where extname = 'postgis'" 2>/dev/null) || true
  echo "$version" | tr -d '[:space:]'
}

# 빌드 키(api/web/ai-agent/executor/channel)를 운영 docker-compose 서비스명으로 매핑.
# channel 만 운영 서비스명이 'firehub-channel'로 다르다 (이름 mismatch 흡수).
prod_service_name() {
  case "$1" in
    channel) echo "firehub-channel" ;;
    *) echo "$1" ;;
  esac
}

deploy_app() {
  local app=$1
  local service
  service=$(prod_service_name "$app")
  log "Deploying $app (service=$service) to production"
  cd "$PROD_DIR"
  docker compose pull "$service"
  docker compose up -d --force-recreate "$service"
  cd - > /dev/null
}

verify_app() {
  local app=$1
  local service
  service=$(prod_service_name "$app")
  log "Verifying $app (service=$service) container..."
  sleep 10
  local status
  status=$(cd "$PROD_DIR" && docker compose ps "$service" --format '{{.Status}}' 2>/dev/null)
  if echo "$status" | grep -qi "up\|running"; then
    log "$app is running: $status"
  else
    error "$app failed to start: $status"
  fi
}

# --- Main ---

TARGET=${1:-all}

# Docker 로그인 확인 (config.json 직접 확인 — `docker info`가 plugin 응답 대기로 hang 되는 문제 회피)
if ! grep -q "ghcr.io" "$HOME/.docker/config.json" 2>/dev/null; then
  warn "ghcr.io 로그인이 필요할 수 있습니다"
fi

if [ "$TARGET" = "all" ]; then
  # 운영 5개 앱 전부 (channel 포함). 추가/제거 시 update.sh 와 docs/deploy.md 도 함께 수정.
  APPS=("api" "executor" "web" "ai-agent" "channel")
else
  APPS=("$TARGET")
fi

# 1. Build + Push (multiplatform)
log "=== Build + Push Phase ==="
ensure_builder
for app in "${APPS[@]}"; do
  build_and_push "$app"
done

# 3. Deploy
log "=== Deploy Phase ==="
for app in "${APPS[@]}"; do
  deploy_app "$app"
done

# 4. Verify
log "=== Verify Phase ==="
for app in "${APPS[@]}"; do
  verify_app "$app"
done

log "=== Deployment Complete ==="
