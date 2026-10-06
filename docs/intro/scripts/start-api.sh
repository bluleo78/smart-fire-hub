#!/usr/bin/env bash
# 촬영용 격리 스택의 API 를 띄운다(local 프로필 + 격리 DB 포트로 덮어쓰기).
#
#   JAR=apps/firehub-api/build/libs/smart-fire-hub-0.0.1-SNAPSHOT.jar LOG=<로그> docs/intro/scripts/start-api.sh
#
# 포트: API 5010(웹 vite 프록시가 5010 으로 고정이라 그대로 쓴다) · DB 5452.
# 그래서 촬영 중에는 `pnpm dev` 를 함께 띄우지 않는다(5010·5020 이 겹친다).
# 브랜드명은 고객용 제품 이름(Gen:iA Data)으로 덮는다 — 코드 기본값을 바꾸지 않고 환경변수로만 넣는다.
set -euo pipefail
cd "$(dirname "$0")/../../.."
JAR="${JAR:-apps/firehub-api/build/libs/smart-fire-hub-0.0.1-SNAPSHOT.jar}"
LOG="${LOG:-/tmp/intro-data-api.log}"
WEB_PORT="${INTRO_WEB_PORT:-5273}"
DB_URL="jdbc:postgresql://localhost:${INTRO_DB_PORT:-5452}/smartfirehub"
pkill -f "intro-data-api-marker" 2>/dev/null || true
sleep 1
APP_BRANDING_NAME="Gen:iA Data" \
# JVM 시간대는 운영 컨테이너와 같은 UTC — 로컬(KST) JVM 이면 생성·수정 시각이 화면에서 9시간 밀려 보인다.
nohup java -Dintro-data-api-marker=1 -Duser.timezone=UTC -jar "$JAR" \
  --spring.profiles.active=local \
  --server.port=5010 \
  --spring.datasource.url="$DB_URL" \
  --spring.flyway.url="$DB_URL" \
  --app.pipeline.datasource.url="$DB_URL" \
  --app.base-url="http://localhost:$WEB_PORT" \
  --app.cors.allowed-origins="http://localhost:$WEB_PORT,http://localhost:5020" \
  --org.jobrunr.dashboard.enabled=false \
  >"$LOG" 2>&1 &
# 헬스체크가 아니라 로그인 엔드포인트가 응답할 때까지 기다린다(마이그레이션 132개라 1~2분 걸린다).
for _ in $(seq 1 150); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST localhost:5010/api/v1/auth/login -H 'Content-Type: application/json' -d '{}' || true)
  if [ "$code" != "000" ]; then echo "api up ($code)"; exit 0; fi
  sleep 2
done
echo "api 기동 실패 — $LOG 확인" >&2
exit 1
