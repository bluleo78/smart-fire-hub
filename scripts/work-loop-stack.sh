#!/usr/bin/env bash
# =============================================================================
# work-loop 격리 라이브 스택
#
# 사용법:
#   LIVE_STACK_NAME=work-loop scripts/work-loop-stack.sh up|down|status|seed|logs [옵션] [워크트리 경로]
#
#   up      : 격리 인프라(PostgreSQL+MinIO, 선택 Neo4j) → api(깨끗한 빌드 jar) → ai-agent → web(→ admin) 기동 후
#             테스트 계정을 시드한다. 이미 떠 있으면 실패한다(먼저 down).
#   down    : 상태 파일에 적힌 프로세스 그룹과 이 compose project 의 컨테이너만 내린다.
#             기본은 볼륨까지 삭제(다음 up 은 빈 DB + 전체 Flyway). --keep-data 면 볼륨 유지.
#   status  : 서비스별 pid·포트·헬스를 출력한다. 하나라도 비정상이면 종료코드 1.
#   seed    : 테스트 계정을 만든다(공개 가입이 열려 있을 때=사용자 0명) 그리고 로그인으로 확인한다.
#   logs    : 로그 파일 경로를 출력한다.
#
# 옵션(환경변수로도 가능):
#   --with-admin  / WL_WITH_ADMIN=1  : firehub-admin dev 서버도 띄운다.
#   --with-neo4j  / WL_WITH_NEO4J=1  : Neo4j(GraphRAG) 컨테이너도 띄운다.
#   --skip-build  / WL_SKIP_BUILD=1  : api jar·ai-agent dist 재빌드를 건너뛴다(직전 빌드 재사용 — 코드가 바뀌었으면 쓰지 말 것).
#   --keep-data   / WL_KEEP_DATA=1   : down 시 DB·MinIO 볼륨을 남긴다.
#
# 포트(기본값, 환경변수로 덮어쓰기 가능). 기본 dev 포트(5010/5020/5030/5040/5050/5173, DB 5432, MinIO 9000/9001,
# Neo4j 7474/7687)와 겹치지 않는 6xxx 대역을 쓴다. 6768 은 Orca 가 쓰므로 피한다.
#   WL_API_PORT=6010 WL_AGENT_PORT=6020 WL_WEB_PORT=6173 WL_ADMIN_PORT=6050
#   WL_DB_PORT=6432 WL_MINIO_PORT=6900 WL_MINIO_CONSOLE_PORT=6901 WL_NEO4J_HTTP_PORT=6474 WL_NEO4J_BOLT_PORT=6687
#
# 격리 원칙:
#   - 남의 포트를 죽이지 않는다(기존 pnpm dev 의 `lsof | kill -9` 와 다르다). 포트가 이미 쓰이면 실패로 알린다.
#   - docker compose project 이름 = $LIVE_STACK_NAME, 볼륨도 그 project 소속 명명 볼륨이다.
#   - 상태 파일은 <메인 저장소>/.work-loop/state/stack-$LIVE_STACK_NAME/ 에만 쓴다.
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
    --skip-build) WL_SKIP_BUILD=1 ;;
    --keep-data)  WL_KEEP_DATA=1 ;;
    -*) echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
    *) WORKTREE_ARG="$arg" ;;
  esac
done

if [ -z "${LIVE_STACK_NAME:-}" ]; then
  echo "LIVE_STACK_NAME 이 필요하다 (예: LIVE_STACK_NAME=work-loop $0 up)" >&2
  exit 2
