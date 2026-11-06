#!/usr/bin/env sh
# 기차(개발자 통합 브랜치) (WP-1216 → WD-506 이식) — 한 AI 가 끝낸 이슈들을 통합 브랜치 `integ/<AI 이름>-<번호>` 에 이슈당 커밋 하나로 쌓아,
# push 게이트를 기차마다 한 번 돌리고 PR 하나로 올린다. 게이트 락이 저장소마다 하나라 이슈마다 게이트를 띄우면 줄이 길어지고,
# 게이트만 합쳐 돌린 뒤 PR 을 이슈마다 내면 A+B 로 통과한 뒤 B 가 반려될 때 A 혼자의 트리가 검증 없이 머지된다.
# 기차는 돌린 트리 그대로를 PR 로 올리므로 그 틈이 없다.
#
# 사용(저장소의 아무 워크트리 안에서, 루트에서는 `pnpm train …` 과 같다):
#   sh scripts/husky/train.sh stack <AI 이름> <feature 브랜치> <이슈 키>  — 다음 기차에 그 브랜치를 커밋 하나로 쌓는다(없으면 만든다)
#   sh scripts/husky/train.sh rebase <기차 브랜치>                         — 출발 전이면 최신 origin/main 으로, 출발했으면 충돌할 때만
#   sh scripts/husky/train.sh next <AI 이름>                               — 아직 출발하지 않은 기차 이름(없으면 빈 출력)
#   sh scripts/husky/train.sh keys <기차 브랜치>                           — 실린 이슈 키를 쌓은 순서로 「K1·K2」(PR 제목 `통합 K1·K2`)
#
# - 「출발」 = 기차 브랜치를 origin 에 push 한 것(origin/<기차> 가 있음). 출발한 기차에는 새 이슈를 쌓지 않는다 — 다음 번호로 새 기차를 만든다.
# - 기차마다 워크트리 MAIN/.claude/worktrees/integ-<AI 이름>-<번호> 를 둔다. 게이트·수정 커밋·push 는 그 안에서 한다.
# - 쌓기는 feature 의 마지막 커밋 메시지(제목 끝 `(<이슈 키>)`)와 작성자를 그대로 쓴다. 쌓는 커밋은 커밋 훅을 다시 돌지 않는다 —
#   같은 변경을 feature 커밋 때 훅이 이미 봤고, 쌓은 결과 전체는 출발 때 push 게이트가 본다.
# - 마이그레이션 번호가 origin/main·앞서 쌓은 이슈와 겹치면 쌓지 않고 멈춘다(번호를 feature 에서 옮기고
#   jOOQ 생성 소스를 다시 만든 뒤 다시 쌓는다 — 이 저장소는 생성 소스를 추적한다).
# git 은 origin 에서 한 번 fetch 한다(TRAIN_NO_FETCH=1 이면 건너뜀). 커밋 신원은 호출한 환경의 것을 쓴다(rebase 가 committer 를 쓴다).

set -e

HOOK_DIR=$(cd "$(dirname "$0")" && pwd)
BASE=refs/remotes/origin/main
# 번호를 옮긴 뒤 같은 커밋에 넣어야 하는 jOOQ 생성 소스 안내 — 멈추는 메시지마다 붙인다.
JOOQ_HINT='(번호를 옮기면 cd apps/firehub-api && ./gradlew generateJooq 결과 src/main/generated/ 를 같은 커밋에 넣는다)'

# 커밋 신원이 없는 환경(AI Pod 등)에서도 쌓고 rebase 한다 — committer 만 고정값으로 채운다.
# 작성자는 쌓을 때 feature 의 것을 그대로 쓰고, rebase 는 원래 작성자를 지킨다.
if ! git var GIT_COMMITTER_IDENT >/dev/null 2>&1; then
  export GIT_COMMITTER_NAME=train GIT_COMMITTER_EMAIL=train@localhost
fi

say() { echo "[train] $*"; }
die() { echo "[train] $*" >&2; exit "${2:-1}"; }

