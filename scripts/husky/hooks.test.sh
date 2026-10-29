#!/usr/bin/env sh
# 게이트 훅 판정 테스트 (WD-459) — `pnpm test:hooks`. 회귀(E2E·gradle)는 돌리지 않고 dry-run 판정만 본다.
# - noncode.sh·gate-scope.sh·verified-trees.sh 함수, pre-push 생략 규칙은 임시 git 저장소에서 검사한다(공유 기록을 건드리지 않게).
# - pre-commit 은 PRECOMMIT_CHANGED_FILES + PRECOMMIT_DRY_RUN 으로 이 저장소에서 판정만 본다(테스트 파일 검색에 실제 트리가 필요).

set -e

HOOK_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$HOOK_DIR/../.." && pwd)
PASS=0
FAIL=0

# check <이름> <기대 부분 문자열> <실제 출력> — 출력에 기대 문자열이 있으면 통과.
check() {
  case "$3" in
    *"$2"*) PASS=$((PASS + 1)) ;;
    *) FAIL=$((FAIL + 1)); printf '✗ %s\n  기대: %s\n  실제: %s\n' "$1" "$2" "$3" ;;
  esac
}

# check_not <이름> <없어야 할 부분 문자열> <실제 출력>
check_not() {
  case "$3" in
    *"$2"*) FAIL=$((FAIL + 1)); printf '✗ %s\n  없어야 함: %s\n  실제: %s\n' "$1" "$2" "$3" ;;
    *) PASS=$((PASS + 1)) ;;
  esac
}

