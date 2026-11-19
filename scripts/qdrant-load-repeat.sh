#!/bin/sh
# 정한 코어에 CPU 부하를 걸고 Qdrant 시험 두 클래스를 반복 실행한다.
#
# 목적(WD-803 완료 기준 3): 호스트가 과부하일 때도 Qdrant 시험이 안정적으로 통과하는지 확인한다.
#   시험용 Qdrant 컨테이너를 QDRANT_TEST_CPUSET 으로 특정 코어에 묶고(Task 1),
#   같은 코어에 묶은 "바쁜 컨테이너" 하나를 띄워 Qdrant 가 그 코어에서 CPU 를 다투게 한다.
#   부하를 호스트 프로세스(taskset)가 아닌 컨테이너로 거는 이유: cgroup v2 의 CPU 공정 분배는
#   cgroup 단위라서, Docker 밖의 루프는 Qdrant cgroup 과 다른 그룹이 되어 루프를 아무리 늘려도
#   Qdrant 가 코어의 절반가량을 항상 확보한다(부하가 한정되어 경합이 재현되지 않을 수 있음).
#   Qdrant 와 같은 Docker cgroup 계층에서 높은 cpu-shares 로 돌리면 실제로 코어를 빼앗는다.
#   정한 코어 밖의 다른 코어 영향은 적다(없다고 단정할 수는 없음 - 호스트 스케줄러·IO 는 공유).
#
# 사용법(저장소 루트 어디서든 실행 가능):
#   scripts/qdrant-load-repeat.sh [반복 수] [코어 목록] [부하 루프 수] [부하 shares]
#   scripts/qdrant-load-repeat.sh --print-image      (이미지 태그 추출 결과만 출력하고 끝냄)
#   기본값: 3 0-1 (코어 수 x 4) 26214
#   예: scripts/qdrant-load-repeat.sh 3 14-15   (공유 호스트에서는 번호가 큰 코어 한 쌍을 권한다)
#   코어 목록: "0-3,8" 형식만 허용한다. "0-7:2" 같은 stride(:) 는 코어 수를 셀 수 없어 거부(종료 2).
#   부하 루프 수: 바쁜 컨테이너 안에서 도는 루프 수. 고정 코어 수보다 많아야 코어를 모두 쓴다.
#   부하 shares: 바쁜 컨테이너의 docker --cpu-shares (2~262144).
#     cgroup v2 에서 Docker 는 shares 를 cpu.weight 로 환산한다:
#       weight = 1 + (shares - 2) * 9999 / 262142   (예: 26214 -> 1000, 10240 -> 391)
#     Docker 기본 컨테이너(Qdrant 시험 컨테이너, shares 미지정)는 weight 100(shares 를 안 주면 cgroup 기본 weight 100 이 그대로 쓰인다).
#     기본 26214 -> weight 약 1000 이므로 같은 코어에서 Qdrant 몫은 100/(100+1000) 약 9%(10% 이하)다.
#     (참고: 10240 -> weight 약 391 이라 Qdrant 몫이 약 20% 로 부하가 약하다.)
#   부하 컨테이너 이미지: QdrantTestContainer.java 의 IMAGE 상수를 읽어 --entrypoint sh 로 재사용한다
#     (sh 포함, 새로 내려받지 않기 위함). 못 찾으면 종료 2, 로컬에 없으면 종료 3(pull 하지 않음).
#   포크 수: gradle 을 FIREHUB_TEST_FORKS=1 로 돌린다(apps/firehub-api/build.gradle.kts 가 환경변수로
#     병렬 포크 수를 정함, 기본 2). 포크마다 Qdrant 컨테이너가 따로 떠서 한 컨테이너만 정한 코어에 묶이게 하려는 것이다.
#   출력: 회차마다 "run <i>/<n>: PASS|FAIL <초>s load=<시작→끝>", 끝에 "summary: <통과>/<n> passed"
#     load= 는 회차 시작·끝에 읽은 호스트 /proc/loadavg 1분 평균이며 이 스크립트가 거는 부하도 포함한다.
#   종료 코드: 0 전부 통과, 1 실패 있음, 2 인자/코어 목록/이미지 상수 오류,
#     3 docker 실패(부하 컨테이너 시작 실패·이미지 없음·회차 중 부하 컨테이너 종료 - 부하 없이는 PASS 를 내지 않음),
#     129 HUP, 130 INT, 143 TERM
#   gradle 로그: apps/firehub-api/build/qdrant-load-repeat/run-<i>.log
#
# 정리: 이 실행의 부하 컨테이너(라벨 wd803-load=<이 스크립트 PID>)만 종료 경로마다 docker rm -f 한다.
#   시작 시 다른 실행의 컨테이너는 건드리지 않는다(동시에 도는 다른 증거 실행 보호).
#   SIGKILL 로 죽은 실행이 남긴 컨테이너는 직접 확인 후 지운다:
#     docker ps --filter label=wd803-load          (남은 것 확인)
#     docker rm -f <확인한 컨테이너 id>
#   gradle 클라이언트를 kill 하면 gradle 데몬의 취소와 Testcontainers Ryuk 이
#   시험 Qdrant 컨테이너를 정리해 준다는 점에 기대고 있다(직접 지우지 않음).
#
# set -e 를 쓰지 않는다: gradle 이 실패해도 trap 정리와 다음 회차가 이어져야 하기 때문이다.

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