usage() {
  cat >&2 <<'EOF'
사용: sh scripts/husky/train.sh stack <AI 이름> <feature 브랜치> <이슈 키>
      sh scripts/husky/train.sh rebase <기차 브랜치>
      sh scripts/husky/train.sh next <AI 이름>
      sh scripts/husky/train.sh keys <기차 브랜치>
EOF
  exit 2
}

COMMON=$(cd "$(git rev-parse --git-common-dir)" && pwd)
MAIN_DIR=$(dirname "$COMMON")

fetch() {
  [ "${TRAIN_NO_FETCH:-}" = 1 ] && return 0
  git fetch --quiet origin || say "origin fetch 실패 — 가진 원격 정보로 판정합니다"
}

check_name() {
  # 이름이 정규식(train_numbers)에 들어가므로 . 같은 메타 문자는 받지 않는다.
  case "$1" in ''|*[!A-Za-z0-9_-]*) die "AI 이름은 영문·숫자·_·- 만: '$1'" 2 ;; esac
}

# 기차 번호들(로컬·origin) — integ/<이름>-<번호> 의 번호만 한 줄씩.
train_numbers() {
  git for-each-ref --format='%(refname)' "refs/heads/integ/" "refs/remotes/origin/integ/" |
    sed -e 's#^refs/heads/##' -e 's#^refs/remotes/origin/##' |
    sed -n "s#^integ/$1-\([0-9][0-9]*\)\$#\1#p" | sort -n -u
}

# 출발 = origin 에 push 함. 원격 브랜치가 머지 뒤 지워지고 prune 돼도 push -u 가 남긴 upstream 설정으로 알아본다 —
# 머지된 기차에 새 이슈를 쌓지 않게.
departed() {
  git rev-parse --verify --quiet "refs/remotes/origin/$1" >/dev/null ||
    [ "$(git config --get "branch.$1.merge" || true)" = "refs/heads/$1" ]
}

worktree_of() { echo "$MAIN_DIR/.claude/worktrees/$(echo "$1" | tr '/' '-')"; }

# 아직 출발하지 않은(origin 에 없는) 로컬 기차 중 가장 낮은 번호.
next_train() {
  for n in $(train_numbers "$1"); do
    b="integ/$1-$n"
    git rev-parse --verify --quiet "refs/heads/$b" >/dev/null || continue
    departed "$b" || { echo "$b"; return 0; }
  done
}

# 기차 워크트리 — 없으면 만든다(브랜치가 없으면 origin/main 에서). 커밋 안 한 변경이 있으면 멈춘다.
ensure_worktree() {
  b=$1; wt=$(worktree_of "$b")
  if [ ! -d "$wt" ]; then
    if git rev-parse --verify --quiet "refs/heads/$b" >/dev/null; then
      git worktree add --quiet "$wt" "$b"
    else
      git worktree add --quiet --no-track -b "$b" "$wt" "$BASE"
    fi
  fi
  [ -z "$(git -C "$wt" status --porcelain)" ] || die "$b 워크트리에 커밋 안 한 변경이 있습니다: $wt"
  echo "$wt"
}

# 마이그레이션 번호 — 1(겹침)이면 실패, 2(읽지 못함)는 알리고 넘어간다.
migrations_ok() {
  rc=0
  (cd "$1" && node "$HOOK_DIR/../check-migration-versions.mjs" "$BASE" "$2") || rc=$?
  [ "$rc" -ne 1 ] || return 1
  [ "$rc" -eq 0 ] || say "마이그레이션 번호를 확인하지 못함(종료 코드 $rc) — 넘어갑니다"
  return 0
}