fi
# compose project 이름 규칙(소문자·숫자·-·_)에 맞는지 확인 — 상태 디렉터리 이름에도 쓰이므로 엄격히 검사한다.
if ! [[ "$LIVE_STACK_NAME" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
  echo "LIVE_STACK_NAME 은 소문자·숫자·-·_ 만 허용: $LIVE_STACK_NAME" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 워크트리 경로: 인자 > 스크립트가 속한 저장소 루트
WORKTREE="$(cd "${WORKTREE_ARG:-$SCRIPT_DIR/..}" && pwd)"
[ -d "$WORKTREE/apps/firehub-api" ] || { echo "firehub 저장소가 아니다: $WORKTREE" >&2; exit 2; }

# 메인 저장소(공통 .git 의 부모) — 상태 파일은 워크트리가 아니라 여기에 둔다(워크트리 삭제와 무관하게 down 가능).
GIT_COMMON_DIR="$(git -C "$WORKTREE" rev-parse --path-format=absolute --git-common-dir)"
MAIN_REPO="$(dirname "$GIT_COMMON_DIR")"
STATE_DIR="$MAIN_REPO/.work-loop/state/stack-$LIVE_STACK_NAME"
LOG_DIR="$STATE_DIR/logs"
STATE_FILE="$STATE_DIR/stack.env"

# 포트 기본값
WL_API_PORT="${WL_API_PORT:-6010}"
WL_AGENT_PORT="${WL_AGENT_PORT:-6020}"
WL_WEB_PORT="${WL_WEB_PORT:-6173}"
WL_ADMIN_PORT="${WL_ADMIN_PORT:-6050}"
WL_DB_PORT="${WL_DB_PORT:-6432}"
WL_MINIO_PORT="${WL_MINIO_PORT:-6900}"
WL_MINIO_CONSOLE_PORT="${WL_MINIO_CONSOLE_PORT:-6901}"
WL_NEO4J_HTTP_PORT="${WL_NEO4J_HTTP_PORT:-6474}"
WL_NEO4J_BOLT_PORT="${WL_NEO4J_BOLT_PORT:-6687}"
export WL_DB_PORT WL_MINIO_PORT WL_MINIO_CONSOLE_PORT WL_NEO4J_HTTP_PORT WL_NEO4J_BOLT_PORT WL_WEB_PORT

# 테스트 계정 — 빈 격리 DB 의 첫 가입자는 시스템 ADMIN 이 된다(SignupTransaction).
WL_TEST_USER="${WL_TEST_USER:-workloop@example.com}"
WL_TEST_PASSWORD="${WL_TEST_PASSWORD:-WorkLoop1234}"
WL_TEST_NAME="${WL_TEST_NAME:-Work Loop}"

# api ↔ ai-agent 내부 토큰(격리값 — 개발 세션 토큰과 다르게 해 서로 섞이면 401 로 드러나게 한다)
WL_INTERNAL_TOKEN="work-loop-internal-token-$LIVE_STACK_NAME"

# 컨테이너 런타임 기본 소켓(OrbStack 등)에서 docker compose 를 쓴다. 다른 project 는 절대 지정하지 않는다.
compose() {
  # macOS 기본 bash 3.2 는 set -u 에서 빈 배열 전개가 오류라 문자열로 분기한다.
  if [ "${WL_WITH_NEO4J:-0}" = 1 ]; then
    docker compose -p "$LIVE_STACK_NAME" -f "$WORKTREE/scripts/work-loop/compose.yml" --profile graph "$@"
  else
    docker compose -p "$LIVE_STACK_NAME" -f "$WORKTREE/scripts/work-loop/compose.yml" "$@"
  fi
}

log() { echo "[work-loop-stack:$LIVE_STACK_NAME] $*"; }

# 포트가 LISTEN 중인가(누가 쓰든). 남의 프로세스는 절대 죽이지 않고 판정만 한다.
port_in_use() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

# HTTP 상태코드만 출력(실패 시 000)
http_code() { curl -s -o /dev/null -m 5 -w '%{http_code}' "$1" 2>/dev/null || echo 000; }

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

# 상태 파일 로드(없으면 빈 값)
load_state() { [ -f "$STATE_FILE" ] && . "$STATE_FILE" || true; }

# 프로세스 그룹 종료: TERM → 최대 20초 대기 → KILL
kill_group() {
  local name="$1" pgid="$2"
  [ -z "$pgid" ] && return 0
  if ! kill -0 "$pgid" 2>/dev/null && ! kill -0 -- "-$pgid" 2>/dev/null; then
    log "$name(pgid $pgid) 이미 종료됨"; return 0
  fi
  log "$name(pgid $pgid) 종료 중"
  kill -TERM -- "-$pgid" 2>/dev/null || kill -TERM "$pgid" 2>/dev/null || true
  for _ in $(seq 1 20); do
    kill -0 -- "-$pgid" 2>/dev/null || return 0
    sleep 1
  done
  log "$name 가 TERM 에 응답하지 않아 KILL"
  kill -KILL -- "-$pgid" 2>/dev/null || true
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
  # 격리 DB 에 한해 소유자 롤(app)로 SUPER_ADMIN 을 직접 부여한다(멱등).
  compose exec -T db psql -q -U app -d smartfirehub -v ON_ERROR_STOP=1 -c \
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
  if [ -f "$STATE_FILE" ]; then
    echo "이미 상태 파일이 있다($STATE_FILE) — 먼저 'LIVE_STACK_NAME=$LIVE_STACK_NAME $0 down' 을 실행할 것" >&2
    exit 1
  fi

  # 1) 포트 선점 검사 — 하나라도 쓰이면 아무것도 띄우지 않고 실패
  local ports=("$WL_DB_PORT" "$WL_MINIO_PORT" "$WL_MINIO_CONSOLE_PORT" "$WL_API_PORT" "$WL_AGENT_PORT" "$WL_WEB_PORT")
  [ "${WL_WITH_ADMIN:-0}" = 1 ] && ports+=("$WL_ADMIN_PORT")
  [ "${WL_WITH_NEO4J:-0}" = 1 ] && ports+=("$WL_NEO4J_HTTP_PORT" "$WL_NEO4J_BOLT_PORT")
  local busy=""
  for p in "${ports[@]}"; do if port_in_use "$p"; then busy="$busy $p"; fi; done
  if [ -n "$busy" ]; then
    echo "포트가 이미 사용 중이다:$busy — 다른 프로세스를 죽이지 않는다. 포트를 WL_*_PORT 로 바꾸거나 점유자를 확인할 것:" >&2
    for p in $busy; do lsof -nP -iTCP:"$p" -sTCP:LISTEN >&2 || true; done
    exit 1
  fi
  if [ -n "$(compose ps -aq 2>/dev/null)" ]; then
    echo "compose project '$LIVE_STACK_NAME' 컨테이너가 남아 있다 — 먼저 down" >&2
    exit 1
  fi

  rm -rf "$LOG_DIR"; mkdir -p "$LOG_DIR"
  : > "$STATE_FILE"
  save WORKTREE "$WORKTREE"
  save WL_API_PORT "$WL_API_PORT"; save WL_AGENT_PORT "$WL_AGENT_PORT"; save WL_WEB_PORT "$WL_WEB_PORT"
  save WL_ADMIN_PORT "$WL_ADMIN_PORT"; save WL_DB_PORT "$WL_DB_PORT"
  save WL_MINIO_PORT "$WL_MINIO_PORT"; save WL_MINIO_CONSOLE_PORT "$WL_MINIO_CONSOLE_PORT"
  save WL_NEO4J_HTTP_PORT "$WL_NEO4J_HTTP_PORT"; save WL_NEO4J_BOLT_PORT "$WL_NEO4J_BOLT_PORT"
  save WL_WITH_ADMIN "${WL_WITH_ADMIN:-0}"; save WL_WITH_NEO4J "${WL_WITH_NEO4J:-0}"
  save LOG_DIR "$LOG_DIR"

  # 실패 시 이미 띄운 것만 정리(이 스택 소유분만)
  trap 'log "기동 실패 — 이 스택이 띄운 것만 정리한다"; cmd_down || true' ERR

  # 2) 인프라 기동 — 빈 볼륨이면 빈 DB 가 만들어지고 Flyway 가 api 기동 시 전체 마이그레이션을 적용한다.
  log "인프라 기동(compose project=$LIVE_STACK_NAME)"
  compose up -d --build --wait db minio > "$LOG_DIR/compose-up.log" 2>&1 || { tail -30 "$LOG_DIR/compose-up.log" >&2; false; }
  compose up minio-init >> "$LOG_DIR/compose-up.log" 2>&1
  if [ "${WL_WITH_NEO4J:-0}" = 1 ]; then compose up -d neo4j >> "$LOG_DIR/compose-up.log" 2>&1; fi

  # 3) api 빌드 — bootRun 의 stale 바이트코드를 피하려고 clean 후 jar 를 만들고, 그 jar 사본을 상태 디렉터리에서 실행한다.
  #    jOOQ 생성 소스는 저장소에 추적되므로 generateJooq 는 필요 없다(스키마를 바꾼 브랜치는 그 브랜치가 직접 갱신).
  local jar="$STATE_DIR/api.jar"
  if [ "${WL_SKIP_BUILD:-0}" != 1 ] || [ ! -f "$jar" ]; then
    log "api 빌드(./gradlew clean bootJar) — 로그: $LOG_DIR/api-build.log"
    ( cd "$WORKTREE/apps/firehub-api" && ./gradlew clean bootJar -x test > "$LOG_DIR/api-build.log" 2>&1 ) \
      || { tail -30 "$LOG_DIR/api-build.log" >&2; false; }
    cp "$(ls "$WORKTREE"/apps/firehub-api/build/libs/*-SNAPSHOT.jar | grep -v plain | head -1)" "$jar"
  fi

  # 4) api 기동 — local 프로필의 고정값(DB 5432, JobRunr 대시보드 5040, agent 5020, MinIO 9000)을 전부 격리값으로 덮는다.
  #    명령행 인자는 Spring 속성 우선순위 최상위라 relaxed binding 추측 없이 확실히 덮인다.
  local db="jdbc:postgresql://127.0.0.1:$WL_DB_PORT/smartfirehub"
  local web_origin="http://localhost:$WL_WEB_PORT"
  # JWT 서명키는 기동마다 새로 만든다 — local 프로필 기본키를 그대로 쓰면 격리 스택 토큰이 개발 api(5010)에서도
  # 유효해져(같은 userId=1 이 존재) 검증 중 실수로 개발 데이터를 건드릴 수 있다.
  local jwt_secret
  jwt_secret="$(openssl rand -base64 64 | tr -d '\n')"
  local api_pgid
  api_pgid="$(spawn api "$STATE_DIR" java -Xmx1g -jar "$jar" \
    --spring.profiles.active=local \
    --server.port="$WL_API_PORT" \
    --app.jwt.secret="$jwt_secret" \
    --spring.datasource.url="$db" \
    --spring.flyway.url="$db" \
    --app.pipeline.datasource.url="$db" \
    --org.jobrunr.dashboard.enabled=false \
    --agent.url="http://127.0.0.1:$WL_AGENT_PORT" \
    --agent.internal-token="$WL_INTERNAL_TOKEN" \
    --app.base-url="$web_origin" \
    --app.cors.allowed-origins="$web_origin,http://127.0.0.1:$WL_WEB_PORT,http://localhost:$WL_ADMIN_PORT" \
    --firehub.minio.endpoint="http://127.0.0.1:$WL_MINIO_PORT" \
    --firehub.minio.public-endpoint="http://127.0.0.1:$WL_MINIO_PORT" \
    --firehub.file.upload-dir="$STATE_DIR/uploads" \
    --app.embedding.backlog-sweep-enabled=false)"
  save API_PGID "$api_pgid"
  log "api 기동(pgid $api_pgid, 포트 $WL_API_PORT) — 최초 기동은 전체 Flyway 마이그레이션으로 1~2분 걸린다"

  # 5) ai-agent — 개발용 .env.local 을 읽지 않도록 필요한 값을 전부 env 로 명시한다(dotenv 는 기존 env 를 덮지 않는다).
  #    AI 공급자 자격은 테넌트 설정에서 요청마다 오므로 없어도 기동한다(챗 응답만 불가).
  if [ "${WL_SKIP_BUILD:-0}" != 1 ] || [ ! -f "$WORKTREE/apps/firehub-ai-agent/dist/index.js" ]; then
    log "ai-agent 빌드(pnpm build)"
    ( cd "$WORKTREE/apps/firehub-ai-agent" && rm -rf dist && pnpm build > "$LOG_DIR/ai-agent-build.log" 2>&1 ) \
      || { tail -30 "$LOG_DIR/ai-agent-build.log" >&2; false; }
  fi
  mkdir -p "$STATE_DIR/xdg-data"
  local agent_pgid
  agent_pgid="$(spawn ai-agent "$WORKTREE/apps/firehub-ai-agent" env \
    PORT="$WL_AGENT_PORT" \
    INTERNAL_SERVICE_TOKEN="$WL_INTERNAL_TOKEN" \
    API_BASE_URL="http://127.0.0.1:$WL_API_PORT/api/v1" \
    MAX_TURNS=10 \
    XDG_DATA_HOME="$STATE_DIR/xdg-data" \
    NEO4J_URI="bolt://127.0.0.1:$WL_NEO4J_BOLT_PORT" \
    NEO4J_USER=neo4j NEO4J_PASSWORD=firehub-graph-dev \
    BRAND_NAME="Smart Fire Hub" \
    node dist/index.js)"
  save AGENT_PGID "$agent_pgid"

  # 6) web — 래퍼 설정으로 /api 프록시를 격리 api 로 돌린다. 127.0.0.1 에 strictPort 로 묶는다.
  local web_pgid
  web_pgid="$(spawn web "$WORKTREE/apps/firehub-web" env FIREHUB_API_PROXY_TARGET="http://127.0.0.1:$WL_API_PORT" \
    ./node_modules/.bin/vite --config "$WORKTREE/scripts/work-loop/vite.web.config.mts" \
    --host 127.0.0.1 --port "$WL_WEB_PORT" --strictPort)"
  save WEB_PGID "$web_pgid"

  if [ "${WL_WITH_ADMIN:-0}" = 1 ]; then
    local admin_pgid
    admin_pgid="$(spawn admin "$WORKTREE/apps/firehub-admin" env FIREHUB_API_PROXY_TARGET="http://127.0.0.1:$WL_API_PORT" \
      ./node_modules/.bin/vite --config "$WORKTREE/scripts/work-loop/vite.admin.config.mts" \
      --host 127.0.0.1 --port "$WL_ADMIN_PORT" --strictPort)"
    save ADMIN_PGID "$admin_pgid"
  fi

  # 7) 헬스 대기
  wait_http ai-agent "http://127.0.0.1:$WL_AGENT_PORT/agent/health" 200 60 "$agent_pgid"
  wait_http web "http://127.0.0.1:$WL_WEB_PORT/" 200 60 "$web_pgid"
  [ "${WL_WITH_ADMIN:-0}" = 1 ] && wait_http admin "http://127.0.0.1:$WL_ADMIN_PORT/" 200 60 "$admin_pgid"
  wait_http api "http://127.0.0.1:$WL_API_PORT/api/v1/auth/signup-status" 200 300 "$api_pgid"

  # 8) 테스트 계정
  cmd_seed
  trap - ERR
  log "기동 완료"
  cmd_status || true
}

