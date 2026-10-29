#!/usr/bin/env sh
# pre-push gate logic (#257)
# - push 직전 전체 회귀 안전망 (E2E 전체 + gradle 풀) — 실제 회귀는 full-regression.sh
# - pre-commit 단계에서 도메인 한정/smoke·변경분 gradle 로 우회된 테스트를 풀로 재검증
# - AI 자동화가 여러 커밋을 쌓아 push 할 때 누적 회귀를 차단한다
# 검증이 필요 없는 push 는 회귀를 건너뛴다 (WD-459, smart-workplace WP-446 이식) — ref 마다 실제로 올라가는 커밋 범위로 판정:
#   범위 = push 하는 커밋에서 <원격>/main 과 원격의 그 ref 가 가리키던 커밋(있으면)을 뺀 것.
#          (다른 원격 브랜치는 빼지 않는다 — 훅을 거치지 않고 올라간 커밋일 수 있다)
#   - 범위가 비었다(태그·삭제, 이미 main 에 있는 커밋) → 생략
#   - 범위의 변경이 모두 비코드(noncode.sh — pre-commit 과 같은 규칙) → 생략
#   - push 하는 커밋의 트리가 이미 전체 회귀를 통과한 트리(verified-trees.sh) → 생략
#     범위를 줄여 통과한 트리(gate-scope.sh)는 그 커밋에 지금 필요한 범위(<원격>/main 기준)를 덮을 때만 인정한다.
#   ref 하나라도 위에 해당하지 않으면 전체 회귀. stdin 이 비었거나 터미널이면(직접 실행) 전체 회귀.
# 인자: $1 = 원격 이름(git 이 넘긴다), stdin = `<local ref> <local sha> <remote ref> <remote sha>` 줄들.
# 환경변수:
#   PREPUSH_DRY_RUN       — 판정만 출력하고 회귀는 돌리지 않는다(테스트용)
#   PREPUSH_VERIFIED_FILE — 검증 완료 트리 기록 파일(verified-trees.sh)

set -e

HOOK_DIR=$(dirname "$0")
. "$HOOK_DIR/noncode.sh"
. "$HOOK_DIR/gate-scope.sh"
. "$HOOK_DIR/verified-trees.sh"

REMOTE="${1:-origin}"

# run_full <사유> [범위] — 전체 회귀로 넘어간다(dry-run 이면 표시만). 범위를 주면 full-regression.sh 가 그 범위로 돈다.
# 범위를 주지 않으면(push 정보 없음·직접 실행) full-regression.sh 가 HEAD 기준으로 스스로 정한다(pnpm test:full 과 같다).
run_full() {
  echo "[pre-push] 전체 회귀 실행 — $1"
  if [ -n "${2:-}" ]; then FULL_REGRESSION_SCOPE=$2; export FULL_REGRESSION_SCOPE; fi
  if [ -n "$PREPUSH_DRY_RUN" ]; then
    echo "[pre-push][dry-run] full-regression.sh (범위 ${FULL_REGRESSION_SCOPE:-스스로 판정})"
    exit 0
  fi
  exec "$HOOK_DIR/full-regression.sh"
}

# is_zero_sha <sha> — 0 으로만 된 sha(없는 ref)인가.
is_zero_sha() { case "$1" in *[!0]*) return 1 ;; esac; }

# skip <ref 이름> <사유> — 생략 사유를 쌓는다.
skip() { SKIP_REASONS="${SKIP_REASONS}$1: $2; "; }

# 터미널에서 직접 실행하면 push 정보가 없다 — read 가 입력을 기다리며 멈추지 않게 바로 전체 회귀.
[ ! -t 0 ] || run_full "직접 실행 (push 전 회귀는 pnpm test:full)"