cmd_stack() {
  [ $# -eq 3 ] || usage
  name=$1; feature=$2; key=$3
  check_name "$name"
  echo "$key" | grep -Eq '^[A-Z][A-Z0-9]*-[0-9]+$' || die "이슈 키 형식이 아닙니다: '$key'" 2
  fetch
  git rev-parse --verify --quiet "$BASE" >/dev/null || die "origin/main 이 없습니다 — git fetch origin 뒤 다시" 2
  fsha=$(git rev-parse --verify --quiet "$feature^{commit}") || die "커밋을 찾을 수 없음: $feature" 2
  subject=$(git log -1 --format=%s "$fsha")
  case "$subject" in *"($key)") : ;; *) die "$feature 의 마지막 커밋 제목 끝에 ($key) 가 없습니다 — 「$subject」(커밋 하나로 합친 뒤 쌓습니다)" ;; esac

  train=$(next_train "$name")
  if [ -z "$train" ]; then
    last=$(train_numbers "$name" | tail -n 1)
    train="integ/$name-$(( ${last:-0} + 1 ))"
    say "새 기차 $train 을 origin/main 에서 만듭니다"
  fi
  wt=$(ensure_worktree "$train")
  # 출발 전 기차는 최신 origin/main 위에서 쌓는다 — 더 새 main 에서 딴 feature 를 낡은 기차에 합치면 그 사이 main 변경이 이 이슈의 커밋에 섞인다.
  TRAIN_NO_FETCH=1 cmd_rebase "$train" >/dev/null || die "$train 을 최신 origin/main 으로 옮기지 못해 쌓지 않습니다 — sh scripts/husky/train.sh rebase $train 로 확인하세요"
  if git -C "$wt" log --format=%s "$BASE..HEAD" | grep -Fq "($key)"; then
    die "$key 는 이미 $train 에 실려 있습니다 — 고친 것은 기차 워크트리에서 제목 끝에 ($key) 를 단 수정 커밋으로 더합니다"
  fi

  # 풀지 못한 파일이 남으면 충돌 — origin/main 과 바로 충돌하는지로 이유를 가른다.
  if ! git -C "$wt" merge --squash --quiet "$fsha" >/dev/null 2>&1; then
    unmerged=$(git -C "$wt" diff --name-only --diff-filter=U 2>/dev/null || true)
    git -C "$wt" reset --hard --quiet HEAD
    [ -n "$unmerged" ] || die "$feature: 합치지 못함(충돌 아님) — git merge --squash 를 기차 워크트리에서 직접 보세요: $wt"
    if ! git merge-tree --write-tree "$BASE" "$fsha" >/dev/null 2>&1; then
      die "$feature: origin/main 과 충돌 — feature 를 rebase 해 푼 뒤 다시 쌓습니다(파일: $(echo $unmerged))"
    fi
    die "$feature: 앞서 실린 이슈와 충돌 — 다음 기차로 미루거나 feature 에서 풀어 다시 쌓습니다(파일: $(echo $unmerged))"
  fi
  if git -C "$wt" diff --cached --quiet; then
    git -C "$wt" reset --hard --quiet HEAD
    die "$feature: 기차에 더할 변경이 없습니다(이미 들어 있음)"
  fi

  # 커밋 하나 — feature 의 메시지·작성자를 그대로(쌓는 사람은 committer). 번호가 겹치면 브랜치를 옮기지 않고 되돌린다.
  tree=$(git -C "$wt" write-tree)
  msg=$(mktemp); git log -1 --format=%B "$fsha" > "$msg"
  commit=$(GIT_AUTHOR_NAME=$(git log -1 --format=%an "$fsha") GIT_AUTHOR_EMAIL=$(git log -1 --format=%ae "$fsha") \
    GIT_AUTHOR_DATE=$(git log -1 --format=%aI "$fsha") git -C "$wt" commit-tree "$tree" -p HEAD -F "$msg") || {
    rm -f "$msg"; git -C "$wt" reset --hard --quiet HEAD; die "커밋을 만들지 못했습니다(커밋 신원 확인)"
  }
  rm -f "$msg"
  if ! migrations_ok "$wt" "$commit"; then
    git -C "$wt" reset --hard --quiet HEAD
    die "마이그레이션 번호가 겹칩니다 — $feature 에서 번호를 옮긴 뒤 다시 쌓습니다 $JOOQ_HINT"
  fi
  git -C "$wt" reset --hard --quiet "$commit"
  say "$key → $train 에 쌓음 $(printf %.9s "$commit") 「$subject」"
  say "기차 워크트리: $wt"
}