# ---------------------------------------------------------------------------
# down — 상태 파일의 프로세스 그룹과 이 compose project 만 정리
# ---------------------------------------------------------------------------
cmd_down() {
  load_state
  kill_group admin "${ADMIN_PGID:-}"
  kill_group web "${WEB_PGID:-}"
  kill_group ai-agent "${AGENT_PGID:-}"
  kill_group api "${API_PGID:-}"
  # 상태 파일에 적힌 워크트리의 compose 파일을 쓴다(인자로 다른 워크트리를 넘겨도 같은 project 를 내린다).
  [ -n "${WORKTREE:-}" ] && [ -f "$WORKTREE/scripts/work-loop/compose.yml" ] || WORKTREE="$(cd "$SCRIPT_DIR/.." && pwd)"
  if [ "${WL_KEEP_DATA:-0}" = 1 ]; then
    log "컨테이너 정리(볼륨 유지)"
    WL_WITH_NEO4J=1 compose down --remove-orphans >/dev/null 2>&1 || true
  else
    log "컨테이너·볼륨 정리"
    WL_WITH_NEO4J=1 compose down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  rm -f "$STATE_FILE" "$STATE_DIR/signup.json" "$STATE_DIR/login.json"
  log "정리 완료 (로그는 $LOG_DIR 에 남김)"
}

