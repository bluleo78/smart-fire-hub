#!/usr/bin/env bash
# work-loop-stack.sh 의 공유 포트 판정(check_shared_ports_free) 시험 — 가짜 docker·lsof·ss 만 쓴다.
# 왜: OrbStack 은 멈춘(Exited) 컨테이너도 docker ps -a 에 Ports 를 보여, 멈춘 공유 바탕을 남으로 오판했다(WD-942).
# 진짜 docker 는 부르지 않는다. 사용법: bash scripts/work-loop/stack.test.sh
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FAKE_DIR="$(mktemp -d)"
trap 'rm -rf "$FAKE_DIR"' EXIT
mkdir -p "$FAKE_DIR/bin"
export FAKE_DIR
export PATH="$FAKE_DIR/bin:$PATH"

# 가짜 docker — 인자에 따라 시험이 정한 파일 내용을 그대로 돌려준다(상태를 바꾸는 명령은 없다).
cat > "$FAKE_DIR/bin/docker" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *--filter*) cat "$FAKE_DIR/running_shared" ;;
  *'{{.Names}}'*) cat "$FAKE_DIR/all_named" ;;
  *) cat "$FAKE_DIR/all_ports" ;;
esac
EOF
# 가짜 lsof — 항상 출력 없음
printf '#!/usr/bin/env bash\nexit 0\n' > "$FAKE_DIR/bin/lsof"
# 가짜 ss — listen 파일에 적힌 포트마다 LISTEN 한 줄
cat > "$FAKE_DIR/bin/ss" <<'EOF'
#!/usr/bin/env bash
while read -r p; do
  [ -n "$p" ] && echo "LISTEN 0 4096 127.0.0.1:$p 0.0.0.0:*"
done < "$FAKE_DIR/listen"
exit 0
EOF
chmod +x "$FAKE_DIR/bin/docker" "$FAKE_DIR/bin/lsof" "$FAKE_DIR/bin/ss"

# 함수만 불러온다 — 상태 경로는 쓰지 않으므로(읽기만) 진짜 공유 바탕은 건드리지 않는다.
# source 앞의 임시 대입은 source 가 끝나면 사라지므로 LIVE_STACK_NAME 은 export 로 둔다.
export LIVE_STACK_NAME=work-loop
# 픽스처는 기본 project·포트(work-loop-shared, 6432·6900·6901)로 적었으므로 셸에 남은 시험용 덮어쓰기를 지운다.
unset WL_SHARED_PROJECT WL_SHARED_PG_PORT WL_SHARED_S3_PORT WL_SHARED_CONSOLE_PORT
WL_STACK_LIB=1 source "$HERE/../work-loop-stack.sh"
set +e  # source 한 스크립트의 set -e 를 풀어 실패 시험이 중간에 끊기지 않게 한다

DB_PORTS='127.0.0.1:6432->5432/tcp'
MINIO_PORTS='127.0.0.1:6900-6901->9000-9001/tcp'
DB_LINE="work-loop-shared-db-1 (project=work-loop-shared) $DB_PORTS"
MINIO_LINE="work-loop-shared-minio-1 (project=work-loop-shared) $MINIO_PORTS"
OTHER_LINE="other-db-1 (project=other) $DB_PORTS"

OUT=""
RC=0
# 공용 단언(check·finish)을 쓴다 — 반환값은 「rc=<값>」 문자열로, 출력은 따로 부분 문자열로 단언한다.
. "$HERE/../husky/test-helpers.sh"

# 시험 입력 파일을 다시 쓴다: setup <실행중 Ports> <ps -a Ports> <ps -a 이름줄> <listen 포트>
setup() {
  printf '%s\n' "$1" > "$FAKE_DIR/running_shared"
  printf '%s\n' "$2" > "$FAKE_DIR/all_ports"
  printf '%s\n' "$3" > "$FAKE_DIR/all_named"
  printf '%s\n' "$4" > "$FAKE_DIR/listen"
  unset WL_LISTEN_PORTS WL_DOCKER_PORTS
}

# 판정 함수를 부르고 반환값·출력(stderr 포함)을 저장한다.
run_check() {
  OUT="$(check_shared_ports_free "$@" 2>&1)"
  RC=$?
}

# expect <이름> <기대 반환값> [출력에 있어야 할 문자열]
expect() {
  check "$1" "rc=$RC" "rc=$2"
  [ -n "${3:-}" ] && check "$1 (출력)" "$OUT" "$3"
  return 0
}

# 1. 멈춘 공유 바탕만 포트를 보이면(아무도 안 듣는다) 내 것이니 통과
setup "" "$DB_PORTS, $MINIO_PORTS" "$DB_LINE
$MINIO_LINE" ""
run_check db minio
expect stopped_shared_only_passes 0 "다시 띄운다"

# 2. 멈춘 공유 바탕이 있어도 누가 실제로 그 포트를 듣고 있으면 실패
setup "" "$DB_PORTS, $MINIO_PORTS" "$DB_LINE
$MINIO_LINE" "6432"
run_check db minio
expect listening_with_stopped_shared_fails 1 "공유 포트 6432 를 공유 바탕(work-loop-shared)이 아닌 것이"

# 3. 다른 project 컨테이너가 포트를 잡고 있으면 실패
setup "" "$DB_PORTS" "$OTHER_LINE" ""
run_check db minio
expect other_project_container_fails 1

# 4. 실행 중인 공유 바탕은 그대로 통과
setup "$DB_PORTS, $MINIO_PORTS" "$DB_PORTS, $MINIO_PORTS" "$DB_LINE
$MINIO_LINE" "6432
6900
6901"
run_check db minio
expect running_shared_passes 0

# 5. 멈춘 공유 바탕과 남의 컨테이너가 같은 포트를 공개하면 실패(남이 섞였다)
setup "" "$DB_PORTS, $DB_PORTS" "$DB_LINE
$OTHER_LINE" ""
run_check db minio
expect stopped_shared_plus_other_fails 1

# 6. minio 만 쓰는 스택(--own-db)은 6432 를 남이 잡아도 상관없다
setup "" "$MINIO_PORTS, $DB_PORTS" "$MINIO_LINE
$OTHER_LINE" ""
run_check minio
expect own_db_minio_only_passes 0

finish
