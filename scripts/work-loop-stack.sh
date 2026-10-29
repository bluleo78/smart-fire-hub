#!/usr/bin/env bash
# =============================================================================
# work-loop 격리 라이브 스택
#
# 사용법:
#   LIVE_STACK_NAME=work-loop scripts/work-loop-stack.sh up|down|status|seed|logs [옵션] [워크트리 경로]
#   scripts/work-loop-stack.sh shared-down [--force] [--keep-data]     (사람용 — 공유 바탕 내리기)
#
#   up      : 공유 바탕(PostgreSQL+MinIO, 컴퓨터에 하나)을 없으면 띄우고 → 내 DB·버킷을 만들고 →
#             (선택 Neo4j / --own-db) → api(깨끗한 빌드 jar) → ai-agent → web(→ admin) 을 빈 포트로 기동한 뒤
#             테스트 계정을 시드한다. 이미 떠 있으면 실패한다(먼저 down).
#   down    : 상태 파일에 적힌 프로세스 그룹, 내 DB·버킷, (있으면) 내 compose project 만 정리한다.
#             기본은 데이터까지 삭제(다음 up 은 빈 DB + 전체 Flyway). --keep-data 면 DB·버킷·볼륨 유지.
#             공유 바탕 컨테이너는 어떤 down 도 내리지 않는다.
#   status  : 내 스택 서비스별 pid·포트·헬스 + 공유 바탕 헬스 + 공유 서버의 다른 저장소 DB·버킷(읽기만).
#             내 스택이 하나라도 비정상이면 종료코드 1.
#   seed    : 테스트 계정을 만든다(공개 가입이 열려 있을 때=사용자 0명) 그리고 로그인으로 확인한다.
#   logs    : 로그 파일 경로를 출력한다.
#   shared-down : 공유 바탕을 내린다. 저장소 DB(smartfirehub_*)·버킷(firehub-*)이 남아 있으면 목록을 보이고 거부한다.
#                 --force 일 때만 내린다(기본은 볼륨까지 삭제, --keep-data 면 유지).
#
# 옵션(환경변수로도 가능):
#   --with-admin  / WL_WITH_ADMIN=1  : firehub-admin dev 서버도 띄운다.
#   --with-neo4j  / WL_WITH_NEO4J=1  : Neo4j(GraphRAG) 컨테이너도 띄운다(저장소마다 따로).
#   --own-db      / WL_OWN_DB=1      : 공유 PostgreSQL 대신 이 저장소 전용 PostgreSQL 컨테이너를 띄운다(탈출구 —
#                                      공유 서버가 고장 났거나, 클러스터 전역(롤·ALTER SYSTEM)을 바꾸는 검증일 때.
#                                      이미지는 공유와 같은 work-loop-firehub-postgres:local 이라 Dockerfile 변경은 반영되지 않는다).
#   --skip-build  / WL_SKIP_BUILD=1  : api jar·ai-agent dist 재빌드를 건너뛴다(직전 빌드 재사용 — 코드가 바뀌었으면 쓰지 말 것).
#   --keep-data   / WL_KEEP_DATA=1   : down 시 DB·버킷·볼륨을 남긴다.
#   --force       / WL_FORCE=1       : shared-down 에서 남은 DB·버킷이 있어도 내린다.
#
# 이름(STACK_ID):
#   STACK_ID = $LIVE_STACK_NAME-<메인 저장소 절대경로 sha1 앞 8자> — 같은 LIVE_STACK_NAME 을 쓰는 여러 클론이 섞이지 않게 한다.
#   내 DB = smartfirehub_<STACK_ID sha1 앞 8자>, 내 버킷 = firehub-<같은 8자>, (--own-db·neo4j) compose project = $STACK_ID.
#   up 이 stack.env 에 저장하고 down·status·seed·logs 는 그 값을 쓴다.
#
# 포트:
#   공유 바탕(고정): Postgres 6432, MinIO S3 6900·콘솔 6901 — 이 포트를 우리 공유 컨테이너가 아닌 것이 쓰면 실패한다.
#   내 서비스(up 이 고름): api 6010·ai-agent 6020·web 6173·admin 6050·(--own-db) DB 6433·neo4j 6474/6687 부터 +1 씩
#   올려 빈 포트를 고른다(lsof·ss·docker 공개 포트·다른 스택 예약을 본다). 고른 포트는 stack.env 에 저장된다.
#   비어 있어도 고르지 않는 포트: dev(5010 5020 5030 5040 5050 5051 5173 5432 5433 9000 9001 7474 7687),
#   로컬 운영(8888 8889), Orca(6768), 공유 Postgres·MinIO 포트.
#   WL_API_PORT·WL_AGENT_PORT·WL_WEB_PORT·WL_ADMIN_PORT·WL_DB_PORT·WL_NEO4J_HTTP_PORT·WL_NEO4J_BOLT_PORT 를 직접 주면
#   그 값을 그대로 쓰고, 쓰는 중이면 아무것도 죽이지 않고 실패한다.
#   시험용 공유 바탕: WL_SHARED_PROJECT(기본 work-loop-shared)·WL_SHARED_PG_PORT·WL_SHARED_S3_PORT·WL_SHARED_CONSOLE_PORT.
#
# 격리 원칙:
#   - 남의 포트·컨테이너를 죽이지 않는다(기존 pnpm dev 의 `lsof | kill -9` 와 다르다). 쓰이면 실패로 알린다.
#   - 컴퓨터 공용 락 두 개(mkdir 원자성, /tmp 고정 — $TMPDIR 은 세션마다 다를 수 있다):
#       공용 락   /tmp/<공유 project>-firehub.lock         : 포트 고르기·예약, 공유 바탕 확인/기동, 내 DB 만들기(몇 초~수십 초)
#       기동 락   /tmp/<공유 project>-firehub.startup.lock : 공유 Postgres 모드의 api 기동~준비. api 기동(Flyway·RolePasswordSyncCallback)은
#                 클러스터 전역 롤 카탈로그를 쓰고 앱의 advisory lock 은 DB 단위라, 두 클론이 겹치면 23505·tuple concurrently updated 로 실패한다.
#     주인 pid 가 살아 있으면 빼앗지 않는다. 주인이 죽었거나 WL_LOCK_STALE_SECS(기본 30분)가 지났을 때만 치우고,
#     대기자는 WL_LOCK_WAIT_SECS(기본 15분) 뒤 주인 pid·나이를 알리며 실패한다.
#   - 소유 기록: up 이 만든(CREATED_*)·재사용한(REUSED_*) DB·버킷·전용 볼륨을 stack.env 에 적고, down 은 그것만 지운다.
#     --keep-data 로 down 하면 keep-data 표식을 남기고 다음 up 이 재사용한다. 표식은 up 이 성공해야 지워지므로, 기동 실패 정리는
#     재사용한 것을 지우지 않는다. 표식 없이 남은 DB·버킷은 up 이 지우고 새로 만든다.
#   - 상태 파일은 <메인 저장소>/.work-loop/state/stack-$LIVE_STACK_NAME/ 에만 쓴다.
#   - 옛 이름 compose project($LIVE_STACK_NAME, 예: work-loop)는 지우지 않는다 — 남아 있으면 경고·정리 방법만 알린다.
# 시험: WL_STACK_LIB=1 로 이 파일을 source 하면 명령을 실행하지 않고 함수만 정의한다.
# =============================================================================
set -Eeuo pipefail  # -E: 함수 안 실패에도 ERR 트랩(기동 실패 시 정리)이 걸리게 한다

# ---------------------------------------------------------------------------
# 인자 파싱
# ---------------------------------------------------------------------------
CMD="${1:-}"
[ $# -gt 0 ] && shift
WORKTREE_ARG=""
for arg in "$@"; do
  case "$arg" in
    --with-admin) WL_WITH_ADMIN=1 ;;
    --with-neo4j) WL_WITH_NEO4J=1 ;;
    --own-db)     WL_OWN_DB=1 ;;
    --skip-build) WL_SKIP_BUILD=1 ;;
    --keep-data)  WL_KEEP_DATA=1 ;;
    --force)      WL_FORCE=1 ;;
    -*) echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
    *) WORKTREE_ARG="$arg" ;;
  esac
done

