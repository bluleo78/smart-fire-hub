#!/bin/sh
# 정한 코어에만 CPU 부하를 걸고 Qdrant 시험 두 클래스를 반복 실행한다.
#
# 목적(WD-803 완료 기준 3): 호스트가 과부하일 때도 Qdrant 시험이 안정적으로 통과하는지 확인한다.
#   시험용 Qdrant 컨테이너를 QDRANT_TEST_CPUSET 으로 특정 코어에 묶고(Task 1),
#   같은 코어에 바쁜 루프를 taskset 으로 묶어 Qdrant 가 그 코어에서만 CPU 를 다투게 한다.
#   나머지 코어는 비워 두므로 이 공유 호스트의 다른 작업에 영향을 주지 않는다.
#
# 사용법(저장소 루트 어디서든 실행 가능):
#   scripts/qdrant-load-repeat.sh [반복 수] [코어 목록] [바쁜 루프 수]
#   기본값: 3 0-1 16
#   출력: 회차마다 "run <i>/<n>: PASS|FAIL <초>s load=<1분 load>", 끝에 "summary: <통과>/<n> passed"
#   종료 코드: 0 전부 통과, 1 실패 있음, 2 인자/코어 목록 오류
#   gradle 로그: apps/firehub-api/build/qdrant-load-repeat/run-<i>.log
#
# set -e 를 쓰지 않는다: gradle 이 실패해도 trap 정리와 다음 회차가 이어져야 하기 때문이다.

N="$1"
CORES="$2"
LOOPS="$3"
if [ -z "$N" ]; then
  N=3
fi
if [ -z "$CORES" ]; then
  CORES=0-1
fi
if [ -z "$LOOPS" ]; then
  LOOPS=16
fi

# 코어 목록이 이 호스트에서 유효한지 먼저 확인한다(잘못되면 부하 없이 바로 종료).
if ! ERR=$(taskset -c "$CORES" true 2>&1); then
  echo "invalid core list '$CORES': $ERR" >&2
  exit 2
fi

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
API_DIR="$SCRIPT_DIR/../apps/firehub-api"
cd "$API_DIR" || exit 2
LOG_DIR="build/qdrant-load-repeat"
mkdir -p "$LOG_DIR"

PIDS=""
GRADLE_PID=""

# 바쁜 루프를 모두 끈다. 어떤 경로로 끝나도 부하가 남지 않도록 trap 에서 호출한다.
cleanup() {
  if [ -n "$GRADLE_PID" ]; then
    kill "$GRADLE_PID" 2>/dev/null
  fi
  for p in $PIDS; do
    kill "$p" 2>/dev/null
  done
  PIDS=""
}

# INT/TERM 은 정리 후 종료 코드를 정해 끝내고, EXIT 는 정상 종료 때 정리만 한다.
on_signal() {
  cleanup
  exit 130
}
trap cleanup EXIT
trap on_signal INT TERM

# 정한 코어에만 묶은 바쁜 루프를 시작한다.
i=0
while [ "$i" -lt "$LOOPS" ]; do
  taskset -c "$CORES" sh -c 'while :; do :; done' &
  PIDS="$PIDS $!"
  i=$((i + 1))
done

PASSED=0
run=1
while [ "$run" -le "$N" ]; do
  START=$(date +%s)
  QDRANT_TEST_CPUSET="$CORES" ./gradlew test --rerun-tasks \
    --tests '*QdrantVectorStoreLifecycleTest' \
    --tests '*QdrantRowSearchServiceTest' \
    > "$LOG_DIR/run-$run.log" 2>&1 &
  GRADLE_PID=$!
  # 백그라운드로 띄우고 wait 해야 신호가 와도 trap 이 즉시 실행된다(전경 실행이면 gradle 이 끝날 때까지 지연됨).
  wait "$GRADLE_PID"
  RC=$?
  END=$(date +%s)
  LOAD=$(cut -d ' ' -f 1 /proc/loadavg)
  if [ "$RC" -eq 0 ]; then
    RESULT=PASS
    PASSED=$((PASSED + 1))
  else
    RESULT=FAIL
  fi
  echo "run $run/$N: $RESULT $((END - START))s load=$LOAD"
  run=$((run + 1))
done

echo "summary: $PASSED/$N passed"
if [ "$PASSED" -ne "$N" ]; then
  exit 1
fi
exit 0
