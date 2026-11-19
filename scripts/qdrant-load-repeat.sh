#!/bin/sh
# WD-803 완료 기준 3: 호스트가 과부하일 때도 Qdrant 시험 두 클래스가 안정적으로 통과하는지 반복 확인한다.
#
# 사용법: scripts/qdrant-load-repeat.sh [반복 수] [코어 목록] [부하 루프 수] [부하 shares]
#         scripts/qdrant-load-repeat.sh --print-image   (부하 이미지 태그만 출력)
#   기본값: 3 0-1 (고정 코어 수) 26214.  예: scripts/qdrant-load-repeat.sh 3 14-15
#   코어 목록은 "0-3,8" 형식만 허용한다("0-7:2" 같은 stride 는 거부).
#
# 부하: Qdrant 시험 컨테이너(QDRANT_TEST_CPUSET)와 같은 코어에 묶은 바쁜 컨테이너 하나를 Docker cgroup
#   안에서 띄운다(호스트 프로세스 부하는 cgroup 공정 분배 때문에 경합이 재현되지 않는다).
#   shares 는 cgroup v2 weight 로 환산되며(26214 -> 약 1000) 기본 Qdrant 컨테이너(100)의 코어 몫은 약 9% 다.
#   이미지는 QdrantTestContainer.java 의 IMAGE 상수를 재사용한다(로컬에 없으면 pull 하지 않고 종료 3).
# 출력: "run <i>/<n>: PASS|FAIL <초>s load=<시작→끝>"(/proc/loadavg 1분 평균), "summary: <통과>/<n> passed"
# 종료 코드: 0 전부 통과, 1 실패 있음, 2 인자·이미지 상수 오류, 3 docker 실패(부하 컨테이너 시작 실패·
#   이미지 없음·부하 중단), 129 HUP, 130 INT, 143 TERM
# SIGKILL 로 죽은 실행의 잔여 부하 컨테이너: docker ps --filter label=wd803-load 로 확인 후 docker rm -f <id>
# gradle 로그: apps/firehub-api/build/qdrant-load-repeat/run-<i>.log
# set -e 를 쓰지 않는다: gradle 이 실패해도 trap 정리와 다음 회차가 이어져야 한다.

# 이미지 태그는 시험 코드의 IMAGE 상수에서 읽는다(하드코딩하면 버전이 어긋난다).
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
API_DIR="$SCRIPT_DIR/../apps/firehub-api"
CONTAINER_JAVA="$API_DIR/src/test/java/com/smartfirehub/vectorstore/qdrant/QdrantTestContainer.java"
LOAD_IMAGE=$(sed -n 's/^.*String IMAGE = "\([^"]*\)";.*$/\1/p' "$CONTAINER_JAVA" 2>/dev/null | head -n 1)
if [ -z "$LOAD_IMAGE" ]; then
  echo "QdrantTestContainer.java 에서 IMAGE 상수를 찾지 못했다: $CONTAINER_JAVA" >&2
  exit 2
fi
if [ "$1" = "--print-image" ]; then
  echo "$LOAD_IMAGE"
  exit 0
fi
LOAD_LABEL="wd803-load=$$"

# 정수 인자 검사: check_range 이름 값 최소 [최대]. 어긋나면 부하·gradle 시작 전에 종료 2.
check_range() {
  case "$2" in
    ''|*[!0-9]*)
      echo "invalid $1 '$2': 양의 정수여야 한다" >&2
      exit 2
      ;;
  esac
  if [ "$2" -lt "$3" ]; then
    echo "invalid $1 '$2': $3 이상이어야 한다" >&2
    exit 2
  fi
  if [ -n "$4" ] && [ "$2" -gt "$4" ]; then
    echo "invalid $1 '$2': $3~$4 범위여야 한다" >&2
    exit 2
  fi
}

# 코어 수를 센다. "0-3,8" 같은 목록도 센다.
count_cores() {
  total=0
  for part in $(echo "$1" | tr ',' ' '); do
    case "$part" in
      *-*)
        lo=${part%-*}
        hi=${part#*-}
        total=$((total + hi - lo + 1))
        ;;
      *)
        total=$((total + 1))
        ;;
    esac
  done
  echo "$total"
}

N=${1:-3}
CORES=${2:-0-1}
check_range "반복 수" "$N" 1

# stride(:) 는 코어 수 계산이 틀어지므로 거부한다.
case "$CORES" in
  *:*)
    echo "invalid core list '$CORES': ':'(stride) 는 지원하지 않는다" >&2
    exit 2
    ;;
esac

# 코어 목록이 이 호스트에서 유효한지 먼저 확인한다(잘못되면 부하 없이 바로 종료).
if ! ERR=$(taskset -c "$CORES" true 2>&1); then
  echo "invalid core list '$CORES': $ERR" >&2
  exit 2
