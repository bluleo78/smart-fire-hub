#!/usr/bin/env bash
# Tibero 실서버 테스트 실행기 (WD-74, WD-77).
#
# ~/.tibero/credentials 의 TIBERO_* 값을 TIBERO_TEST_* 환경변수로 넘겨 다음을 실행한다.
#   api 단계: 방언 확인(TiberoDialectProbeTest)과 계약 테스트(TiberoDataStoreContractTest), 행 트리거·슬롯 GRANT·재생성 보관 실서버 테스트
#   db-executor 단계: SQL 실행·컬럼 탐지 실서버 테스트(TiberoLiveSqlTest, WD-77)와 HTTP 끝-끝 테스트(TiberoLiveHttpTest),
#                     슬롯 스트리밍·REVOKE 즉시 반영(TiberoLiveSlotStreamTest, WD-78)
#
# - 자격 증명 값은 어디에도 출력하지 않는다. 절대 `set -x` 를 켜지 말 것.
# - 각 앱의 libs/tibero*.jar (Tibero JDBC 드라이버)가 있어야 한다. 없으면 안내 후 종료한다.
# - 출력은 PROBE/LIVE 줄과 테스트 결과 줄, 스위트 합계만 걸러서 보여 준다.
#
# Usage: ./scripts/tibero-test.sh [all|api|db-executor]   (기본 all)
set -euo pipefail
set +x

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CREDENTIALS="${TIBERO_CREDENTIALS_FILE:-$HOME/.tibero/credentials}"
STAGE="${1:-all}"
case "$STAGE" in
  all|api|db-executor) ;;
  *)
    echo "단계는 all, api, db-executor 중 하나입니다: $STAGE" >&2
    exit 2
    ;;
esac

if [[ ! -r "$CREDENTIALS" ]]; then
  echo "자격 증명 파일을 읽을 수 없습니다: $CREDENTIALS" >&2
  echo "TIBERO_HOST / TIBERO_PORT / TIBERO_SID / TIBERO_DBA_USER / TIBERO_DBA_PASSWORD 키가 필요합니다." >&2
  exit 1
fi