# 공유 바탕 설정 — 기본값은 컴퓨터 공용. 시험할 때만 바꿔 진짜 공유 바탕과 섞이지 않게 한다(락·예약 경로도 project 이름을 따른다).
WL_SHARED_PROJECT="${WL_SHARED_PROJECT:-work-loop-shared}"
WL_SHARED_PG_PORT="${WL_SHARED_PG_PORT:-6432}"
WL_SHARED_S3_PORT="${WL_SHARED_S3_PORT:-6900}"
WL_SHARED_CONSOLE_PORT="${WL_SHARED_CONSOLE_PORT:-6901}"
export WL_SHARED_PG_PORT WL_SHARED_S3_PORT WL_SHARED_CONSOLE_PORT
if ! [[ "$WL_SHARED_PROJECT" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
  echo "WL_SHARED_PROJECT 는 소문자·숫자·-·_ 만 허용: $WL_SHARED_PROJECT" >&2
  exit 2
fi
# 컴퓨터 공용 락·포트 예약 경로 — 공유 project 이름에서 만든다(상태 파일을 읽어 project 가 바뀌면 다시 부른다). 시험은 WL_SHARED_LOCK 등으로 바꾼다.
shared_paths() {
  SHARED_LOCK="${WL_SHARED_LOCK:-/tmp/${WL_SHARED_PROJECT}-firehub.lock}"
  STARTUP_LOCK="${WL_STARTUP_LOCK:-/tmp/${WL_SHARED_PROJECT}-firehub.startup.lock}"
  SHARED_PORTS_DIR="${WL_SHARED_PORTS_DIR:-/tmp/${WL_SHARED_PROJECT}-firehub.ports}"
}
shared_paths

if [ "$CMD" != shared-down ]; then
  if [ -z "${LIVE_STACK_NAME:-}" ]; then
    echo "LIVE_STACK_NAME 이 필요하다 (예: LIVE_STACK_NAME=work-loop $0 up)" >&2
    exit 2
  fi
  # compose project 이름 규칙(소문자·숫자·-·_)에 맞는지 확인 — 상태 디렉터리 이름에도 쓰이므로 엄격히 검사한다.
  if ! [[ "$LIVE_STACK_NAME" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
    echo "LIVE_STACK_NAME 은 소문자·숫자·-·_ 만 허용: $LIVE_STACK_NAME" >&2
    exit 2
  fi
  # 공유 바탕 project 와 같은 이름이면 「옛 이름 project 경고」가 공유 바탕을 가리키게 된다 — 막는다.
  if [ "$LIVE_STACK_NAME" = "$WL_SHARED_PROJECT" ]; then
    echo "LIVE_STACK_NAME 은 공유 바탕 project 이름($WL_SHARED_PROJECT)과 같을 수 없다" >&2
    exit 2
  fi
fi
LIVE_STACK_NAME="${LIVE_STACK_NAME:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 워크트리 경로: 인자 > 스크립트가 속한 저장소 루트
WORKTREE="$(cd "${WORKTREE_ARG:-$SCRIPT_DIR/..}" && pwd)"
[ -d "$WORKTREE/apps/firehub-api" ] || { echo "firehub 저장소가 아니다: $WORKTREE" >&2; exit 2; }
# 공유 바탕 compose 파일은 실행 중인 스크립트의 저장소 것을 쓴다(공유 바탕은 어느 워크트리에도 속하지 않는다).
SHARED_COMPOSE_FILE="$SCRIPT_DIR/work-loop/compose.shared.yml"

# 메인 저장소(공통 .git 의 부모) — 상태 파일은 워크트리가 아니라 여기에 둔다(워크트리 삭제와 무관하게 down 가능).
GIT_COMMON_DIR="$(git -C "$WORKTREE" rev-parse --path-format=absolute --git-common-dir)"
MAIN_REPO="$(dirname "$GIT_COMMON_DIR")"
STATE_DIR="$MAIN_REPO/.work-loop/state/stack-$LIVE_STACK_NAME"
LOG_DIR="$STATE_DIR/logs"
STATE_FILE="$STATE_DIR/stack.env"

# 문자열의 sha1 앞 8자(macOS 는 shasum, Linux 는 sha1sum, 둘 다 없으면 openssl)
sha8() {
  if command -v shasum >/dev/null 2>&1; then printf '%s' "$1" | shasum -a 1 | cut -c1-8
  elif command -v sha1sum >/dev/null 2>&1; then printf '%s' "$1" | sha1sum | cut -c1-8
  else printf '%s' "$1" | openssl sha1 -r | cut -c1-8
  fi
}

# 이 저장소 스택의 이름들. 상태 파일이 있으면 load_state 가 저장된 값으로 덮는다(down·status 는 저장된 값을 쓴다).
# DB·버킷 이름은 STACK_ID 전체의 해시 — 같은 클론에서 LIVE_STACK_NAME 만 다른 두 스택도 갈리게 한다.
STACK_ID="$LIVE_STACK_NAME-$(sha8 "$MAIN_REPO")"
STACK_HASH="$(sha8 "$STACK_ID")"
WL_DB_NAME="smartfirehub_$STACK_HASH"
WL_BUCKET="firehub-$STACK_HASH"

# 직접 준 포트(WL_*_PORT)는 고르지 않고 그대로 쓴다 — 기본값을 채우기 전에 무엇을 직접 줬는지 기억한다.
WL_EXPLICIT_PORTS=""
for _v in WL_API_PORT WL_AGENT_PORT WL_WEB_PORT WL_ADMIN_PORT WL_DB_PORT WL_NEO4J_HTTP_PORT WL_NEO4J_BOLT_PORT; do
  [ -n "${!_v:-}" ] && WL_EXPLICIT_PORTS="$WL_EXPLICIT_PORTS $_v "
  # 안 준 포트는 빈 값으로 둔다 — 상태 파일이 없거나 덜 써진 채 status·seed 를 불러도 set -u 로 죽지 않게(up 이 고른 값은 load_state 가 채운다).
  printf -v "$_v" '%s' "${!_v:-}"
done
unset _v

# 고르기 시작 포트(기본값). --own-db 의 기본 6432 는 공유 Postgres 포트라 금지 목록에 걸려 6433 부터가 된다.
port_default() {
  case "$1" in
    WL_API_PORT) echo 6010 ;;
    WL_AGENT_PORT) echo 6020 ;;
    WL_WEB_PORT) echo 6173 ;;
    WL_ADMIN_PORT) echo 6050 ;;
    WL_DB_PORT) echo 6432 ;;
    WL_NEO4J_HTTP_PORT) echo 6474 ;;
    WL_NEO4J_BOLT_PORT) echo 6687 ;;
  esac
}

# 비어 있어도 고르지 않는 포트: dev 스택·dev DB/MinIO/Neo4j·로컬 운영 배포·Orca, 그리고 공유 바탕 포트.
# 공유 바탕 기본 포트(6432 6900 6901)는 시험용으로 WL_SHARED_*_PORT 를 바꿨을 때도 뺀다 — 진짜 공유 바탕이 나중에 뜰 자리를 남의 것이 잡지 않게.
WL_FORBIDDEN_PORTS="5010 5020 5030 5040 5050 5051 5173 5432 5433 9000 9001 7474 7687 8888 8889 6768 6432 6900 6901 \
$WL_SHARED_PG_PORT $WL_SHARED_S3_PORT $WL_SHARED_CONSOLE_PORT"

# 테스트 계정 — 빈 격리 DB 의 첫 가입자는 시스템 ADMIN 이 된다(SignupTransaction).
WL_TEST_USER="${WL_TEST_USER:-workloop@example.com}"
WL_TEST_PASSWORD="${WL_TEST_PASSWORD:-WorkLoop1234}"
WL_TEST_NAME="${WL_TEST_NAME:-Work Loop}"

# 공유 PostgreSQL 은 롤이 클러스터 전역이다. 저장소마다 비밀번호 설정이 다르면 서로의 롤 비밀번호를 덮어써 상대 스택의
# 접속·PYTHON 스텝이 인증 실패로 멈춘다. 그래서 롤 비밀번호에 닿는 설정을 모두 여기서 고정해 명령행으로 준다
# (PIPELINE_ROLE_PASSWORD_SECRET 같은 환경변수가 새어 들어와도 명령행 인자가 이긴다).
WL_ROLE_PASSWORD_SECRET="work-loop-shared-pipeline-role-secret"   # app.pipeline.role-password-secret(테넌트 실행·PYTHON 슬롯 롤)
WL_APP_TENANT_PASSWORD="app_tenant"                               # spring.datasource.password(app_tenant 롤, V83 기본값과 같게)
WL_PIPELINE_EXECUTOR_PASSWORD="pipeline_exec_pwd"                 # app.pipeline.datasource.password(레거시 pipeline_executor 롤)

# 컨테이너 런타임 기본 소켓(OrbStack 등)에서 docker compose 를 쓴다.
# 내 compose project(-p $STACK_ID) — --own-db·--with-neo4j 일 때만 컨테이너가 생긴다. 다른 project 는 절대 지정하지 않는다.
# own_compose 는 이번 기동에 켠 프로필만, own_compose_all 은 모든 프로필(조회·정리용 — 무엇을 켰든 내 것을 다 본다).
own_compose() {
  local profiles=""
  [ "${WL_OWN_DB:-0}" = 1 ] && profiles="$profiles --profile own-db"
  [ "${WL_WITH_NEO4J:-0}" = 1 ] && profiles="$profiles --profile graph"
  # 프로필 플래그는 공백 없는 고정 토큰이라 일부러 단어 분리한다(bash 3.2 의 set -u 빈 배열 전개 오류 회피).
  # shellcheck disable=SC2086
  docker compose -p "$STACK_ID" -f "$WORKTREE/scripts/work-loop/compose.yml" $profiles "$@"
}
own_compose_all() { docker compose -p "$STACK_ID" -f "$WORKTREE/scripts/work-loop/compose.yml" --profile '*' "$@"; }

# 내 compose project 에 컨테이너·볼륨이 하나라도 있는가
own_project_has_containers() { [ -n "$(own_compose_all ps -aq 2>/dev/null || true)" ]; }
own_project_has_volumes() {
  [ -n "$(docker volume ls -q --filter "label=com.docker.compose.project=$STACK_ID" 2>/dev/null || true)" ]
}

# 공유 바탕 compose project — 띄우기(up --no-recreate)·조회·exec 만 하고, 내리는 것은 cmd_shared_down 뿐이다.
shared_compose() {
  docker compose -p "$WL_SHARED_PROJECT" -f "$SHARED_COMPOSE_FILE" "$@"
}

log() { echo "[work-loop-stack:${LIVE_STACK_NAME:-shared}] $*"; }

# ---------------------------------------------------------------------------
# 포트 — 판정만 하고 남의 프로세스는 절대 죽이지 않는다
# ---------------------------------------------------------------------------
# 모든 컨테이너(멈춘 것 포함)의 공개 포트 목록. OrbStack 은 lsof 에도 보이지만, Linux Docker(userland-proxy=false·root 의
# docker-proxy)는 일반 사용자 lsof 에 안 보이므로 docker 에도 묻는다(WP live-stack.sh 와 같은 판단).
docker_published_ports() { docker ps -a --format '{{.Ports}}' 2>/dev/null || true; }

