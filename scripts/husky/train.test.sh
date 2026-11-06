#!/usr/bin/env sh
# 기차(train.sh) 검증 (WP-1216 → WD-506 이식) — 임시 git 저장소(+ bare 원격)에 실제 브랜치를 만들어
#   1) 쌓기: 이슈당 커밋 하나(메시지·작성자 유지), 같은 이슈 다시 쌓기·제목에 키 없음 거부, 실린 키 목록
#   2) 멈춤: 마이그레이션 번호 겹침(jOOQ 재생성 안내 포함)·앞서 실린 이슈와 충돌에서 쌓지 않음
#   3) 출발: push 한 기차에는 쌓지 않고 다음 번호 기차를 만든다
#   4) rebase: 출발 전에는 최신 origin/main 으로, 출발한 기차는 충돌할 때만
# 을 단언한다.
#
# 사용: 리포지토리 루트에서 `sh scripts/husky/train.test.sh` (`pnpm test:hooks` 가 함께 돈다)

set -e
HOOKS="$(cd "$(dirname "$0")" && pwd)"
. "$HOOKS/test-helpers.sh"

# 커밋 훅 안에서 돌 때 git 이 넘긴 GIT_INDEX_FILE 등을 지운다 — 임시 저장소의 git 명령이 실제 커밋의 인덱스를 건드리지 않게.
unset GIT_INDEX_FILE GIT_DIR GIT_WORK_TREE GIT_PREFIX GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t
# 사용자 전역 git 설정(서명·훅 경로 등)이 끼어들지 않게 끈다.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1

git init -q --bare "$TMP/remote.git"
git init -q -b main "$TMP/work"
cd "$TMP/work"
git remote add origin "$TMP/remote.git"
printf '.claude/worktrees/\n' > .git/info/exclude
M=apps/firehub-api/src/main/resources/db/migration
mkdir -p "$M"
printf 'a\n' > "$M/V1__init.sql"
printf 'base\n' > shared.txt
git add -A && git commit -qm "init"
git push -q origin main
TRAIN="sh $HOOKS/train.sh"

# feature <브랜치> <이슈 키> <경로> <내용> — origin/main 에서 브랜치를 만들어 커밋 하나(제목 끝에 키)를 둔다.
feature() {
  git checkout -q -b "$1" origin/main
  mkdir -p "$(dirname "$3")"
  printf '%s\n' "$4" > "$3"
  git add -A
  GIT_AUTHOR_NAME=dev GIT_AUTHOR_EMAIL=dev@x git commit -qm "fix(web): $1 고침 ($2)" -m "본문 $2"
  git checkout -q main
}
WT="$TMP/work/.claude/worktrees/integ-ai-1"

# ---- 1) 쌓기 ----
feature f-a WD-1 a.txt 'a'
git checkout -q f-a; printf 'a2\n' > a.txt; git commit -qam "wip"; git commit -q --amend -m "fix(web): f-a 고침 (WD-1)"; git checkout -q main
feature f-b WD-2 b.txt 'b'
out=$($TRAIN stack ai f-a WD-1 2>&1)
check "첫 쌓기 — 새 기차를 origin/main 에서 만든다" "$out" "새 기차 integ/ai-1"
out=$($TRAIN stack ai f-b WD-2 2>&1)
check "둘째 쌓기 — 같은 기차" "$out" "WD-2 → integ/ai-1" "새 기차"
check "이슈당 커밋 하나" "$(git log --format=%s origin/main..integ/ai-1 | wc -l | tr -d ' ')" "2"
check "커밋 제목·작성자 유지" "$(git log -1 --format='%s|%an|%b' integ/ai-1)" "fix(web): f-b 고침 (WD-2)|dev|본문 WD-2"
check "쌓은 내용" "$(git show integ/ai-1:a.txt)" "a2"
check "실린 키 — 쌓은 순서" "$($TRAIN keys integ/ai-1)" "WD-1·WD-2"
out=$($TRAIN stack ai f-a WD-1 2>&1 || true)
check "같은 이슈 다시 쌓기 거부" "$out" "이미 integ/ai-1 에 실려"
git checkout -q -b f-nokey origin/main; printf 'x\n' > x.txt; git add -A; git commit -qm "fix: 키 없음"; git checkout -q main
out=$($TRAIN stack ai f-nokey WD-9 2>&1 || true)
check "제목 끝에 키가 없으면 거부" "$out" "(WD-9) 가 없습니다"
check "기차 워크트리는 깨끗" "[$(git -C "$WT" status --porcelain)]" "[]"