N="$1"
CORES="$2"
LOOPS="$3"
WEIGHT="$4"
if [ -z "$N" ]; then
  N=3
fi
if [ -z "$CORES" ]; then
  CORES=0-1
fi

# 양의 정수가 아닌 인자는 바로 거부한다(부하·gradle 시작 전).
check_positive() {
  case "$2" in
    ''|*[!0-9]*)
      echo "invalid $1 '$2': 양의 정수여야 한다" >&2
      exit 2
      ;;
  esac
  if [ "$2" -lt 1 ]; then
    echo "invalid $1 '$2': 1 이상이어야 한다" >&2
    exit 2
  fi
}
check_positive "반복 수" "$N"

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

# 코어 수를 세어 루프 수 기본값(코어 수 x 4)을 정한다. "0-3,8" 같은 목록도 센다.
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
if [ -z "$LOOPS" ]; then
  LOOPS=$(( $(count_cores "$CORES") * 4 ))
fi
if [ -z "$WEIGHT" ]; then
  WEIGHT=26214
fi
check_positive "부하 루프 수" "$LOOPS"
check_positive "부하 shares" "$WEIGHT"
if [ "$WEIGHT" -lt 2 ] || [ "$WEIGHT" -gt 262144 ]; then
  echo "invalid 부하 shares '$WEIGHT': 2~262144 범위여야 한다" >&2
  exit 2
fi

cd "$API_DIR" || exit 2
LOG_DIR="build/qdrant-load-repeat"
mkdir -p "$LOG_DIR"

LOAD_CID=""
GRADLE_PID=""

# 이 실행의 부하 컨테이너만 지운다(다른 실행의 컨테이너는 건드리지 않는다).
remove_load_container() {
  if [ -n "$LOAD_CID" ]; then
    docker rm -f "$LOAD_CID" >/dev/null 2>&1
  fi
}

# gradle 과 부하 컨테이너를 모두 끈다. 어떤 경로로 끝나도 부하가 남지 않도록 trap 에서 호출한다.
cleanup() {
  if [ -n "$GRADLE_PID" ]; then
    kill "$GRADLE_PID" 2>/dev/null
    GRADLE_PID=""
  fi
  remove_load_container
  LOAD_CID=""
}

# INT/TERM/HUP 은 정리 후 신호별 종료 코드로 끝내고, EXIT 는 정상 종료 때 정리만 한다.
on_int() {
  cleanup
  exit 130
}
on_term() {
  cleanup
  exit 143
}
on_hup() {
  cleanup
  exit 129
}
trap cleanup EXIT
trap on_int INT
trap on_term TERM
trap on_hup HUP

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

# 정한 코어에 묶은 바쁜 컨테이너 하나를 띄운다. 안에서 바쁜 루프를 LOOPS 개 돌려 모든 코어를 쓴다.
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
