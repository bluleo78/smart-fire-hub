#!/usr/bin/env bash
set -euo pipefail

# Smart Fire Hub 운영 배포 스크립트
# Usage: ./scripts/deploy.sh [api|executor|web|ai-agent|channel|all]
# all = api + executor + web + ai-agent + channel (5개 앱 전부)

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
      docker buildx build --platform "$PLATFORM" --no-cache -t "$REGISTRY/api:latest" --push apps/firehub-api/
      ;;
    web)
      log "Building + pushing $app (context: project root)"
      docker buildx build --platform "$PLATFORM" --no-cache -t "$REGISTRY/web:latest" -f apps/firehub-web/Dockerfile --push .
      ;;
    ai-agent)
      log "Building + pushing $app (context: project root)"
      docker buildx build --platform "$PLATFORM" --no-cache -t "$REGISTRY/ai-agent:latest" -f apps/firehub-ai-agent/Dockerfile --push .
      ;;
    executor)
      log "Building + pushing $app (context: apps/firehub-executor/)"
      docker buildx build --platform "$PLATFORM" --no-cache -t "$REGISTRY/executor:latest" --push apps/firehub-executor/
      ;;
    channel)
      log "Building + pushing $app (context: apps/firehub-channel/)"
      docker buildx build --platform "$PLATFORM" --no-cache -t "$REGISTRY/channel:latest" --push apps/firehub-channel/
      ;;
    *)
      error "Unknown app: $app (valid: api, web, ai-agent, executor, channel)"
      ;;
  esac
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

# Docker 로그인 확인
if ! docker info 2>/dev/null | grep -q "ghcr.io"; then
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
