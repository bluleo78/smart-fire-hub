#!/usr/bin/env bash
# Tibero 실서버 테스트 실행기 (WD-74).
#
# ~/.tibero/credentials 의 TIBERO_* 값을 TIBERO_TEST_* 환경변수로 넘겨
# 방언 확인(TiberoDialectProbeTest)과 계약 테스트(TiberoDataStoreContractTest)만 실행한다.
#
# - 자격 증명 값은 어디에도 출력하지 않는다. 절대 `set -x` 를 켜지 말 것.
# - apps/firehub-api/libs/tibero*.jar (Tibero JDBC 드라이버)가 있어야 한다. 없으면 안내 후 종료한다.
# - 출력은 PROBE 줄과 테스트 결과 줄만 걸러서 보여 준다.
#
# Usage: ./scripts/tibero-test.sh
set -euo pipefail
set +x

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CREDENTIALS="${TIBERO_CREDENTIALS_FILE:-$HOME/.tibero/credentials}"
LIBS_DIR="$ROOT_DIR/apps/firehub-api/libs"

if [[ ! -r "$CREDENTIALS" ]]; then
  echo "자격 증명 파일을 읽을 수 없습니다: $CREDENTIALS" >&2
  echo "TIBERO_HOST / TIBERO_PORT / TIBERO_SID / TIBERO_DBA_USER / TIBERO_DBA_PASSWORD 키가 필요합니다." >&2
  exit 1
fi

# 드라이버 jar 확인(없으면 두 클래스가 모두 건너뛰어져 의미 없는 초록이 되므로 여기서 막는다).
shopt -s nullglob
jars=("$LIBS_DIR"/tibero*.jar)
shopt -u nullglob
if [[ ${#jars[@]} -eq 0 ]]; then
  echo "Tibero JDBC 드라이버가 없습니다: $LIBS_DIR/tibero*.jar" >&2
  echo "apps/firehub-api/libs/ 에 tibero7-jdbc*.jar 를 두고 다시 실행하세요(jar 는 커밋하지 않습니다)." >&2
  exit 1
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
# 필터(grep)가 아무것도 못 찾아도 스크립트가 죽지 않게 errexit 를 잠시 끄고, gradle 종료 코드를 보존한다.
set +e
"$ROOT_DIR/apps/firehub-api/gradlew" -p "$ROOT_DIR/apps/firehub-api" test \
  --tests com.smartfirehub.dataset.storage.tibero.TiberoDialectProbeTest \
  --tests com.smartfirehub.dataset.storage.tibero.TiberoDataStoreContractTest \
  -i 2>&1 | grep -E '^[[:space:]]*PROBE |[[:space:]]>[[:space:]].*[[:space:]](PASSED|FAILED|SKIPPED)$|^BUILD (SUCCESSFUL|FAILED)|tests completed'
status=${PIPESTATUS[0]}
exit "$status"