fi

# 루프 수 기본값은 고정 코어 수다(높은 weight 에서는 코어당 바쁜 루프 하나면 충분하다).
LOOPS=${3:-$(count_cores "$CORES")}
WEIGHT=${4:-26214}
check_range "부하 루프 수" "$LOOPS" 1
check_range "부하 shares" "$WEIGHT" 2 262144

cd "$API_DIR" || exit 2
LOG_DIR="build/qdrant-load-repeat"
mkdir -p "$LOG_DIR"

LOAD_CID=""
GRADLE_PID=""

# gradle 과 이 실행의 부하 컨테이너(다른 실행의 것은 건드리지 않음)를 끈다.
# EXIT trap 과 on_signal 에서 두 번 불려도 무해하도록 끝낸 뒤 변수를 비운다.
cleanup() {
  if [ -n "$GRADLE_PID" ]; then
    kill "$GRADLE_PID" 2>/dev/null
    GRADLE_PID=""
  fi
  if [ -n "$LOAD_CID" ]; then
    docker rm -f "$LOAD_CID" >/dev/null 2>&1
    LOAD_CID=""
  fi
}

# 신호는 정리 후 신호별 종료 코드로 끝낸다. EXIT 는 정상 종료 때 정리만 한다.
on_signal() {
  cleanup
  exit "$1"
}
trap cleanup EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM
trap 'on_signal 129' HUP

# 이미지는 로컬에 있어야 한다(pull 하지 않는다).
if ! docker image inspect "$LOAD_IMAGE" >/dev/null 2>&1; then
  echo "이미지 $LOAD_IMAGE 가 로컬에 없다(pull 하지 않음). 시험을 한 번 돌려 받아 두라" >&2
  exit 3
fi

# 부하 컨테이너가 살아 있는지 본다. 죽었으면 부하 없는 PASS 를 막으려고 종료 3.
check_load_alive() {
  RUNNING=$(docker inspect -f '{{.State.Running}}' "$LOAD_CID" 2>/dev/null)
  if [ "$RUNNING" != "true" ]; then
    echo "부하 컨테이너가 살아 있지 않다($1): $LOAD_CID - 부하 없이는 결과를 내지 않는다" >&2
    exit 3
  fi
}

# 정한 코어에 묶은 바쁜 컨테이너 하나를 띄운다. 안에서 바쁜 루프를 LOOPS 개 돌린다.
BUSY_CMD="i=0
while [ \$i -lt $LOOPS ]; do
  (while :; do :; done) &
  i=\$((i+1))
done
wait"
LOAD_CID=$(docker run -d --rm --label "$LOAD_LABEL" --cpuset-cpus "$CORES" \
  --cpu-shares "$WEIGHT" --entrypoint sh "$LOAD_IMAGE" -c "$BUSY_CMD")
if [ -z "$LOAD_CID" ]; then
  echo "부하 컨테이너를 시작하지 못했다" >&2
  exit 3
fi

PASSED=0
run=1
while [ "$run" -le "$N" ]; do
  check_load_alive "회차 $run 시작 전"
  START=$(date +%s)
  LOAD_START=$(cut -d ' ' -f 1 /proc/loadavg)
  # --rerun 은 시험 태스크만 다시 돌린다(--rerun-tasks 는 컴파일·코드생성까지 호스트를 다시 부하시킴).
  FIREHUB_TEST_FORKS=1 QDRANT_TEST_CPUSET="$CORES" ./gradlew test --rerun \
    --tests '*QdrantVectorStoreLifecycleTest' \
    --tests '*QdrantRowSearchServiceTest' \
    > "$LOG_DIR/run-$run.log" 2>&1 &
  GRADLE_PID=$!
  # 백그라운드로 띄우고 wait 해야 신호가 와도 trap 이 즉시 실행된다(전경 실행이면 gradle 이 끝날 때까지 지연됨).
  wait "$GRADLE_PID"
  RC=$?
  GRADLE_PID=""
  END=$(date +%s)
  LOAD_END=$(cut -d ' ' -f 1 /proc/loadavg)
  # 회차 도중 부하가 죽었다면 그 회차 결과는 믿을 수 없으므로 출력 없이 종료 3.
  check_load_alive "회차 $run 종료 후"
  if [ "$RC" -eq 0 ]; then
    RESULT=PASS
    PASSED=$((PASSED + 1))
  else
    RESULT=FAIL
  fi
  echo "run $run/$N: $RESULT $((END - START))s load=$LOAD_START→$LOAD_END"
  run=$((run + 1))
done

echo "summary: $PASSED/$N passed"
if [ "$PASSED" -ne "$N" ]; then
  exit 1
fi
exit 0