cmd_rebase() {
  [ $# -eq 1 ] || usage
  train=$1
  case "$train" in integ/*) : ;; *) die "기차 브랜치(integ/…)가 아닙니다: $train" 2 ;; esac
  git rev-parse --verify --quiet "refs/heads/$train" >/dev/null || die "기차 브랜치가 없습니다: $train" 2
  fetch
  git rev-parse --verify --quiet "$BASE" >/dev/null || die "origin/main 이 없습니다 — git fetch origin 뒤 다시" 2
  wt=$(ensure_worktree "$train")
  if git -C "$wt" merge-base --is-ancestor "$BASE" HEAD; then
    say "$train: 이미 최신 origin/main 위입니다"; return 0
  fi
  # 출발한 기차는 충돌할 때만 — rebase 하면 트리가 바뀌어 게이트를 다시 돌아야 한다.
  if departed "$train" && git merge-tree --write-tree "$BASE" "$train" >/dev/null 2>&1; then
    # 줄 충돌이 없어도 그 사이 main 이 같은 마이그레이션 번호를 더했으면 함께 들어가 Flyway 가 깨진다 — 번호는 따로 본다.
    migrations_ok "$wt" HEAD || die "$train: origin/main 과 마이그레이션 번호가 겹칩니다 — 그 이슈의 번호를 옮기는 수정 커밋을 더합니다 $JOOQ_HINT"
    say "$train: 출발한 기차 — origin/main 과 충돌하지 않아 rebase 하지 않습니다"; return 0
  fi
  log=$(mktemp)
  if ! git -C "$wt" rebase --quiet "$BASE" >"$log" 2>&1; then
    stopped=$(git -C "$wt" log -1 --format=%s REBASE_HEAD 2>/dev/null || true)
    unmerged=$(git -C "$wt" diff --name-only --diff-filter=U 2>/dev/null || true)
    git -C "$wt" rebase --abort >/dev/null 2>&1 || true
    if [ -z "$unmerged" ]; then
      # 충돌이 아닌 실패(신원·훅·잠금 등)는 충돌로 알리지 않고 git 이 낸 이유를 보인다.
      reason=$(grep -v '^[[:space:]]*$' "$log" | tail -n 3 | tr '\n' ' '); rm -f "$log"
      die "$train: rebase 하지 못함(충돌 아님) — git: $reason"
    fi
    rm -f "$log"
    die "$train: rebase 중 충돌 — 「$stopped」($(echo $unmerged)) — 기차 워크트리에서 git rebase origin/main 으로 풀어 주세요: $wt"
  fi
  rm -f "$log"
  migrations_ok "$wt" HEAD || die "$train: rebase 뒤 마이그레이션 번호가 origin/main 과 겹칩니다 — 그 이슈의 번호를 옮기는 수정 커밋을 더합니다 $JOOQ_HINT"
  if departed "$train"; then
    say "$train: rebase 했습니다 — 게이트(pnpm test:full)를 다시 돈 뒤 git push --force-with-lease 합니다"
  else
    say "$train: 최신 origin/main 으로 rebase 했습니다"
  fi
}

cmd_next() {
  [ $# -eq 1 ] || usage
  check_name "$1"
  fetch
  next_train "$1"
}

cmd_keys() {
  [ $# -eq 1 ] || usage
  git rev-parse --verify --quiet "$1^{commit}" >/dev/null || die "커밋을 찾을 수 없음: $1" 2
  git log --reverse --format=%s "$BASE..$1" |
    sed -n 's/.*(\([A-Z][A-Z0-9]*-[0-9][0-9]*\))$/\1/p' |
    awk '!seen[$0]++ { out = out (out == "" ? "" : "·") $0 } END { if (out != "") print out }'
}

[ $# -ge 1 ] || usage
cmd=$1; shift
case "$cmd" in
  stack) cmd_stack "$@" ;;
  rebase) cmd_rebase "$@" ;;
  next) cmd_next "$@" ;;
  keys) cmd_keys "$@" ;;
  *) usage ;;
esac