# 드라이버 jar 확인(없으면 테스트가 모두 건너뛰어져 의미 없는 초록이 되므로 여기서 막는다).
require_driver() {
  local app="$1"
  local libs_dir="$ROOT_DIR/apps/$app/libs"
  local jars
  shopt -s nullglob
  jars=("$libs_dir"/tibero*.jar)
  shopt -u nullglob
  if [[ ${#jars[@]} -eq 0 ]]; then
    echo "Tibero JDBC 드라이버가 없습니다: $libs_dir/tibero*.jar" >&2
    echo "apps/$app/libs/ 에 tibero7-jdbc*.jar 를 두고 다시 실행하세요(jar 는 커밋하지 않습니다)." >&2
    exit 1
  fi
}
if [[ "$STAGE" == all || "$STAGE" == api ]]; then
  require_driver firehub-api
fi
if [[ "$STAGE" == all || "$STAGE" == db-executor ]]; then
  require_driver firehub-db-executor
fi

# 값을 에코하지 않고 읽는다. set -a 로 읽은 키를 모두 export 한다.
set -a
# shellcheck disable=SC1090
source "$CREDENTIALS"
set +a

for key in TIBERO_HOST TIBERO_DBA_USER TIBERO_DBA_PASSWORD; do
  if [[ -z "${!key:-}" ]]; then
    echo "자격 증명 파일에 $key 가 없습니다." >&2
    exit 1
  fi
done

export TIBERO_TEST_HOST="$TIBERO_HOST"
export TIBERO_TEST_PORT="${TIBERO_PORT:-8629}"
export TIBERO_TEST_SID="${TIBERO_SID:-tibero}"
export TIBERO_TEST_DBA_USER="$TIBERO_DBA_USER"
export TIBERO_TEST_DBA_PASSWORD="$TIBERO_DBA_PASSWORD"
if [[ -n "${TIBERO_TABLESPACE:-}" ]]; then
  export TIBERO_TEST_TABLESPACE="$TIBERO_TABLESPACE"
fi

cd "$ROOT_DIR"

# 결과 XML 의 스위트 합계(name/tests/skipped/failures/errors)만 한 줄씩 보여 준다.
print_totals() {
  local report
  for report in "$@"; do
    [[ -r "$report" ]] || continue
    grep -o '<testsuite [^>]*' "$report" | grep -oE ' (name|tests|skipped|failures|errors)="[^"]*"' | tr '\n' ' '
    echo
  done
}

# 필터(grep)가 아무것도 못 찾아도 스크립트가 죽지 않게 errexit 를 잠시 끄고, gradle 종료 코드를 보존한다.
set +e
status=0

if [[ "$STAGE" == all || "$STAGE" == api ]]; then
  echo "== api 단계 (TiberoDialectProbeTest, TiberoDataStoreContractTest, TiberoLiveTriggerTest, TiberoLiveSlotGrantTest, TiberoLiveRebuildTest, TiberoLiveStoreRebuildTest, TiberoDataErrorCodeProbeTest)"
  "$ROOT_DIR/apps/firehub-api/gradlew" -p "$ROOT_DIR/apps/firehub-api" test \
    --tests com.smartfirehub.dataset.storage.tibero.TiberoDialectProbeTest \
    --tests com.smartfirehub.dataset.storage.tibero.TiberoDataStoreContractTest \
    --tests com.smartfirehub.dataset.storage.tibero.TiberoLiveTriggerTest \
    --tests com.smartfirehub.dataset.storage.tibero.TiberoLiveSlotGrantTest \
    --tests com.smartfirehub.dataset.storage.tibero.TiberoLiveRebuildTest \
    --tests com.smartfirehub.dataset.storage.tibero.TiberoLiveStoreRebuildTest \
    --tests com.smartfirehub.dataset.storage.tibero.TiberoDataErrorCodeProbeTest \
    -i 2>&1 | grep -E '^[[:space:]]*PROBE |[[:space:]]>[[:space:]].*[[:space:]](PASSED|FAILED|SKIPPED)$|^BUILD (SUCCESSFUL|FAILED)|tests completed'
  api_status=${PIPESTATUS[0]}
  # 테스트별 PASSED 줄은 gradle 로그 설정에 따라 안 찍힐 수 있으므로, 결과 XML 의 클래스별 합계를 함께 보여 준다.
  print_totals "$ROOT_DIR"/apps/firehub-api/build/test-results/test/TEST-com.smartfirehub.dataset.storage.tibero.Tibero{DialectProbe,DataErrorCodeProbe,DataStoreContract,LiveTrigger,LiveSlotGrant,LiveRebuild,LiveStoreRebuild}Test.xml
  [[ $api_status -ne 0 ]] && status=$api_status
fi

if [[ "$STAGE" == all || "$STAGE" == db-executor ]]; then
  echo "== db-executor 단계 (TiberoLiveSqlTest, TiberoLiveHttpTest, TiberoLiveLoadTest, TiberoLiveMoveTest, TiberoLiveCursorTest, TiberoLiveSlotStreamTest)"
  # --rerun: 입력이 같아도 실서버 테스트를 매번 실제로 돌린다(캐시된 초록 방지).
  "$ROOT_DIR/apps/firehub-db-executor/gradlew" -p "$ROOT_DIR/apps/firehub-db-executor" test \
    --tests com.smartfirehub.dbexecutor.live.TiberoLiveSqlTest \
    --tests com.smartfirehub.dbexecutor.live.TiberoLiveHttpTest \
    --tests com.smartfirehub.dbexecutor.live.TiberoLiveLoadTest \
    --tests com.smartfirehub.dbexecutor.live.TiberoLiveMoveTest \
    --tests com.smartfirehub.dbexecutor.live.TiberoLiveCursorTest \
    --tests com.smartfirehub.dbexecutor.live.TiberoLiveSlotStreamTest --rerun \
    -i 2>&1 | grep -E '^[[:space:]]*LIVE |[[:space:]]>[[:space:]].*[[:space:]](PASSED|FAILED|SKIPPED)$|^BUILD (SUCCESSFUL|FAILED)|tests completed'
  executor_status=${PIPESTATUS[0]}
  print_totals "$ROOT_DIR"/apps/firehub-db-executor/build/test-results/test/TEST-com.smartfirehub.dbexecutor.live.TiberoLive{Sql,Http,Load,Move,Cursor,SlotStream}Test.xml
  [[ $executor_status -ne 0 ]] && status=$executor_status
fi

exit "$status"