# 락을 잡기 전에 stdin 을 다 읽고 판정한다 — 건너뛸 push 가 다른 세션의 회귀를 기다리지 않게.
SEEN=0
SKIP_REASONS=""
NEED_REFS=""
NEED_GATE=""
# 원격 main(마지막 fetch 기준) — 범위에서 빼는 기준. 없으면 쓰지 않는다.
REMOTE_MAIN=""
git rev-parse --verify --quiet "refs/remotes/$REMOTE/main" >/dev/null && REMOTE_MAIN="refs/remotes/$REMOTE/main"
while read -r local_ref local_sha remote_ref remote_sha; do
  [ -n "$local_ref" ] || continue
  SEEN=1
  name=${local_ref#refs/*/}
  # 원격 ref 삭제(로컬 sha 가 0) — 올라가는 커밋이 없다.
  if is_zero_sha "$local_sha"; then skip "$name" "삭제"; continue; fi

  commit=$(git rev-parse --verify --quiet "$local_sha^{commit}" || true)
  [ -n "$commit" ] || run_full "$name: 커밋을 가리키지 않음"

  # 뺄 기준: 원격 main + (있으면) 원격 ref 의 이전 값. 로컬에 없는 원격 sha 는 쓸 수 없으니 빼지 않는다(범위가 커질 뿐).
  # sha·ref 이름엔 공백이 없어 단어 분리로 넘겨도 안전하다.
  # 원격 ref 의 이전 값은 fast-forward 일 때만 뺀다 — 이력을 바꾼 force-push(reset·rebase)는 새 트리가 검증되지 않았을 수 있다.
  NOT_REFS="$REMOTE_MAIN"
  if ! is_zero_sha "$remote_sha" && git cat-file -e "$remote_sha^{commit}" 2>/dev/null \
    && git merge-base --is-ancestor "$remote_sha" "$commit"; then NOT_REFS="$NOT_REFS $remote_sha"; fi

  # 범위의 커밋과 바꾼 파일을 한 번에 — 머지 커밋은 첫 부모 대비(충돌 해결분 포함), 삭제·이름 변경 전후 경로도 포함.
  # 한글 경로가 따옴표·8진수로 바뀌지 않게 quotePath 를 끈다.
  LOG=$(git -c core.quotePath=false log --format='@%H' --name-only --no-renames --diff-merges=first-parent "$commit" --not $NOT_REFS)
  if [ -z "$LOG" ]; then
    skip "$name" "새 커밋 없음"; continue
  fi

  if [ -z "$(code_files "$(printf '%s\n' "$LOG" | grep -v '^@')")" ]; then
    skip "$name" "비코드 변경만"; continue
  fi

  tree=$(git rev-parse "$commit^{tree}")
  # 이 커밋에 필요한 범위 — 게이트 때 기록한 범위를 그대로 믿지 않고 지금 원격 main 기준으로 다시 구한다.
  if [ -n "$REMOTE_MAIN" ]; then need=$(gate_scope "$REMOTE_MAIN" "$commit"); else need=full; fi
  if is_verified "$tree" "$need"; then
    skip "$name" "이미 전체 회귀를 통과한 트리 $(printf %.12s "$tree") (필요 범위 $need)"; continue
  fi

  # 회귀는 작업 트리(HEAD)를 검사한다. push 하는 커밋이 HEAD 이고 기준이 origin/main 일 때만 그 범위로 줄이고,
  # 아니면(HEAD 가 아닌 ref·다른 원격) 전체로 돈다 — HEAD 나 다른 기준으로 고른 범위가 이 커밋에 필요한 범위보다 좁을 수 있다.
  # 회귀가 필요한 ref 를 모두 모은 뒤 한 번 돈다 — 첫 ref 에서 바로 넘어가면 뒤 ref 가 판정되지 않는다.
  gate=full
  if [ "$REMOTE_MAIN" = refs/remotes/origin/main ] && [ "$commit" = "$(git rev-parse HEAD)" ]; then gate=$need; fi
  NEED_REFS="${NEED_REFS}$name "
  if [ -z "$NEED_GATE" ]; then NEED_GATE=$gate; else NEED_GATE=$(scope_union "$NEED_GATE" "$gate"); fi
done

# git 은 올릴 ref 가 있을 때만 훅을 부른다 — 빈 stdin 은 직접 실행(파이프·에이전트 셸)이다.
[ "$SEEN" -eq 1 ] || run_full "push 정보 없음 — 직접 실행으로 본다 (push 전 회귀는 pnpm test:full)"
[ -z "$NEED_GATE" ] || run_full "코드 변경 — ${NEED_REFS}(생략: ${SKIP_REASONS:-없음})" "$NEED_GATE"

echo "[pre-push] 회귀 생략 — $SKIP_REASONS"