# 지금 LISTEN 중인 TCP 포트 번호 목록(공백 구분). lsof(내 프로세스)와 ss(Linux — 남의 소켓까지)를 합친다.
listening_ports() {
  {
    lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null | awk 'NR > 1 { print $9 }'
    if command -v ss >/dev/null 2>&1; then ss -Hltn 2>/dev/null | awk '{ print $4 }'; fi
  } | sed 's/.*://' | sort -u | tr '\n' ' ' || true
}

# 포트 판정용 스냅숏 — 고르기 한 번 동안 lsof·docker 를 후보마다 부르지 않게 한 번만 찍어 둔다.
port_snapshot() {
  WL_LISTEN_PORTS=" $(listening_ports) "
  WL_DOCKER_PORTS="$(docker_published_ports)"
}

# 「…:<포트>->…」 형식 목록(쉼표 구분, 범위 「…:<시작>-<끝>->…」 포함)에 포트가 들어 있는가
ports_list_has() {
  printf '%s\n' "$2" | tr ',' '\n' | awk -v p="$1" '
    /->/ {
      host = $0; sub(/->.*/, "", host); sub(/.*:/, "", host)
      n = split(host, r, "-")
      if (n == 1 && host + 0 == p) found = 1
      if (n == 2 && p >= r[1] + 0 && p <= r[2] + 0) found = 1
    }
    END { exit found ? 0 : 1 }'
}

# 공백 구분 목록에 값이 있는가
in_list() { case " $2 " in *" $1 "*) return 0 ;; esac; return 1; }

# 포트를 누가 쓰고 있는가(LISTEN·docker 공개 포트). 스냅숏이 있으면 그것을, 없으면 그때그때 묻는다.
# nc -z 같은 접속 확인은 듣는 쪽을 끊을 수 있어 쓰지 않는다.
port_in_use() {
  in_list "$1" "${WL_LISTEN_PORTS-$(listening_ports)}" && return 0
  ports_list_has "$1" "${WL_DOCKER_PORTS-$(docker_published_ports)}"
}