# 커밋 훅 안에서 돌 때 git 이 넘긴 GIT_INDEX_FILE 등을 지운다 — 임시 저장소의 git add·checkout 이 실제 커밋의 인덱스를 건드리지 않게.
unset GIT_INDEX_FILE GIT_DIR GIT_WORK_TREE GIT_PREFIX GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ── 1) 비코드 판정 ───────────────────────────────────────────────
out=$(sh -c ". '$HOOK_DIR/noncode.sh'; code_files 'docs/a.md
README.md
apps/firehub-web/README.md
.work-loop/project.md
apps/firehub-ai-agent/src/agent/subagents/x/agent.md
apps/firehub-web/src/App.tsx'")
check "apps 아래 런타임 md 는 코드" "apps/firehub-ai-agent/src/agent/subagents/x/agent.md" "$out"
check "web 소스는 코드" "apps/firehub-web/src/App.tsx" "$out"
check_not "docs 는 비코드" "docs/a.md" "$out"
check_not "앱 README 는 비코드" "apps/firehub-web/README.md" "$out"
check_not "work-loop 메모는 비코드" ".work-loop/project.md" "$out"

# ── 2) 범위 판정·통과 기록·pre-push 생략 (임시 저장소) ─────────────
R="$TMP/repo"
mkdir -p "$R/scripts/husky" "$R/apps/firehub-web/src" "$R/apps/firehub-api/src" "$R/docs"
cp "$HOOK_DIR/noncode.sh" "$HOOK_DIR/gate-scope.sh" "$HOOK_DIR/verified-trees.sh" "$HOOK_DIR/pre-push.sh" "$HOOK_DIR/full-regression.sh" "$R/scripts/husky/"
cd "$R"
git init -q -b main
git config user.email t@example.com
git config user.name t
echo a > apps/firehub-web/src/a.ts
echo a > apps/firehub-api/src/A.java
git add -A && git commit -qm base
git update-ref refs/remotes/origin/main HEAD
export PREPUSH_VERIFIED_FILE="$TMP/verified"
LIB=". scripts/husky/noncode.sh; . scripts/husky/gate-scope.sh; . scripts/husky/verified-trees.sh"
ZERO=0000000000000000000000000000000000000000

# prepush <설명> — 지금 HEAD 를 새 원격 브랜치로 push 하는 판정(dry-run).
prepush() {
  printf 'refs/heads/b %s refs/heads/b %s\n' "$(git rev-parse HEAD)" "$ZERO" | PREPUSH_DRY_RUN=1 sh scripts/husky/pre-push.sh origin 2>&1
}

git checkout -qb web-only
echo b > apps/firehub-web/src/a.ts && git commit -qam web
check "web 만 바꾸면 범위 web" "web" "$(sh -c "$LIB; gate_scope refs/remotes/origin/main HEAD")"
check "web 범위 push 는 gradle 없는 회귀" "범위 web" "$(prepush)"

git checkout -qb api main
echo b > apps/firehub-api/src/A.java && git commit -qam api
check "api 를 바꾸면 범위 full" "full" "$(sh -c "$LIB; gate_scope refs/remotes/origin/main HEAD")"

git checkout -qb docs main
echo d > docs/x.md && git add -A && git commit -qm docs
check "문서만 push 는 생략" "비코드 변경만" "$(prepush)"

git checkout -q main
check "main 과 같은 커밋 push 는 생략" "새 커밋 없음" "$(prepush)"
check "삭제 push 는 생략" "삭제" "$(printf 'refs/heads/b %s refs/heads/b %s\n' "$ZERO" "$(git rev-parse HEAD)" | PREPUSH_DRY_RUN=1 sh scripts/husky/pre-push.sh origin 2>&1)"

# 통과 기록: web 범위 기록은 web 필요 범위만 덮고, full 필요 범위는 덮지 못한다.
git checkout -q web-only
sh -c "$LIB; record_verified \"\$(git rev-parse 'HEAD^{tree}')\" web"
check "web 으로 통과한 트리는 push 생략" "이미 전체 회귀를 통과한 트리" "$(prepush)"
sh -c "$LIB; is_verified_head" && r=0 || r=$?
check "is_verified_head 성공" "0" "$r"
git checkout -q api
sh -c "$LIB; record_verified \"\$(git rev-parse 'HEAD^{tree}')\" web"
check "web 기록은 api 변경(full)을 덮지 못함" "코드 변경" "$(prepush)"
sh -c "$LIB; record_verified \"\$(git rev-parse 'HEAD^{tree}')\" full"
check "full 로 통과하면 생략" "이미 전체 회귀를 통과한 트리" "$(prepush)"
check "기록 합치기 — web 다음 full 은 full" "$(git rev-parse 'HEAD^{tree}')" "$(cat "$PREPUSH_VERIFIED_FILE" | grep -v ' ')"

# 여러 ref 를 한 번에 push — web 범위 ref 와 api ref 가 함께면 full 로 돈다(첫 ref 에서 멈추지 않는다).
git checkout -q web-only
out=$(printf 'refs/heads/w %s refs/heads/w %s\nrefs/heads/a %s refs/heads/a %s\n' "$(git rev-parse HEAD)" "$ZERO" "$(git rev-parse api)" "$ZERO" | PREPUSH_DRY_RUN=1 sh scripts/husky/pre-push.sh origin 2>&1)
check "기록된 web ref 와 미기록 api ref 를 함께 push 하면 full" "범위 full" "$out"

# force-push 로 이력을 바꾸면 원격의 이전 값을 빼지 않는다 — 문서 커밋만 얹어도 새 트리는 검증 대상.
git checkout -qb rewritten main
echo e > apps/firehub-api/src/A.java && git commit -qam code-e
mkdir -p docs && echo d > docs/y.md && git add -A && git commit -qm docs2
out=$(printf 'refs/heads/r %s refs/heads/r %s\n' "$(git rev-parse HEAD)" "$(git rev-parse api)" | PREPUSH_DRY_RUN=1 sh scripts/husky/pre-push.sh origin 2>&1)
check "non-fast-forward push 는 검증하지 않은 코드 변경으로 회귀" "코드 변경" "$out"
out=$(printf 'refs/heads/r %s refs/heads/r %s\n' "$(git rev-parse HEAD)" "$(git rev-parse HEAD~1)" | PREPUSH_DRY_RUN=1 sh scripts/husky/pre-push.sh origin 2>&1)
check "fast-forward 로 문서만 더하면 생략" "비코드 변경만" "$out"

# full-regression: 회귀 대신 FULL_REGRESSION_CMD 로 돌려 기록·범위 전달을 본다.
git checkout -qb web2 main
echo c > apps/firehub-web/src/a.ts && git commit -qam web2
out=$(FULL_REGRESSION_CMD='echo "scope=$GATE_SCOPE"' sh scripts/husky/full-regression.sh 2>&1)
check "full-regression 범위 전달" "scope=web" "$out"
check "full-regression 통과 기록" "검증 완료 트리 기록" "$out"
echo dirty > apps/firehub-web/src/b.ts
out=$(FULL_REGRESSION_CMD='true' sh scripts/husky/full-regression.sh 2>&1)
check "커밋 안 된 코드 파일이 있으면 기록하지 않음" "기록하지 않음" "$out"
rm apps/firehub-web/src/b.ts
out=$(FULL_REGRESSION_CMD='true' env -u PREPUSH_VERIFIED_FILE sh scripts/husky/full-regression.sh 2>&1) && r=0 || r=$?
check "테스트 명령은 기록 파일 없이 거부" "테스트 전용" "$out"

# ── 3) pre-commit 판정 (이 저장소, dry-run) ───────────────────────
cd "$REPO_ROOT"
precommit() { PRECOMMIT_CHANGED_FILES="$1" PRECOMMIT_DRY_RUN=1 sh scripts/husky/pre-commit.sh 2>&1; }

check "문서만 커밋은 생략" "비코드 변경만" "$(precommit 'docs/a.md')"
out=$(precommit 'apps/firehub-web/src/pages/graphrag/X.tsx')
check "graphrag 페이지는 도메인 한정" "도메인 한정 변경 감지: graphrag" "$out"
check "web 만 커밋은 gradle 생략" "firehub-api 변경 없음 — gradle skip" "$out"
out=$(precommit 'apps/firehub-web/src/lib/x.ts')
check "공유 영역은 전체 E2E" "전체 E2E" "$out"
out=$(precommit 'apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/PipelineService.java')
check "api 메인 변경은 이름 규칙 매칭 테스트만" '--tests "*PipelineServiceTest"' "$out"
check "api 만 커밋은 E2E 생략" "firehub-web 변경 없음 — E2E skip" "$out"
out=$(precommit 'apps/firehub-api/src/main/resources/application.yml')
check "api 비 Java 변경은 compile 만" "compileTestJava" "$out"
out=$(precommit 'apps/firehub-ai-agent/src/agent/subagents/x/agent.md')
check "ai-agent md 는 코드로 보고 gradle 생략" "gradle skip" "$out"
check_not "ai-agent md 는 비코드가 아님" "비코드 변경만" "$out"

check "docker/postgres 변경은 api 로 본다" "compileTestJava" "$(precommit 'docker/postgres/Dockerfile')"
check "훅 변경은 판정 테스트" "게이트 훅 변경" "$(precommit 'scripts/husky/noncode.sh')"

echo "hooks.test: 통과 $PASS, 실패 $FAIL"
[ "$FAIL" -eq 0 ]