# ---- 2) 멈춤 ----
feature f-m1 WD-3 "$M/V2__a.sql" 'm1'
feature f-m2 WD-4 "$M/V2__b.sql" 'm2'
$TRAIN stack ai f-m1 WD-3 >/dev/null 2>&1
before=$(git rev-parse integ/ai-1)
out=$($TRAIN stack ai f-m2 WD-4 2>&1 || true)
check "마이그레이션 번호 겹침 — 쌓지 않고 멈춤" "$out" "마이그레이션 번호가 겹칩니다"
check "번호 겹침 — jOOQ 재생성 안내" "$out" "./gradlew generateJooq"
check "번호 겹침 — 기차는 그대로" "$(git rev-parse integ/ai-1)" "$before"
check "번호 겹침 — 워크트리 깨끗" "[$(git -C "$WT" status --porcelain)]" "[]"
feature f-c1 WD-5 shared.txt 'c1'
feature f-c2 WD-6 shared.txt 'c2'
$TRAIN stack ai f-c1 WD-5 >/dev/null 2>&1
out=$($TRAIN stack ai f-c2 WD-6 2>&1 || true)
check "앞서 실린 이슈와 충돌 — 쌓지 않음" "$out" "앞서 실린 이슈와 충돌"
check "충돌 — 워크트리 깨끗" "[$(git -C "$WT" status --porcelain)]" "[]"

# ---- 4) rebase(출발 전) — main 이 움직이면 최신 origin/main 위로 ----
git checkout -q main; printf 'm\n' > main.txt; git add -A; git commit -qm "main 진행"; git push -q origin main
out=$($TRAIN rebase integ/ai-1 2>&1)
check "출발 전 — 최신 origin/main 으로 rebase" "$out" "최신 origin/main 으로 rebase 했습니다"
check "rebase 뒤 main 위" "$(git merge-base --is-ancestor origin/main integ/ai-1 && echo yes)" "yes"
check "rebase 뒤 실린 키 유지" "$($TRAIN keys integ/ai-1)" "WD-1·WD-2·WD-3·WD-5"
out=$($TRAIN rebase integ/ai-1 2>&1)
check "이미 최신이면 그대로" "$out" "이미 최신"

# main 이 실린 이슈와 충돌하면 rebase 를 되돌리고 알린다
git checkout -q main; printf 'main-side\n' > shared.txt; git commit -qam "main 이 shared 를 고침"; git push -q origin main
before=$(git rev-parse integ/ai-1)
out=$($TRAIN rebase integ/ai-1 2>&1 || true)
check "rebase 충돌 — 멈추고 커밋을 알림" "$out" "rebase 중 충돌"
check "rebase 충돌 — 기차는 그대로" "$(git rev-parse integ/ai-1)" "$before"
# 충돌 커밋(WD-5)을 기차에서 빼 정리한다(다음 단계용)
git -C "$WT" reset -q --hard HEAD~1
$TRAIN rebase integ/ai-1 >/dev/null 2>&1

# ---- 3) 출발 — push 한 기차에는 쌓지 않고 다음 기차 ----
git -C "$WT" push -q origin integ/ai-1
check "출발한 기차는 next 에 없음" "[$($TRAIN next ai)]" "[]"
feature f-d WD-7 d.txt 'd'
out=$($TRAIN stack ai f-d WD-7 2>&1)
check "출발 뒤 쌓기 — 다음 번호 기차" "$out" "새 기차 integ/ai-2"
check "다음 기차에는 그 이슈만" "$($TRAIN keys integ/ai-2)" "WD-7"
check "next — 출발 전 기차" "$($TRAIN next ai)" "integ/ai-2"

# ---- 4) rebase(출발 뒤) — 충돌 없으면 하지 않는다 ----
git checkout -q main; printf 'm2\n' > main2.txt; git add -A; git commit -qm "main 또 진행"; git push -q origin main
before=$(git rev-parse integ/ai-1)
out=$($TRAIN rebase integ/ai-1 2>&1)
check "출발한 기차 — 충돌 없으면 rebase 하지 않음" "$out" "충돌하지 않아 rebase 하지 않습니다"
check "출발한 기차 — 그대로" "$(git rev-parse integ/ai-1)" "$before"
# 충돌하면 rebase 한다(게이트 다시 안내)
git -C "$WT" checkout -q integ/ai-1
printf 'train-side\n' > "$WT/main2.txt"; git -C "$WT" add -A; git -C "$WT" commit -qm "fix(web): 수정 (WD-1)"
git checkout -q main; printf 'x\n' > other.txt; git add -A; git commit -qm "main 셋째"; git push -q origin main
out=$($TRAIN rebase integ/ai-1 2>&1 || true)
check "출발한 기차가 main 과 충돌 — rebase 시도(충돌을 알림)" "$out" "rebase 중 충돌" "충돌하지 않아"