# ---------------------------------------------------------------------------
# status — 서비스별 헬스
# ---------------------------------------------------------------------------
cmd_status() {
  load_state
  if [ ! -f "$STATE_FILE" ]; then
    log "기동 상태 아님(상태 파일 없음: $STATE_FILE)"
    [ -n "$(compose ps -aq 2>/dev/null)" ] && log "경고: compose project '$LIVE_STACK_NAME' 컨테이너가 남아 있다"
    return 1
  fi
  local bad=0
  row() { # 이름 pgid url 기대코드
    local name="$1" pgid="$2" url="$3" expect="$4" alive="-" code
    if [ -n "$pgid" ]; then kill -0 "$pgid" 2>/dev/null && alive="alive" || alive="DEAD"; fi
    code="$(http_code "$url")"
    local ok=OK; { [ "$code" = "$expect" ] && [ "$alive" != DEAD ]; } || { ok=FAIL; bad=1; }
    printf '  %-9s %-5s pgid=%-7s %-55s HTTP %s\n' "$name" "$ok" "${pgid:--}" "$url" "$code"
  }
  echo "스택 '$LIVE_STACK_NAME' (워크트리: ${WORKTREE:-?})"
  local dbh
  dbh="$(compose ps --format '{{.Service}} {{.Health}}' 2>/dev/null | awk '$1=="db"{print $2}')"
  local dbok=OK
  [ "$dbh" = healthy ] || { dbok=FAIL; bad=1; }
  printf '  %-9s %-5s %s\n' db "$dbok" "127.0.0.1:${WL_DB_PORT} ($dbh) app/app db=smartfirehub"
  row minio "" "http://127.0.0.1:${WL_MINIO_PORT}/minio/health/live" 200
  row api "${API_PGID:-}" "http://127.0.0.1:${WL_API_PORT}/api/v1/auth/signup-status" 200
  row ai-agent "${AGENT_PGID:-}" "http://127.0.0.1:${WL_AGENT_PORT}/agent/health" 200
  row web "${WEB_PGID:-}" "http://127.0.0.1:${WL_WEB_PORT}/" 200
  [ "${WL_WITH_ADMIN:-0}" = 1 ] && row admin "${ADMIN_PGID:-}" "http://127.0.0.1:${WL_ADMIN_PORT}/" 200
  [ "${WL_WITH_NEO4J:-0}" = 1 ] && row neo4j "" "http://127.0.0.1:${WL_NEO4J_HTTP_PORT}/" 200
  echo "  테스트 계정: $WL_TEST_USER / $WL_TEST_PASSWORD   로그: $LOG_DIR"
  return $bad
}

case "$CMD" in
  up) cmd_up ;;
  down) cmd_down ;;
  status) cmd_status ;;
  seed) load_state; cmd_seed ;;
  logs) load_state; ls -1 "$LOG_DIR"/*.log 2>/dev/null || log "로그 없음" ;;
  *) sed -n '2,25p' "$0"; exit 2 ;;
esac