# 다른 스택이 예약한 포트를 WL_RESERVED_PORTS 에 모은다. 예약 파일(1행=그 스택의 stack.env, 2행=포트)은
# 그 stack.env 가 있을 때만 유효하다 — 없으면 down 없이 사라진 스택의 낡은 예약이라 지운다. 락 안에서 부른다.
load_reserved_ports() {
  WL_RESERVED_PORTS=""
  local f sf
  [ -d "$SHARED_PORTS_DIR" ] || return 0
  for f in "$SHARED_PORTS_DIR"/*; do
    [ -f "$f" ] || continue
    [ "$(basename "$f")" = "$STACK_ID" ] && continue
    sf="$(sed -n 1p "$f")"
    if [ -n "$sf" ] && [ -f "$sf" ]; then
      WL_RESERVED_PORTS="$WL_RESERVED_PORTS $(sed -n 2p "$f")"
    else
      rm -f "$f"
    fi
  done
}

# 고른 포트를 예약한다 — api 는 Flyway 를 끝낸 뒤(1~2분)에야 포트를 바인드하므로, 그 사이 다른 스택이 같은 포트를
# 고르지 않게 한다(락을 바인드까지 쥐고 있지 않기 위함).
reserve_ports() {
  mkdir -p "$SHARED_PORTS_DIR"
  printf '%s\n%s\n' "$STATE_FILE" "$WL_PICKED_PORTS" > "$SHARED_PORTS_DIR/$STACK_ID.tmp"
  mv "$SHARED_PORTS_DIR/$STACK_ID.tmp" "$SHARED_PORTS_DIR/$STACK_ID"
}
release_ports() { rm -f "$SHARED_PORTS_DIR/$STACK_ID"; }

# 포트 하나를 정해 변수에 넣는다(choose_port <변수명>). 고르기 전에 begin_port_pick 을 한 번 부른다.
# - 직접 준 값: 그대로 쓰되 쓰는 중·예약됨이면 실패(죽이지 않는다).
# - 아니면 기본값부터 +1 씩: 금지 포트·이번에 이미 고른 포트·다른 스택 예약·쓰는 중을 건너뛴다. 100개 안에 없으면 실패.
begin_port_pick() {
  WL_PICKED_PORTS=""
  port_snapshot
  load_reserved_ports
}
choose_port() {
  local var="$1" p n=0
  if in_list "$var" "$WL_EXPLICIT_PORTS"; then
    p="${!var}"
    if ! [[ "$p" =~ ^[0-9]+$ ]]; then log "$var=$p 는 포트 번호가 아니다"; return 1; fi
    if in_list "$p" "$WL_PICKED_PORTS" || in_list "$p" "$WL_RESERVED_PORTS" || port_in_use "$p"; then
      log "$var=$p 가 이미 쓰이거나 다른 스택이 예약했다 — 아무것도 죽이지 않는다. 다른 값을 주거나 지정을 빼 자동으로 고르게 할 것:"
      lsof -nP -iTCP:"$p" -sTCP:LISTEN >&2 || true
      return 1
    fi
  else
    p="$(port_default "$var")"
    while in_list "$p" "$WL_FORBIDDEN_PORTS" || in_list "$p" "$WL_PICKED_PORTS" \
        || in_list "$p" "$WL_RESERVED_PORTS" || port_in_use "$p"; do
      p=$((p + 1)); n=$((n + 1))
      if [ "$n" -ge 100 ]; then log "$var: 빈 포트를 찾지 못했다($(port_default "$var") 부터 100개)"; return 1; fi
    done
  fi
  printf -v "$var" '%s' "$p"
  WL_PICKED_PORTS="$WL_PICKED_PORTS $p"
}

# ---------------------------------------------------------------------------
# 컴퓨터 공용 락(머리 주석 「격리 원칙」 참고) — macOS 에 flock 이 없어 mkdir 의 원자성으로 잡는다.
# owner 파일에 「pid 시각」을 적고, 낡은 락은 rm 대신 고유 이름으로 mv 해 치운다(직전에 owner 를 다시 읽어, 그 사이
# 다른 대기자가 새로 잡은 락을 지우지 않게). 생존 확인은 ps -p — kill -0 은 다른 사용자 프로세스를 죽었다고 오판한다.
# ---------------------------------------------------------------------------
# 잡음 플래그 — mkdir_lock_* 가 변수명으로 간접 참조한다.
# shellcheck disable=SC2034
WL_LOCK_HELD=0
# shellcheck disable=SC2034
WL_STARTUP_LOCK_HELD=0
WL_LOCK_STALE_SECS="${WL_LOCK_STALE_SECS:-1800}"
WL_LOCK_WAIT_SECS="${WL_LOCK_WAIT_SECS:-900}"
# mkdir_lock_acquire <락 경로> <잡음 플래그 변수명> <이름>
mkdir_lock_acquire() {
  local lock="$1" flag="$2" label="$3"
  [ "${!flag}" = 1 ] && return 0
  local start=$SECONDS owner pid ts now age said=0 junk
  while ! mkdir "$lock" 2>/dev/null; do
    owner="$(cat "$lock/owner" 2>/dev/null || true)"
    now="$(date +%s)"
    if [ -n "$owner" ]; then
      pid="${owner%% *}"; ts="${owner##* }"
    else
      # mkdir 직후 owner 를 쓰기 전이거나, 그 사이에 죽은 경우 — 디렉터리 시각으로 나이를 잰다(30초 넘으면 낡음).
      pid=""; ts="$(stat -f %m "$lock" 2>/dev/null || stat -c %Y "$lock" 2>/dev/null || echo "$now")"
    fi
    [[ "$ts" =~ ^[0-9]+$ ]] || ts="$now"
    age=$((now - ts))
    if { [ -n "$pid" ] && ! ps -p "$pid" >/dev/null 2>&1; } || [ "$age" -gt "$WL_LOCK_STALE_SECS" ] \
        || { [ -z "$owner" ] && [ "$age" -gt 30 ]; }; then
      if [ "$(cat "$lock/owner" 2>/dev/null || true)" = "$owner" ]; then
        junk="$lock.stale.$$.$RANDOM"
        if mv "$lock" "$junk" 2>/dev/null; then
          log "낡은 ${label}을 치웠다(주인 '${owner:-?}', ${age}s)"; rm -rf "$junk"
        fi
      fi
      continue
    fi
    if [ "$said" = 0 ]; then log "$label 대기 중($lock, 주인 pid ${pid:-?})"; said=1; fi
    if (( SECONDS - start > WL_LOCK_WAIT_SECS )); then
      log "${label}을 ${WL_LOCK_WAIT_SECS}s 안에 잡지 못했다 — 주인 pid ${pid:-?} 가 살아 있고 잡은 지 ${age}s 다(빼앗지 않는다)."
      log "  그 프로세스(ps -p ${pid:-?})가 다른 스택의 up 이면 끝나길 기다렸다 다시 하고, 멈춘 것이 확실하면 그 프로세스를 끝낸 뒤 다시 할 것($lock)"
      return 1
    fi
    sleep 1
  done
  printf '%s %s\n' "$$" "$(date +%s)" > "$lock/owner.tmp"
  mv "$lock/owner.tmp" "$lock/owner"
  printf -v "$flag" '%s' 1
}
# 내가 잡은 락만 푼다(owner 의 pid 가 나인지 확인 — 낡은 락으로 빼앗긴 뒤 남의 락을 지우지 않게).
# mkdir_lock_release <락 경로> <잡음 플래그 변수명>
mkdir_lock_release() {
  local lock="$1" flag="$2" owner
  [ "${!flag}" = 1 ] || return 0
  printf -v "$flag" '%s' 0
  owner="$(cat "$lock/owner" 2>/dev/null || true)"
  [ "${owner%% *}" = "$$" ] && rm -rf "$lock"
  return 0
}
lock_acquire() { mkdir_lock_acquire "$SHARED_LOCK" WL_LOCK_HELD "공용 락"; }
lock_release() { mkdir_lock_release "$SHARED_LOCK" WL_LOCK_HELD; }
startup_lock_acquire() { mkdir_lock_acquire "$STARTUP_LOCK" WL_STARTUP_LOCK_HELD "api 기동 락"; }
startup_lock_release() { mkdir_lock_release "$STARTUP_LOCK" WL_STARTUP_LOCK_HELD; }

# ---------------------------------------------------------------------------
# 헬스 — 판단은 docker 자체(compose ps)로 한다
# ---------------------------------------------------------------------------
# compose_health <compose 함수> <서비스…> : 서비스마다 헬스(healthy/starting/unhealthy, 없거나 멈췄으면 없음,
# docker 에 닿지 않으면 unknown)를 공백 구분으로 출력한다. ps 는 한 번만 부르고, 절대 실패하지 않는다
# (status 가 set -e/pipefail 아래에서 docker 가 안 될 때도 모든 행을 찍게).
compose_health() {
  local fn="$1" out svc h res=""; shift
  if ! out="$("$fn" ps --format '{{.Service}} {{.Health}}' 2>/dev/null)"; then
    for svc in "$@"; do res="$res unknown"; done
  else
    for svc in "$@"; do
      h="$(printf '%s\n' "$out" | awk -v s="$svc" '$1 == s { print $2; exit }' || true)"
      res="$res ${h:-없음}"
    done
  fi
  echo "${res# }"
}
# 공백 구분 헬스 목록이 모두 healthy 인가
all_healthy() { local h; for h in $1; do [ "$h" = healthy ] || return 1; done; }
shared_services_healthy() { all_healthy "$(compose_health shared_compose "$@")"; }
# 주어진 공유 서비스가 healthy 가 될 때까지 최대 <초> 기다린다(wait_shared_healthy <초> <서비스…>).
wait_shared_healthy() {
  local secs="$1" start=$SECONDS; shift
  until shared_services_healthy "$@"; do
    (( SECONDS - start >= secs )) && return 1
    sleep 2
  done
}

# ---------------------------------------------------------------------------
# 공유 바탕(PostgreSQL·MinIO) — 띄우기·조회만
# ---------------------------------------------------------------------------
# 이 스택이 쓰는 공유 서비스 — --own-db 면 DB 는 전용 컨테이너라 MinIO 만 쓴다.
my_shared_services() { if [ "${WL_OWN_DB:-0}" = 1 ]; then echo minio; else echo "db minio"; fi; }
# 공유 서비스 → 호스트 포트(포트 목록의 단일 출처)
svc_ports() {
  local svc
  for svc in "$@"; do
    case "$svc" in
      db) echo "$WL_SHARED_PG_PORT" ;;
      minio) echo "$WL_SHARED_S3_PORT $WL_SHARED_CONSOLE_PORT" ;;
    esac
  done
}

# 공유 포트를 우리 공유 컨테이너가 아닌 것이 쓰는지 확인한다. 쓰면 누가 쓰는지(옛 이름 project 면 정리 방법)를 알리고 실패.
# check_shared_ports_free <서비스…> (기본 = db minio)
check_shared_ports_free() {
  local mine p bad=0 owners ports
  # shellcheck disable=SC2046  # 서비스·포트는 공백 없는 고정 토큰
  ports="$(svc_ports $([ $# -gt 0 ] && echo "$@" || echo db minio))"
  # 실행 중인 공유 컨테이너만 본다 — 멈춘 컨테이너도 docker ps -a 에 Ports 를 보여, 그 포트를 남이 잡고 있어도 내 것으로 오판한다.
  mine="$(docker ps --filter "label=com.docker.compose.project=$WL_SHARED_PROJECT" --format '{{.Ports}}' 2>/dev/null || true)"
  port_snapshot
  for p in $ports; do
    ports_list_has "$p" "$mine" && continue
    port_in_use "$p" || continue
    bad=1
    owners="$(docker ps -a --format '{{.Names}} (project={{.Label "com.docker.compose.project"}}) {{.Ports}}' 2>/dev/null \
      | while IFS= read -r line; do ports_list_has "$p" "$line" && echo "$line"; done || true)"
    log "공유 포트 $p 를 공유 바탕($WL_SHARED_PROJECT)이 아닌 것이 쓰고 있다 — 죽이지 않고 실패한다:"
    [ -n "$owners" ] && echo "$owners" | sed 's/^/    /' >&2
    lsof -nP -iTCP:"$p" -sTCP:LISTEN >&2 || true
    if [ -n "$LIVE_STACK_NAME" ] && echo "$owners" | grep -q "(project=$LIVE_STACK_NAME)"; then
      log "  옛 이름 project '$LIVE_STACK_NAME'(이 스크립트의 예전 방식)이다. 그 스택을 쓰는 세션이 끝났는지 확인한 뒤 그 저장소에서"
      log "  'LIVE_STACK_NAME=$LIVE_STACK_NAME scripts/work-loop-stack.sh down'(옛 스크립트) 또는 'docker compose -p $LIVE_STACK_NAME down -v' 로 정리할 것"
    fi
  done
  return $bad
}

# 내가 쓰는 공유 서비스가 없거나 멈춰 있으면 띄우고 healthy 까지 기다린다. healthy 면 그대로 재사용한다. 락 안에서 부른다.
# --no-recreate: 클론마다 compose.shared.yml 이 조금 달라도 떠 있는 컨테이너를 재생성(=모든 저장소 접속 끊김)하지 않는다.
# --build 를 주지 않는다: 이미지(work-loop-firehub-postgres:local)가 없을 때만 compose 가 빌드한다.
ensure_shared() {
  local svcs
  svcs="$(my_shared_services)"
  # shellcheck disable=SC2086  # 서비스 이름은 공백 없는 고정 토큰
  if shared_services_healthy $svcs; then log "공유 바탕 재사용(project=$WL_SHARED_PROJECT: $svcs)"; return 0; fi
  # shellcheck disable=SC2086
  check_shared_ports_free $svcs || return 1
  log "공유 바탕 기동(project=$WL_SHARED_PROJECT: $svcs — Postgres $WL_SHARED_PG_PORT, MinIO $WL_SHARED_S3_PORT/$WL_SHARED_CONSOLE_PORT)"
  mkdir -p "$LOG_DIR"
  # shellcheck disable=SC2086
  shared_compose up -d --no-recreate --wait --wait-timeout 300 $svcs > "$LOG_DIR/shared-up.log" 2>&1 \
    || { tail -30 "$LOG_DIR/shared-up.log" >&2; return 1; }
}

# 공유 서버 관리 접속(postgres DB). DB 이름은 해시라 SQL 에 그대로 넣어도 안전하다.
shared_psql() { shared_compose exec -T db psql -v ON_ERROR_STOP=1 -q -U app "$@"; }
# MinIO 클라이언트 — 서버 이미지에 든 mc 를 컨테이너 안에서 쓴다(별도 mc 컨테이너·alias 설정 파일 불필요).
shared_mc() {
  shared_compose exec -T -e "MC_HOST_wl=http://minioadmin:minioadmin@127.0.0.1:9000" minio mc --quiet "$@"
}
# 이 스택이 쓰는 PostgreSQL(공유 서버 또는 --own-db 컨테이너)에 psql 한다.
db_psql() {
  if [ "${WL_OWN_DB:-0}" = 1 ]; then own_compose exec -T db psql -v ON_ERROR_STOP=1 -q -U app "$@"
  else shared_psql "$@"
  fi
}

# 공유 서버에 있는 저장소 DB·버킷 목록(읽기만, 실패해도 빈 값). LIKE 의 _ 는 와일드카드라 이스케이프한다.
shared_db_list() {
  shared_psql -d postgres -tAc "SELECT datname FROM pg_database WHERE datname LIKE 'smartfirehub\_%' ORDER BY 1" 2>/dev/null || true
}
shared_bucket_list() {
  shared_mc ls wl/ 2>/dev/null | awk '{print $NF}' | sed 's#/$##' | grep '^firehub-' || true
}

# 있는가 — 조회 실패도 「없음」으로 본다(set -e 아래 어디서 불러도 안전).
db_exists() { [ "$(db_psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$WL_DB_NAME'" 2>/dev/null || true)" = 1 ]; }
bucket_exists() { local l; l="$(shared_bucket_list)"; in_list "$WL_BUCKET" "${l//$'\n'/ }"; }
# 만들기·지우기. DROP … WITH (FORCE) 는 남은 접속(내 api 잔여·psql)을 끊고 지운다. 롤은 클러스터 전역이라 지우지 않는다.
db_create() { db_psql -d postgres -c "CREATE DATABASE \"$WL_DB_NAME\" OWNER app"; }
db_drop() { db_psql -d postgres -c "DROP DATABASE IF EXISTS \"$WL_DB_NAME\" WITH (FORCE)" >/dev/null; }
bucket_create() { shared_mc mb "wl/$WL_BUCKET" >/dev/null; }
bucket_drop() { shared_mc rb --force "wl/$WL_BUCKET" >/dev/null 2>&1; }

# ensure_resource <표시 이름> <이름> <키> : DB·버킷 공통 결정 —
#   있음 + keep-data 표식(WL_PREV_KEEP=1) → 재사용(REUSED_<키>=1 기록)
#   있음 + 표식 없음 → 다른 상태가 섞인 잔여물이라 지우고 새로 / 없음 → 만든다(CREATED_<키>=1 기록)
# 함수 이름은 <소문자 키>_exists·_drop·_create 규칙을 따른다.
ensure_resource() {
  local what="$1" name="$2" key="$3" fn
  fn="$(echo "$3" | tr '[:upper:]' '[:lower:]')"
  if "${fn}_exists"; then
    if [ "${WL_PREV_KEEP:-0}" = 1 ]; then log "$what 재사용(--keep-data 로 남긴 것): $name"; save "REUSED_$key" 1; return 0; fi
    log "$what $name 가 남아 있지만 마지막 down 이 --keep-data 가 아니었다 — 지우고 새로 만든다"
    "${fn}_drop"
  fi
  log "$what 생성: $name"
  "${fn}_create"
  save "CREATED_$key" 1
}
db_ensure() { ensure_resource DB "$WL_DB_NAME" DB; }
bucket_ensure() { ensure_resource 버킷 "$WL_BUCKET" BUCKET; }

# 옛 스크립트 흔적을 한곳에서 본다.
#  - 옛 이름 compose project($LIVE_STACK_NAME, 예: work-loop)가 남아 있으면 경고만 한다(다른 세션이 쓰는 중일 수 있어 지우지 않는다.
#    그것이 공유 포트를 잡고 있으면 ensure_shared 의 check_shared_ports_free 가 실패시키고 정리 방법을 알린다).
#  - 상태 파일이 옛 형식(STACK_ID 없음)이면 알리고 1 을 돌려준다.
legacy_state_check() {
  local legacy
  legacy="$(docker ps -a --filter "label=com.docker.compose.project=$LIVE_STACK_NAME" --format '{{.Names}}' 2>/dev/null || true)"
  if [ -n "$legacy" ]; then
    log "경고: 옛 이름 compose project '$LIVE_STACK_NAME' 컨테이너가 남아 있다($(echo "$legacy" | tr '\n' ' ')) — 이 스크립트는 건드리지 않는다."
    log "  그 스택을 쓰는 세션이 끝났으면 'docker compose -p $LIVE_STACK_NAME down -v' 로 정리할 것(공유 포트를 잡고 있으면 up 이 실패한다)"
  fi
  if [ -f "$STATE_FILE" ] && ! grep -q '^STACK_ID=' "$STATE_FILE"; then
    log "옛 형식 상태 파일(STACK_ID 없음: $STATE_FILE) — down 은 프로세스만 정리하고 compose project '$LIVE_STACK_NAME' 은 건드리지 않는다"
    return 1
  fi
  return 0
}

# HTTP 상태코드만 출력(실패 시 000). curl 은 접속 실패에도 -w 로 000 을 찍고 실패하므로 덧붙이지 않고 빈 값만 메운다.
http_code() { local c; c="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$1" 2>/dev/null)" || true; echo "${c:-000}"; }

# 지정 URL 이 기대 코드가 될 때까지 대기. 해당 서비스 프로세스가 죽으면 즉시 실패.
wait_http() {
  local name="$1" url="$2" expect="$3" timeout="$4" pgid="${5:-}"
  local start=$SECONDS code
  while true; do
    code="$(http_code "$url")"
    [ "$code" = "$expect" ] && { log "$name 준비됨 ($url → $code)"; return 0; }
    if [ -n "$pgid" ] && ! kill -0 "$pgid" 2>/dev/null; then
      log "$name 프로세스가 종료됐다 — 로그: $LOG_DIR/$name.log"; tail -40 "$LOG_DIR/$name.log" >&2 || true; return 1
    fi
    if (( SECONDS - start > timeout )); then
      log "$name 헬스 대기 시간 초과(${timeout}s, 마지막 코드 $code) — 로그: $LOG_DIR/$name.log"
      tail -40 "$LOG_DIR/$name.log" >&2 || true; return 1
    fi
    sleep 2
  done
}

# 새 프로세스 그룹으로 백그라운드 기동하고 pgid(=pid)를 출력한다.
# vite·pnpm 처럼 자식을 띄우는 도구도 down 에서 그룹째(kill -- -pgid) 정리하기 위함이다.
spawn() {
  local name="$1" cwd="$2"; shift 2
  ( cd "$cwd" && exec nohup perl -e 'setpgrp(0,0); exec @ARGV or die "exec 실패: $!"' "$@" ) \
    >"$LOG_DIR/$name.log" 2>&1 < /dev/null &
  echo $!
}

# 상태 파일에 key=value 한 줄 추가
save() { echo "$1=$2" >> "$STATE_FILE"; }

# 상태 파일 로드(없으면 빈 값). STACK_ID·DB·버킷·포트·플래그·소유 기록이 저장된 값으로 덮이고, 공유 project 에 맞춰 경로를 다시 만든다.
load_state() {
  if [ -f "$STATE_FILE" ]; then . "$STATE_FILE"; fi
  shared_paths
}

# 프로세스 그룹 종료 — 모두에 TERM 을 먼저 보내고 함께 최대 20초 기다린 뒤 남은 것만 KILL.
# stop_groups <이름=pgid>…
stop_groups() {
  local pair alive="" still start=$SECONDS
  for pair in "$@"; do
    [ -n "${pair#*=}" ] || continue
    if kill -0 -- "-${pair#*=}" 2>/dev/null || kill -0 "${pair#*=}" 2>/dev/null; then
      log "${pair%%=*}(pgid ${pair#*=}) 종료 중"
      kill -TERM -- "-${pair#*=}" 2>/dev/null || kill -TERM "${pair#*=}" 2>/dev/null || true
      alive="$alive $pair"
    else
      log "${pair%%=*}(pgid ${pair#*=}) 이미 종료됨"
    fi
  done
  while [ -n "$alive" ] && (( SECONDS - start < 20 )); do
    still=""
    for pair in $alive; do kill -0 -- "-${pair#*=}" 2>/dev/null && still="$still $pair"; done
    alive="$still"
    [ -n "$alive" ] && sleep 1
  done
  for pair in $alive; do
    log "${pair%%=*} 가 TERM 에 응답하지 않아 KILL"; kill -KILL -- "-${pair#*=}" 2>/dev/null || true
  done
  return 0
}

# ---------------------------------------------------------------------------
# seed: 테스트 계정 생성(멱등) + 로그인 확인
# ---------------------------------------------------------------------------
cmd_seed() {
  local api="http://127.0.0.1:$WL_API_PORT/api/v1"
  local status_json open
  status_json="$(curl -sf -m 10 "$api/auth/signup-status")" || { log "api 응답 없음 — 먼저 up"; return 1; }
  open="$(echo "$status_json" | grep -o '"open"[^,}]*' | grep -o 'true\|false' || true)"
  if [ "$open" = true ]; then
    log "공개 가입 열림(사용자 0명) — 테스트 계정 생성: $WL_TEST_USER"
    local body code
    body="$(printf '{"username":"%s","email":"%s","password":"%s","name":"%s"}' \
      "$WL_TEST_USER" "$WL_TEST_USER" "$WL_TEST_PASSWORD" "$WL_TEST_NAME")"
    code="$(curl -s -o "$STATE_DIR/signup.json" -w '%{http_code}' -m 20 -H 'Content-Type: application/json' \
      -d "$body" "$api/auth/signup")"
    [ "$code" = 201 ] || { log "가입 실패 HTTP $code: $(cat "$STATE_DIR/signup.json")"; return 1; }
  else
    log "이미 사용자가 있다 — 가입 생략(signup-status=$status_json)"
  fi
  # admin 콘솔(/api/platform) 로그인은 플랫폼 운영자 롤이 필요하다. 공개 가입은 테넌트 ADMIN 만 주므로,
  # 내 격리 DB 에 한해 소유자 롤(app)로 SUPER_ADMIN 을 직접 부여한다(멱등). 공유 서버라도 내 DB 에만 접속한다.
  db_psql -d "$WL_DB_NAME" -c \
    "INSERT INTO platform_user_role (user_id, platform_role_id)
     SELECT u.id, pr.id FROM \"user\" u, platform_role pr
     WHERE u.username = '$WL_TEST_USER' AND pr.name = 'SUPER_ADMIN'
     ON CONFLICT DO NOTHING" >/dev/null
  # 로그인 확인은 1회만 — 실패를 반복하면 로그인 잠금 카운터가 올라간다.
  local code
  code="$(curl -s -o "$STATE_DIR/login.json" -w '%{http_code}' -m 20 -H 'Content-Type: application/json' \
    -d "$(printf '{"username":"%s","password":"%s"}' "$WL_TEST_USER" "$WL_TEST_PASSWORD")" "$api/auth/login")"
  if [ "$code" = 200 ]; then
    log "테스트 계정 로그인 성공: $WL_TEST_USER / $WL_TEST_PASSWORD (web 테넌트 ADMIN + admin 콘솔 SUPER_ADMIN)"
  else
    log "테스트 계정 로그인 실패 HTTP $code: $(cat "$STATE_DIR/login.json")"; return 1
  fi
}

# ---------------------------------------------------------------------------
# up
# ---------------------------------------------------------------------------
cmd_up() {
  mkdir -p "$STATE_DIR"
  legacy_state_check || true
  if [ -f "$STATE_FILE" ]; then
    echo "이미 상태 파일이 있다($STATE_FILE) — 먼저 'LIVE_STACK_NAME=$LIVE_STACK_NAME $0 down' 을 실행할 것" >&2
    exit 1
  fi
  # 「컨테이너가 남아 있다」 검사는 내 project(--own-db·neo4j 의 $STACK_ID)에만 한다 — 공유 바탕·남의 project 는 보지 않는다.
  if own_project_has_containers; then
    echo "compose project '$STACK_ID' 컨테이너가 남아 있다 — 먼저 down" >&2
    exit 1
  fi

  rm -rf "$LOG_DIR"; mkdir -p "$LOG_DIR"
  : > "$STATE_FILE"
  # 이름·플래그를 먼저 적는다 — 이후 어디서 실패해도 ERR 트랩의 down 이 정확히 내 것만 정리한다.
  save WORKTREE "$WORKTREE"
  save STACK_ID "$STACK_ID"; save WL_DB_NAME "$WL_DB_NAME"; save WL_BUCKET "$WL_BUCKET"
  save WL_SHARED_PROJECT "$WL_SHARED_PROJECT"; save WL_SHARED_PG_PORT "$WL_SHARED_PG_PORT"
  save WL_SHARED_S3_PORT "$WL_SHARED_S3_PORT"; save WL_SHARED_CONSOLE_PORT "$WL_SHARED_CONSOLE_PORT"
  save WL_WITH_ADMIN "${WL_WITH_ADMIN:-0}"; save WL_WITH_NEO4J "${WL_WITH_NEO4J:-0}"; save WL_OWN_DB "${WL_OWN_DB:-0}"
  save LOG_DIR "$LOG_DIR"

  # 마지막 down 이 --keep-data 였는지(그때만 남은 DB·버킷·볼륨을 재사용한다). 표식은 up 이 성공한 뒤에 지운다.
  WL_PREV_KEEP=0; [ -f "$STATE_DIR/keep-data" ] && WL_PREV_KEEP=1

  # 실패 시 이 스택 소유분만 정리 — down 이 stack.env 의 소유 기록대로 지운다(표식이 남아 있어 재사용한 것은 남는다).
  # 정리는 최상위 셸에서만 한다 — macOS bash 3.2 는 set -E 아래에서 `x="$(실패)" || true` 처럼 바깥에서 받은
  # $(…) 안 실패에도 서브셸에서 ERR 트랩을 부른다(그러면 헬스 확인 중 정리가 돌아 스택이 지워진다).
  # 서브셸의 진짜 실패는 부모의 대입이 실패해 부모에서 다시 트랩이 걸린다.
  trap '[ "${BASH_SUBSHELL:-0}" -gt 0 ] || { log "기동 실패 — 이 up 이 띄우거나 만든 것만 정리한다"; cmd_down || true; }' ERR

  # 1) 컴퓨터 공용 락 안에서: 포트 고르기·예약 → 공유 바탕 확인/기동 → (공유 Postgres) 내 DB.
  #    CREATE DATABASE 는 template1 을 복사하므로 동시 실행이 서로 막을 수 있어 락 안에 둔다. 버킷은 락 밖에서 만든다.
  lock_acquire
  begin_port_pick
  choose_port WL_API_PORT; choose_port WL_AGENT_PORT; choose_port WL_WEB_PORT
  [ "${WL_WITH_ADMIN:-0}" = 1 ] && choose_port WL_ADMIN_PORT
  # Neo4j 포트는 --with-neo4j 가 아니어도 고르고 예약한다 — ai-agent 의 NEO4J_URI 가 남의 Neo4j(다른 스택·dev 7687)에 붙지 않게.
  choose_port WL_NEO4J_HTTP_PORT; choose_port WL_NEO4J_BOLT_PORT
  if [ "${WL_OWN_DB:-0}" = 1 ]; then choose_port WL_DB_PORT; else WL_DB_PORT="$WL_SHARED_PG_PORT"; fi
  export WL_DB_PORT WL_NEO4J_HTTP_PORT WL_NEO4J_BOLT_PORT
  reserve_ports
  save WL_API_PORT "$WL_API_PORT"; save WL_AGENT_PORT "$WL_AGENT_PORT"; save WL_WEB_PORT "$WL_WEB_PORT"
  save WL_ADMIN_PORT "$WL_ADMIN_PORT"; save WL_DB_PORT "$WL_DB_PORT"
  save WL_NEO4J_HTTP_PORT "$WL_NEO4J_HTTP_PORT"; save WL_NEO4J_BOLT_PORT "$WL_NEO4J_BOLT_PORT"
  log "포트: api $WL_API_PORT · ai-agent $WL_AGENT_PORT · web $WL_WEB_PORT${WL_ADMIN_PORT:+ · admin $WL_ADMIN_PORT} · DB $WL_DB_PORT"
  ensure_shared
  [ "${WL_OWN_DB:-0}" = 1 ] || db_ensure
  lock_release
  bucket_ensure

  # 2) 저장소 전용 컨테이너(--own-db·--with-neo4j) — compose project=$STACK_ID. 볼륨이 이미 있으면(--keep-data) 재사용으로 기록한다.
  if [ "${WL_OWN_DB:-0}" = 1 ] || [ "${WL_WITH_NEO4J:-0}" = 1 ]; then
    if own_project_has_volumes; then save REUSED_VOLUME 1; else save CREATED_VOLUME 1; fi
  fi
  if [ "${WL_OWN_DB:-0}" = 1 ]; then
    log "전용 PostgreSQL 기동(compose project=$STACK_ID, 포트 $WL_DB_PORT)"
    own_compose up -d --wait db > "$LOG_DIR/compose-up.log" 2>&1 || { tail -30 "$LOG_DIR/compose-up.log" >&2; false; }
    db_ensure
  fi
  if [ "${WL_WITH_NEO4J:-0}" = 1 ]; then
    log "Neo4j 기동(compose project=$STACK_ID)"
    own_compose up -d neo4j >> "$LOG_DIR/compose-up.log" 2>&1 || { tail -30 "$LOG_DIR/compose-up.log" >&2; false; }
  fi

  # 3) 빌드 — 둘 다 api 기동 락 밖에서 끝낸다. 메모리가 부족한 서버라 gradle·pnpm 을 동시에 돌리지 않는다.
  #    api 는 bootRun 의 stale 바이트코드를 피하려고 clean 후 jar 를 만들고, 그 사본을 상태 디렉터리에서 실행한다.
  #    jOOQ 생성 소스는 저장소에 추적되므로 generateJooq 는 필요 없다(스키마를 바꾼 브랜치는 그 브랜치가 직접 갱신).
  local jar="$STATE_DIR/api.jar"
  if [ "${WL_SKIP_BUILD:-0}" != 1 ] || [ ! -f "$jar" ]; then
    log "api 빌드(./gradlew clean bootJar) — 로그: $LOG_DIR/api-build.log"
    ( cd "$WORKTREE/apps/firehub-api" && ./gradlew clean bootJar -x test > "$LOG_DIR/api-build.log" 2>&1 ) \
      || { tail -30 "$LOG_DIR/api-build.log" >&2; false; }
    cp "$(ls "$WORKTREE"/apps/firehub-api/build/libs/*-SNAPSHOT.jar | grep -v plain | head -1)" "$jar"
  fi
  if [ "${WL_SKIP_BUILD:-0}" != 1 ] || [ ! -f "$WORKTREE/apps/firehub-ai-agent/dist/index.js" ]; then
    log "ai-agent 빌드(pnpm build)"
    ( cd "$WORKTREE/apps/firehub-ai-agent" && rm -rf dist && pnpm build > "$LOG_DIR/ai-agent-build.log" 2>&1 ) \
      || { tail -30 "$LOG_DIR/ai-agent-build.log" >&2; false; }
  fi

  local api_url="http://127.0.0.1:$WL_API_PORT"
  # api ↔ ai-agent 내부 토큰(스택마다 다르게 — 다른 스택·개발 세션과 섞이면 401 로 드러나게 한다)
  local internal_token="work-loop-internal-token-$STACK_ID"

  # 4) ai-agent·web·admin 을 먼저 띄운다 — api 는 요청 때만 필요하므로 api 기동 락을 기다리는 동안 함께 기동된다.
  #    ai-agent: 개발용 .env.local 을 읽지 않도록 필요한 값을 전부 env 로 명시한다(dotenv 는 기존 env 를 덮지 않는다).
  #    AI 공급자 자격은 테넌트 설정에서 요청마다 오므로 없어도 기동한다(챗 응답만 불가). DB·MinIO 는 직접 쓰지 않는다(api 경유).
  mkdir -p "$STATE_DIR/xdg-data"
  local agent_pgid
  agent_pgid="$(spawn ai-agent "$WORKTREE/apps/firehub-ai-agent" env \
    PORT="$WL_AGENT_PORT" \
    INTERNAL_SERVICE_TOKEN="$internal_token" \
    API_BASE_URL="$api_url/api/v1" \
    MAX_TURNS=10 \
    XDG_DATA_HOME="$STATE_DIR/xdg-data" \
    NEO4J_URI="bolt://127.0.0.1:$WL_NEO4J_BOLT_PORT" \
    NEO4J_USER=neo4j NEO4J_PASSWORD=firehub-graph-dev \
    BRAND_NAME="Smart Fire Hub" \
    node dist/index.js)"
  save AGENT_PGID "$agent_pgid"
  #    web: 래퍼 설정으로 /api 프록시를 격리 api 로 돌린다. 127.0.0.1 에 strictPort 로 묶는다.
  #    파일 업로드·다운로드는 api 가 준 presigned URL(공유 MinIO, 내 버킷)로 브라우저가 직접 한다 — web 은 버킷 이름을 모른다.
  local web_pgid
  web_pgid="$(spawn web "$WORKTREE/apps/firehub-web" env FIREHUB_API_PROXY_TARGET="$api_url" \
    ./node_modules/.bin/vite --config "$WORKTREE/scripts/work-loop/vite.web.config.mts" \
    --host 127.0.0.1 --port "$WL_WEB_PORT" --strictPort)"
  save WEB_PGID "$web_pgid"
  local admin_pgid=""
  if [ "${WL_WITH_ADMIN:-0}" = 1 ]; then
    admin_pgid="$(spawn admin "$WORKTREE/apps/firehub-admin" env FIREHUB_API_PROXY_TARGET="$api_url" \
      ./node_modules/.bin/vite --config "$WORKTREE/scripts/work-loop/vite.admin.config.mts" \
      --host 127.0.0.1 --port "$WL_ADMIN_PORT" --strictPort)"
    save ADMIN_PGID "$admin_pgid"
  fi

  # 5) api — local 프로필의 고정값(DB 5432/smartfirehub, JobRunr 대시보드 5040, agent 5020, MinIO 9000/firehub-files)을
  #    전부 격리값으로 덮는다. 명령행 인자는 Spring 속성 우선순위 최상위라 relaxed binding 추측 없이 확실히 덮인다.
  #    롤 비밀번호 관련 설정(런타임 app_tenant·Flyway 소유자 app·레거시 pipeline_executor·테넌트 롤 시크릿)도 위 고정값으로 준다.
  local db="jdbc:postgresql://127.0.0.1:$WL_DB_PORT/$WL_DB_NAME"
  local web_origin="http://localhost:$WL_WEB_PORT"
  local cors="$web_origin,http://127.0.0.1:$WL_WEB_PORT"
  [ -n "$WL_ADMIN_PORT" ] && cors="$cors,http://localhost:$WL_ADMIN_PORT,http://127.0.0.1:$WL_ADMIN_PORT"
  # JWT 서명키는 기동마다 새로 만든다 — local 프로필 기본키를 그대로 쓰면 격리 스택 토큰이 개발 api(5010)·다른 저장소 스택에서도
  # 유효해져(같은 userId=1 이 존재) 검증 중 실수로 남의 데이터를 건드릴 수 있다.
  local jwt_secret
  jwt_secret="$(openssl rand -base64 64 | tr -d '\n')"
  # 공유 Postgres 면 다른 클론의 api 기동과 겹치지 않게 기동 락을 잡는다(머리 주석). --own-db 는 클러스터가 내 것이라 필요 없다.
  [ "${WL_OWN_DB:-0}" = 1 ] || startup_lock_acquire
  local api_pgid
  api_pgid="$(spawn api "$STATE_DIR" java -Xmx1g -jar "$jar" \
    --spring.profiles.active=local \
    --server.port="$WL_API_PORT" \
    --app.jwt.secret="$jwt_secret" \
    --spring.datasource.url="$db" \
    --spring.datasource.username=app_tenant \
    --spring.datasource.password="$WL_APP_TENANT_PASSWORD" \
    --spring.flyway.url="$db" \
    --spring.flyway.user=app \
    --spring.flyway.password=app \
    --app.pipeline.datasource.url="$db" \
    --app.pipeline.datasource.password="$WL_PIPELINE_EXECUTOR_PASSWORD" \
    --app.pipeline.role-password-secret="$WL_ROLE_PASSWORD_SECRET" \
    --org.jobrunr.dashboard.enabled=false \
    --agent.url="http://127.0.0.1:$WL_AGENT_PORT" \
    --agent.internal-token="$internal_token" \
    --app.base-url="$web_origin" \
    --app.cors.allowed-origins="$cors" \
    --firehub.minio.endpoint="http://127.0.0.1:$WL_SHARED_S3_PORT" \
    --firehub.minio.public-endpoint="http://127.0.0.1:$WL_SHARED_S3_PORT" \
    --firehub.minio.bucket="$WL_BUCKET" \
    --firehub.file.upload-dir="$STATE_DIR/uploads" \
    --app.embedding.backlog-sweep-enabled=false)"
  save API_PGID "$api_pgid"
  log "api 기동(pgid $api_pgid, 포트 $WL_API_PORT) — 최초 기동은 전체 Flyway 마이그레이션으로 1~2분 걸린다"

  # 6) 헬스 대기 — api 가 준비되는 즉시 기동 락을 푼다(ai-agent·web·admin 대기 동안 다른 클론을 막지 않게).
  wait_http api "$api_url/api/v1/auth/signup-status" 200 300 "$api_pgid"
  startup_lock_release
  wait_http ai-agent "http://127.0.0.1:$WL_AGENT_PORT/agent/health" 200 60 "$agent_pgid"
  wait_http web "http://127.0.0.1:$WL_WEB_PORT/" 200 60 "$web_pgid"
  [ "${WL_WITH_ADMIN:-0}" = 1 ] && wait_http admin "http://127.0.0.1:$WL_ADMIN_PORT/" 200 60 "$admin_pgid"

  # 7) 테스트 계정
  cmd_seed
  trap - ERR
  # 기동이 끝났으니 keep-data 표식을 지운다 — 재사용한 데이터도 이제 이 스택 것이고, 다음 down 이 지울지 남길지 정한다.
  rm -f "$STATE_DIR/keep-data"
  log "기동 완료 — STACK_ID=$STACK_ID (포트·DB 이름: $STATE_FILE)"
  log "  web      http://localhost:$WL_WEB_PORT"
  log "  api      http://localhost:$WL_API_PORT/api/v1"
  log "  ai-agent http://localhost:$WL_AGENT_PORT/agent"
  [ -n "$WL_ADMIN_PORT" ] && log "  admin    http://localhost:$WL_ADMIN_PORT"
  log "  DB $WL_DB_NAME @127.0.0.1:$WL_DB_PORT · 버킷 $WL_BUCKET @127.0.0.1:$WL_SHARED_S3_PORT"
}

# ---------------------------------------------------------------------------
# down — 상태 파일의 프로세스 그룹과, stack.env 의 소유 기록이 이 스택 것이라고 한 DB·버킷·전용 볼륨만 정리
# ---------------------------------------------------------------------------
# owns <키> : 이 스택이 지울 수 있는가 — 이번에 만든 것(CREATED_<키>), 또는 재사용했고 keep-data 표식이 이미 없는 것
# (표식이 남아 있으면 up 이 아직 끝나지 않은 것이라 재사용분은 다음 up 몫으로 남긴다). WL_DISCARD_KEPT=1 이면 표식이 있어도 지운다.
WL_DISCARD_KEPT=0
owns() {
  local created="CREATED_$1" reused="REUSED_$1"
  [ "${!created:-0}" = 1 ] && return 0
  [ "${!reused:-0}" = 1 ] && { [ ! -f "$STATE_DIR/keep-data" ] || [ "$WL_DISCARD_KEPT" = 1 ]; }
}

cmd_down() {
  trap - ERR   # ERR 트랩(기동 실패 정리)에서 불려도 정리 중 실패가 트랩을 다시 부르지 않게 한다
  local keep="${WL_KEEP_DATA:-0}" vflag="" need=""
  load_state
  stop_groups "admin=${ADMIN_PGID:-}" "web=${WEB_PGID:-}" "ai-agent=${AGENT_PGID:-}" "api=${API_PGID:-}"
  if ! legacy_state_check; then
    rm -f "$STATE_FILE" "$STATE_DIR/signup.json" "$STATE_DIR/login.json"
    return 0
  fi
  # 상태 파일에 적힌 워크트리의 compose 파일을 쓴다(인자로 다른 워크트리를 넘겨도 같은 project 를 내린다).
  [ -n "${WORKTREE:-}" ] && [ -f "$WORKTREE/scripts/work-loop/compose.yml" ] || WORKTREE="$(cd "$SCRIPT_DIR/.." && pwd)"
  # 상태 파일 없이 keep-data 표식만 있으면 「보관해 둔 데이터를 버린다」는 뜻이다(--keep-data down 뒤의 보통 down).
  if [ ! -f "$STATE_FILE" ] && [ -f "$STATE_DIR/keep-data" ] && [ "$keep" != 1 ]; then
    log "보관해 둔 데이터(--keep-data)를 지운다: DB $WL_DB_NAME · 버킷 $WL_BUCKET"
    # 표식은 다 지운 뒤에 없앤다 — 중간에 실패하면 down 을 다시 해 같은 경로로 들어오게.
    # shellcheck disable=SC2034  # owns 가 간접 참조한다
    WL_DISCARD_KEPT=1 REUSED_DB=1 REUSED_BUCKET=1 REUSED_VOLUME=1
  fi

  if [ "$keep" = 1 ]; then
    log "데이터 유지(--keep-data): DB $WL_DB_NAME · 버킷 $WL_BUCKET 를 남긴다(다음 up 이 재사용)"
    echo "STACK_ID=$STACK_ID" > "$STATE_DIR/keep-data"
  else
    # --own-db 의 DB 는 전용 볼륨에 있어 아래 compose down -v 가 함께 지운다(공유 Postgres 에 접속할 일 없음).
    [ "${WL_OWN_DB:-0}" != 1 ] && owns DB && need="db"
    owns BUCKET && need="$need minio"
    owns VOLUME && vflag="-v"
  fi
  if [ -n "$need" ]; then
    # 공유 바탕이 잠시 재시작 중일 수 있어 조금 기다린다. 그래도 아니면 조용히 넘기지 않는다 — 상태 파일을 남겨 down 을 다시 할 수 있게 한다.
    # shellcheck disable=SC2086  # 서비스 이름은 공백 없는 고정 토큰
    if ! wait_shared_healthy "${WL_DOWN_WAIT_SECS:-60}" $need; then
      log "공유 바탕(project=$WL_SHARED_PROJECT, db minio = $(compose_health shared_compose db minio))이 healthy 가 아니라"
      log "  내 DB $WL_DB_NAME · 버킷 $WL_BUCKET 를 지우지 못했다. 프로세스는 정리했고 상태 파일($STATE_FILE)은 남긴다."
      log "  공유 바탕이 돌아온 뒤 down 을 다시 할 것. 공유 바탕을 이미 내려 데이터를 버려도 되면 --keep-data 로 down 해 상태만 정리할 것"
      return 1
    fi
    if in_list db "$need"; then
      log "DB 삭제: $WL_DB_NAME"
      db_drop || { log "DB 삭제 실패 — 상태 파일을 남긴다. 원인을 확인한 뒤 down 을 다시 할 것"; return 1; }
    fi
    if in_list minio "$need"; then
      log "버킷 삭제: $WL_BUCKET"
      # 실패했어도 버킷이 이미 없으면(남이 지웠거나 공유 바탕 볼륨을 지운 뒤) 성공으로 본다.
      bucket_drop || ! bucket_exists || { log "버킷 삭제 실패 — 상태 파일을 남긴다. 원인을 확인한 뒤 down 을 다시 할 것"; return 1; }
    fi
  fi
  # 내 compose project(--own-db·neo4j): 컨테이너는 늘 내리고, 볼륨은 이 스택 소유일 때만 지운다.
  if own_project_has_containers || { [ -n "$vflag" ] && own_project_has_volumes; }; then
    log "컨테이너 정리(project=$STACK_ID${vflag:+, 볼륨까지})"
    # shellcheck disable=SC2086  # vflag 는 비었거나 -v
    own_compose_all down $vflag --remove-orphans >/dev/null 2>&1 || true
  fi
  release_ports
  [ "$WL_DISCARD_KEPT" = 1 ] && rm -f "$STATE_DIR/keep-data"
  rm -f "$STATE_FILE" "$STATE_DIR/signup.json" "$STATE_DIR/login.json"
  log "정리 완료 — STACK_ID=$STACK_ID (공유 바탕은 그대로, 로그는 $LOG_DIR 에 남김)"
}

# ---------------------------------------------------------------------------
# status — 내 스택 헬스 + 공유 바탕 헬스 + 공유 서버의 다른 저장소 DB·버킷(읽기만)
# ---------------------------------------------------------------------------
# print_shared <db 헬스> <minio 헬스> <버킷 목록> : 공유 바탕 한 줄 요약 + 다른 저장소 DB·버킷(내 것 제외). 이미 받은 값을 쓴다.
print_shared() {
  local dbh="$1" minh="$2" buckets="$3" others
  echo "  공유 바탕 project=$WL_SHARED_PROJECT  db 127.0.0.1:$WL_SHARED_PG_PORT ($dbh)  minio 127.0.0.1:$WL_SHARED_S3_PORT/$WL_SHARED_CONSOLE_PORT ($minh)"
  if [ "$dbh" = healthy ]; then
    others="$(shared_db_list | grep -vx "$WL_DB_NAME" | tr '\n' ' ' || true)"
    echo "    다른 저장소 DB: ${others:-없음}"
  fi
  if [ "$minh" = healthy ]; then
    others="$(printf '%s\n' "$buckets" | grep -vx -e "$WL_BUCKET" -e '' | tr '\n' ' ' || true)"
    echo "    다른 저장소 버킷: ${others:-없음}"
  fi
  return 0
}

cmd_status() {
  load_state
  local sdbh sminh buckets=""
  read -r sdbh sminh <<< "$(compose_health shared_compose db minio)"
  [ "$sminh" = healthy ] && buckets="$(shared_bucket_list)"
  if [ ! -f "$STATE_FILE" ]; then
    log "기동 상태 아님(상태 파일 없음: $STATE_FILE)"
    own_project_has_containers && log "경고: compose project '$STACK_ID' 컨테이너가 남아 있다"
    legacy_state_check || true
    print_shared "$sdbh" "$sminh" "$buckets"
    return 1
  fi
  legacy_state_check || return 1
  local bad=0
  row() { # 이름 pgid url 기대코드
    local name="$1" pgid="$2" url="$3" expect="$4" alive="-" code
    if [ -n "$pgid" ]; then kill -0 "$pgid" 2>/dev/null && alive="alive" || alive="DEAD"; fi
    code="$(http_code "$url")"
    local ok=OK; { [ "$code" = "$expect" ] && [ "$alive" != DEAD ]; } || { ok=FAIL; bad=1; }
    printf '  %-9s %-5s pgid=%-7s %-55s HTTP %s\n' "$name" "$ok" "${pgid:--}" "$url" "$code"
  }
  echo "스택 '$LIVE_STACK_NAME' STACK_ID=$STACK_ID (워크트리: ${WORKTREE:-?})"
  # DB: 서버 헬스 + 내 DB 존재(공유·전용 모두 compose_health 로 같은 방식으로 본다)
  local dbh where exists=0 dbok=OK
  if [ "${WL_OWN_DB:-0}" = 1 ]; then dbh="$(compose_health own_compose_all db)"; where="전용 project=$STACK_ID"
  else dbh="$sdbh"; where="공유"; fi
  [ "$dbh" = healthy ] && db_exists && exists=1
  [ "$exists" = 1 ] || { dbok=FAIL; bad=1; }
  printf '  %-9s %-5s %s\n' db "$dbok" "127.0.0.1:${WL_DB_PORT} ($dbh, $where) app/app db=$WL_DB_NAME$([ "$exists" = 1 ] || echo ' (DB 없음)')"
  # MinIO: 호스트에서 닿는가 + 내 버킷 존재
  local bok=OK
  if [ "$(http_code "http://127.0.0.1:${WL_SHARED_S3_PORT}/minio/health/live")" != 200 ] \
      || ! in_list "$WL_BUCKET" "${buckets//$'\n'/ }"; then bok=FAIL; bad=1; fi
  printf '  %-9s %-5s %s\n' minio "$bok" "127.0.0.1:${WL_SHARED_S3_PORT} (콘솔 ${WL_SHARED_CONSOLE_PORT}) 버킷=$WL_BUCKET"
  row api "${API_PGID:-}" "http://127.0.0.1:${WL_API_PORT}/api/v1/auth/signup-status" 200
  row ai-agent "${AGENT_PGID:-}" "http://127.0.0.1:${WL_AGENT_PORT}/agent/health" 200
  row web "${WEB_PGID:-}" "http://127.0.0.1:${WL_WEB_PORT}/" 200
  [ "${WL_WITH_ADMIN:-0}" = 1 ] && row admin "${ADMIN_PGID:-}" "http://127.0.0.1:${WL_ADMIN_PORT}/" 200
  [ "${WL_WITH_NEO4J:-0}" = 1 ] && row neo4j "" "http://127.0.0.1:${WL_NEO4J_HTTP_PORT}/" 200
  print_shared "$sdbh" "$sminh" "$buckets"
  echo "  테스트 계정: $WL_TEST_USER / $WL_TEST_PASSWORD   상태: $STATE_FILE   로그: $LOG_DIR"
  return $bad
}

# ---------------------------------------------------------------------------
# shared-down — 사람용. 저장소 DB·버킷이 남아 있거나 목록을 읽을 수 없으면 거부, --force 일 때만 공유 바탕을 내린다.
# 공용 락은 EXIT 트랩이 푼다.
# ---------------------------------------------------------------------------
cmd_shared_down() {
  lock_acquire
  if [ -z "$(shared_compose ps -aq 2>/dev/null || true)" ]; then
    log "공유 바탕(project=$WL_SHARED_PROJECT) 컨테이너가 없다 — 할 일 없음"; return 0
  fi
  local dbs="" bks="" dbh minh vflag="-v" what="볼륨까지 삭제"
  read -r dbh minh <<< "$(compose_health shared_compose db minio)"
  if [ "${WL_FORCE:-0}" != 1 ]; then
    # 안전하게 닫힌 쪽으로: 둘 다 healthy 가 아니면 남은 DB·버킷 목록을 믿을 수 없으니 --force 없이는 내리지 않는다.
    if [ "$dbh" != healthy ] || [ "$minh" != healthy ]; then
      log "공유 바탕이 healthy 가 아니다(db=$dbh, minio=$minh) — 남은 저장소 DB·버킷 목록을 읽을 수 없어 내리지 않는다."
      log "다른 저장소 스택이 쓰는 중일 수 있다. 상태를 확인한 뒤(docker compose -p $WL_SHARED_PROJECT ps) 모두 버려도 되면 --force"
      return 1
    fi
    dbs="$(shared_db_list)"; bks="$(shared_bucket_list)"
    if [ -n "$dbs$bks" ]; then
      log "공유 바탕에 저장소 DB·버킷이 남아 있다 — 다른 저장소 스택이 쓰는 중일 수 있어 내리지 않는다:"
      [ -n "$dbs" ] && echo "$dbs" | sed 's/^/    DB     /' >&2
      [ -n "$bks" ] && echo "$bks" | sed 's/^/    버킷   /' >&2
      log "각 저장소에서 down 한 뒤 다시 하거나, 모두 버려도 되면 --force"
      return 1
    fi
  fi
  if [ "${WL_KEEP_DATA:-0}" = 1 ]; then vflag=""; what="볼륨 유지"; fi
  log "공유 바탕 내림(project=$WL_SHARED_PROJECT, $what)"
  # shellcheck disable=SC2086  # vflag 는 비었거나 -v
  shared_compose down $vflag --remove-orphans
}

# 시험(WL_STACK_LIB=1 로 source)에서는 함수만 정의하고 명령을 실행하지 않는다.
if [ "${WL_STACK_LIB:-0}" = 1 ]; then return 0 2>/dev/null || exit 0; fi

# 어떤 식으로 끝나든(실패·Ctrl-C 포함) 내가 잡은 공용 락은 푼다.
trap 'lock_release; startup_lock_release' EXIT
trap 'exit 130' INT TERM HUP

case "$CMD" in
  up) cmd_up ;;
  down) cmd_down ;;
  status) cmd_status ;;
  seed) load_state; cmd_seed ;;
  logs) load_state; ls -1 "$LOG_DIR"/*.log 2>/dev/null || log "로그 없음" ;;
  shared-down) cmd_shared_down ;;
  # 사용법 = 머리 주석(2행부터 다음 「# ====」 줄까지)
  *) sed -n '2,/^# =====/p' "$0"; exit 2 ;;
esac