# ---- 5) 그 밖의 경우 ----
# 출발 전 기차가 main 보다 뒤처졌을 때 더 새 main 에서 딴 feature 를 쌓아도, 그 사이 main 변경이 이슈 커밋에 섞이지 않는다
git checkout -q main; printf 'n\n' > main3.txt; git add -A; git commit -qm "main 넷째"; git push -q origin main
feature f-e WD-8 e.txt 'e'
$TRAIN stack ai f-e WD-8 >/dev/null 2>&1
check "뒤처진 기차에 쌓기 — 이슈 커밋에는 feature 변경만" "$(git show --name-only --format= integ/ai-2 | tr '\n' ' ')" "e.txt" "main3.txt"
check "뒤처진 기차에 쌓기 — 기차가 최신 main 위" "$(git merge-base --is-ancestor origin/main integ/ai-2 && echo yes)" "yes"

# 출발한 기차가 줄 충돌 없이 main 과 같은 마이그레이션 번호를 가지면 알린다
feature f-v WD-10 "$M/V9__train.sql" 'v'
$TRAIN stack ai f-v WD-10 >/dev/null 2>&1
git -C "$TMP/work/.claude/worktrees/integ-ai-2" push -q -u origin integ/ai-2
git checkout -q main; printf 'x\n' > "$M/V9__main.sql"; git add -A; git commit -qm "main 이 V9"; git push -q origin main
out=$($TRAIN rebase integ/ai-2 2>&1 || true)
check "출발한 기차 — 줄 충돌 없어도 번호 겹침을 알림" "$out" "마이그레이션 번호가 겹칩니다" "rebase 하지 않습니다"

# 머지 뒤 원격 브랜치가 지워지고 prune 돼도(upstream 설정이 남음) 출발한 기차로 본다
git push -q origin --delete integ/ai-2; git fetch -q --prune origin
check "원격 브랜치가 지워져도 출발한 기차로 봄" "[$($TRAIN next ai)]" "[]"

# 충돌이 아닌 rebase 실패는 충돌로 알리지 않는다(pre-rebase 훅이 막는 경우로 흉내)
feature f-h WD-11 h.txt 'h'
$TRAIN stack ai f-h WD-11 >/dev/null 2>&1
git checkout -q main; printf 'y\n' > main5.txt; git add -A; git commit -qm "main 다섯째"; git push -q origin main
mkdir -p "$TMP/hooks"; printf '#!/bin/sh\necho "pre-rebase 가 막음" >&2\nexit 1\n' > "$TMP/hooks/pre-rebase"; chmod +x "$TMP/hooks/pre-rebase"
git config core.hooksPath "$TMP/hooks"
out=$($TRAIN rebase integ/ai-3 2>&1 || true)
git config --unset core.hooksPath
check "충돌 아닌 rebase 실패 — 충돌로 알리지 않음" "$out" "rebase 하지 못함(충돌 아님)" "rebase 중 충돌"
check "충돌 아닌 rebase 실패 — git 이유를 보임" "$out" "pre-rebase 가 막음"

# git 커밋 신원이 없는 환경(AI Pod 등)에서도 쌓는다
feature f-n WD-12 nn.txt 'n'
printf '[user]\n\tuseConfigOnly = true\n' > "$TMP/noident.gitconfig"
out=$(env -u GIT_AUTHOR_NAME -u GIT_AUTHOR_EMAIL -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  HOME="$TMP" GIT_CONFIG_GLOBAL="$TMP/noident.gitconfig" GIT_CONFIG_NOSYSTEM=1 EMAIL= \
  $TRAIN stack ai f-n WD-12 2>&1 || true)
check "신원 없는 환경 — 쌓음" "$out" "WD-12 → integ/ai-3"
check "이름에 정규식 문자는 받지 않음" "$($TRAIN next 'a.i' 2>&1 || true)" "영문·숫자·_·- 만"

check "잘못된 하위 명령은 사용법" "$($TRAIN nope 2>&1 || true)" "사용:"

finish
