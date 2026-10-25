#!/usr/bin/env sh
# pre-push gate logic (#257)
# - push 직전 전체 회귀 안전망 (E2E 전체 + gradle 풀)
# - pre-commit 단계에서 도메인 한정/smoke 로 우회된 spec 들을 풀로 재검증
# - AI 자동화가 여러 커밋을 쌓아 push 할 때 누적 회귀를 차단한다
# - 게이트 전체를 저장소 전역 락 하나로 감싼다 — 워크트리 간 동시 실행이 겹치면 여기서 대기 (WD-257).
#   단계별로 락을 잡으면 사이사이 다른 세션이 끼어들어 한 게이트가 길게 늘어지므로 통째로 잡는다.

set -e

. "$(dirname "$0")/test-lock.sh"

run_locked sh -c '
  set -e
  # web 전체 E2E 는 preview 서버(빌드 결과물 서빙)로 실행한다 — playwright.config.ts 참조.
  # 재시도로 통과한 flaky 를 git common dir 의 누적 로그에 남긴다(모든 워크트리 공유).
  # 실패해도 기록은 남기고 게이트 결과는 그대로 돌려준다.
  FLAKY_LOG="$(cd "$(git rev-parse --git-common-dir)" && pwd)/firehub-e2e-flaky.tsv"
  REPORT_JSON="$(pwd)/test-results/e2e-report.json"
  rm -f "$REPORT_JSON"
  e2e_status=0
  (cd apps/firehub-web && PLAYWRIGHT_JSON_OUTPUT_FILE="$REPORT_JSON" E2E_SERVER=preview pnpm test:e2e --reporter=html,json) || e2e_status=$?
  node scripts/e2e-flaky-record.mjs "$REPORT_JSON" "$FLAKY_LOG" || true
  [ "$e2e_status" -eq 0 ] || exit "$e2e_status"
  cd apps/firehub-api && ./gradlew test -x generateJooq --build-cache --configuration-cache
'
