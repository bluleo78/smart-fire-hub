#!/usr/bin/env bash
set -euo pipefail

# Smart Fire Hub 원격 업데이트 스크립트
# ghcr.io에서 최신 이미지를 pull하고 컨테이너를 재생성한다.
# Usage: ./scripts/update.sh [api|web|ai-agent|executor|channel|admin|db|minio|all]
# all = api + executor + web + ai-agent + channel (5개 앱 전부, db/minio/admin 제외 — 셋 다 개별 배포로만 재기동)
# admin(firehub-admin, 플랫폼 슈퍼관리자 콘솔)은 권한상승 경로를 배포 수준에서도 분리하기 위해
# 의도적으로 all 밖에 둔다 — 업데이트는 항상 `./scripts/update.sh admin`으로 명시적으로 트리거한다.

PROD_DIR="$HOME/prod/smart-fire-hub"

# 색상
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[update]${NC} $1"; }
warn() { echo -e "${YELLOW}[warn]${NC} $1"; }
error() { echo -e "${RED}[error]${NC} $1"; exit 1; }

# 빌드 키(api/web/ai-agent/executor/channel)를 운영 docker-compose 서비스명으로 매핑.
# channel 만 서비스명이 'firehub-channel'로 다르다 (이름 mismatch 흡수).
prod_service_name() {
  case "$1" in
    channel) echo "firehub-channel" ;;
    *) echo "$1" ;;
  esac
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

if [ "$TARGET" = "all" ]; then
  # 운영 5개 앱 전부 (channel 포함). 추가/제거 시 deploy.sh 와 docs/deploy.md 도 함께 수정.
  APPS=("api" "executor" "web" "ai-agent" "channel")
else
  APPS=("$TARGET")
fi

cd "$PROD_DIR"

# 1. Pull
log "=== Pull Phase ==="
for app in "${APPS[@]}"; do
  service=$(prod_service_name "$app")
  log "Pulling $app (service=$service)"
  docker compose pull "$service"
done

# 2. Recreate
log "=== Recreate Phase ==="
for app in "${APPS[@]}"; do
  service=$(prod_service_name "$app")
  log "Recreating $app (service=$service)"
  docker compose up -d --force-recreate "$service"
done

# 3. Verify
log "=== Verify Phase ==="
for app in "${APPS[@]}"; do
  verify_app "$app"
done

log "=== Update Complete ==="
