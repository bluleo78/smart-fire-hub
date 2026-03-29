#!/usr/bin/env bash
set -euo pipefail

# Smart Fire Hub 원격 업데이트 스크립트
# ghcr.io에서 최신 이미지를 pull하고 컨테이너를 재생성한다.
# Usage: ./scripts/update.sh [api|web|ai-agent|executor|all]

PROD_DIR="$HOME/prod/smart-fire-hub"

# 색상
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[update]${NC} $1"; }
warn() { echo -e "${YELLOW}[warn]${NC} $1"; }
error() { echo -e "${RED}[error]${NC} $1"; exit 1; }

verify_app() {
  local app=$1
  log "Verifying $app container..."
  sleep 10
  local status
  status=$(cd "$PROD_DIR" && docker compose ps "$app" --format '{{.Status}}' 2>/dev/null)
  if echo "$status" | grep -qi "up\|running"; then
    log "$app is running: $status"
  else
    error "$app failed to start: $status"
  fi
}

# --- Main ---

TARGET=${1:-all}

if [ "$TARGET" = "all" ]; then
  APPS=("api" "executor" "web" "ai-agent")
else
  APPS=("$TARGET")
fi

cd "$PROD_DIR"

# 1. Pull
log "=== Pull Phase ==="
for app in "${APPS[@]}"; do
  log "Pulling $app"
  docker compose pull "$app"
done

# 2. Recreate
log "=== Recreate Phase ==="
for app in "${APPS[@]}"; do
  log "Recreating $app"
  docker compose up -d --force-recreate "$app"
done

# 3. Verify
log "=== Verify Phase ==="
for app in "${APPS[@]}"; do
  verify_app "$app"
done

log "=== Update Complete ==="
