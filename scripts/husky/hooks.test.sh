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

# check_eq <이름> <기대 값> <실제 값> — 정확히 같아야 통과(종료 코드처럼 부분 일치로는 틀린 값도 통과할 수 있는 경우).
check_eq() {
  if [ "$2" = "$3" ]; then
    PASS=$((PASS + 1))
  else
    FAIL=$((FAIL + 1)); printf '✗ %s\n  기대: %s\n  실제: %s\n' "$1" "$2" "$3"
  fi
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
out=$(sh -c ". '$HOOK_DIR/noncode.sh'; code_files '.agents/skills/x/SKILL.md'")
check_not ".agents 스킬 메모는 비코드 (WD-764)" ".agents/" "$out"

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
check_eq "is_verified_head 성공" "0" "$r"
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

# 검토자 풀테스트 — 기준을 green 으로(FULL_REGRESSION_BASE). green 뒤 web 만 → web, api 포함 → full, 문서만 → 돌 단계 없이 통과.
git branch -f green main
git checkout -qb g-web main
echo g > apps/firehub-web/src/g.ts && git add -A && git commit -qm g-web
out=$(FULL_REGRESSION_BASE=green FULL_REGRESSION_CMD='echo "scope=$GATE_SCOPE"' sh scripts/husky/full-regression.sh 2>&1)
check "green 뒤 web 만 바뀌면 web 범위" "scope=web" "$out"
check "범위 안내에 기준이 나온다" "기준 green" "$out"
echo g > apps/firehub-api/src/G.java && git add -A && git commit -qm g-api
out=$(FULL_REGRESSION_BASE=green FULL_REGRESSION_CMD='echo "scope=$GATE_SCOPE"' sh scripts/husky/full-regression.sh 2>&1)
check "green 뒤 api 가 바뀌면 full" "scope=full" "$out"
git checkout -qb g-doc main
mkdir -p docs && echo g > docs/g.md && git add -A && git commit -qm g-doc
out=$(FULL_REGRESSION_BASE=green FULL_REGRESSION_CMD='echo "scope=$GATE_SCOPE"' sh scripts/husky/full-regression.sh 2>&1; echo "exit=$?")
check "green 뒤 문서만이면 돌 단계 없이 통과" "범위: 없음" "$out"
check_not "문서만이면 회귀 명령을 부르지 않는다" "scope=" "$out"
check "문서만 통과는 종료 0" "exit=0" "$out"

# ── 3) pre-commit 판정 (이 저장소, dry-run) ───────────────────────
cd "$REPO_ROOT"
precommit() { PRECOMMIT_CHANGED_FILES="$1" PRECOMMIT_DRY_RUN=1 sh scripts/husky/pre-commit.sh 2>&1; }

check "문서만 커밋은 생략" "비코드 변경만" "$(precommit 'docs/a.md')"
# web E2E 는 커밋 때 @smoke + 바꾼 도메인 + 바꾼 spec 한 번 — 전체 E2E 는 push 게이트 (WD-764)
out=$(precommit 'apps/firehub-web/src/pages/graphrag/X.tsx')
check "graphrag 페이지는 @smoke + 도메인 한정" '--grep "@smoke|pages/(graphrag)/"' "$out"
check "web 만 커밋은 gradle 생략" "firehub-api 변경 없음 — gradle skip" "$out"
check_not "도메인 변경은 짝 없음 안내가 없다" "도메인·spec 에 짝이 없는 web 변경" "$out"
check "도메인 페이지 변경은 vitest 도 돈다" "web 단위 테스트(vitest)" "$out"
# 공유 영역·매핑 밖 페이지·e2e 헬퍼는 짝지을 spec 이 없어 @smoke 만 돌고 push 게이트를 안내한다.
for f in apps/firehub-web/src/lib/x.ts apps/firehub-web/src/pages/zz-none/X.tsx apps/firehub-web/e2e/fixtures/x.ts; do
  out=$(precommit "$f")
  check "$f 는 @smoke 만" '--grep "@smoke"' "$out"
  check_not "$f 는 도메인·spec 을 더하지 않는다" '--grep "@smoke|' "$out"
  check "$f 는 짝 없음 안내" "도메인·spec 에 짝이 없는 web 변경" "$out"
done
out=$(precommit 'apps/firehub-web/src/lib/x.ts
apps/firehub-web/src/pages/graphrag/X.tsx')
check "공유 영역 + 도메인은 @smoke|도메인" '--grep "@smoke|pages/(graphrag)/"' "$out"
out=$(precommit 'apps/firehub-web/e2e/pages/admin/api-connections.spec.ts')
check "바뀐 spec 파일은 @smoke 에 더한다" '--grep "@smoke|(pages/admin/api-connections\.spec\.ts)"' "$out"
check "spec 만 바꾸면 spec 안내" "e2e spec 변경 — 커밋 때는 @smoke + 바꾼 spec" "$out"
check_not "spec 만 바꾸면 짝 없음 안내가 없다" "도메인·spec 에 짝이 없는 web 변경" "$out"
check_not "e2e 만 바꾸면 vitest 생략" "vitest" "$out"
out=$(precommit 'apps/firehub-web/e2e/pages/admin/a.spec.ts
apps/firehub-web/e2e/pages/data/b-c.spec.ts')
check "여러 spec 은 | 로 합친다" '--grep "@smoke|(pages/admin/a\.spec\.ts|pages/data/b-c\.spec\.ts)"' "$out"
out=$(precommit 'apps/firehub-web/e2e/pages/x/(a)[1]+{2}b.spec.ts')
check "spec 경로의 정규식 메타문자는 이스케이프" '--grep "@smoke|(pages/x/\(a\)\[1\]\+\{2\}b\.spec\.ts)"' "$out"
out=$(precommit 'apps/firehub-web/README.md')
check "web 비코드만 바꾸면 커밋 생략" "비코드 변경만" "$out"
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
# 마이그레이션 번호 검사도 test:hooks 가 돌리므로 같은 판정을 탄다 (WD-506)
check "번호 검사 변경도 판정 테스트" "게이트 훅 변경" "$(precommit 'scripts/check-migration-versions.mjs')"
# 의존성 변경은 zod 판 가드를 돈다 (WD-829)
check "lockfile 변경은 zod 가드" "의존성 변경" "$(precommit 'pnpm-lock.yaml')"
check "앱 package.json 변경은 zod 가드" "의존성 변경" "$(precommit 'apps/firehub-web/package.json')"
check_not "무관한 변경은 zod 가드 없음" "의존성 변경" "$(precommit 'apps/firehub-web/src/App.tsx')"

# ── 4) run_locked — macOS 잠자기 방지(caffeinate) (WD-764) ───────
# 락 파일은 임시 경로로 덮어 다른 세션이 쥔 공유 락을 건드리지 않는다. source 뒤에 다시 대입해야 덮어써진다.
# caffeinate 는 명령을 감싸지 않고 훅 셸을 -w 로 지켜보는 백그라운드로 뜬다 — 종료 코드는 명령 자신의 것이어야 한다.
FAKEBIN="$TMP/fakebin"
CAFF_LOG="$TMP/caff.log"
mkdir -p "$FAKEBIN"
# 가짜 caffeinate: 받은 인자만 기록하고 바로 끝난다(백그라운드로 남지 않게).
printf '#!/bin/sh\necho "$*" >> "%s"\n' "$CAFF_LOG" > "$FAKEBIN/caffeinate"
chmod +x "$FAKEBIN/caffeinate"
# 백그라운드 caffeinate 가 기록을 마치도록 wait 한 뒤 명령의 코드로 끝낸다(진짜 caffeinate 는 -w 로 끝나지만 가짜는 바로 끝난다).
out=$(PATH="$FAKEBIN:$PATH" sh -c ". scripts/husky/test-lock.sh
TEST_GATE_LOCK='$TMP/gate.lock'
echo \"PID=\$\$\"
run_locked sh -c 'echo RAN; exit 3'
r=\$?
wait
exit \$r" 2>&1) && r=0 || r=$?
pid=$(printf '%s\n' "$out" | sed -n 's/^PID=//p')
check "caffeinate 가 있어도 명령 실행" "RAN" "$out"
check_eq "caffeinate 가 있어도 명령 종료 코드 그대로" "3" "$r"
check_eq "caffeinate 는 훅 셸을 지켜본다(-i -w <pid>)" "-i -w $pid" "$(cat "$CAFF_LOG" 2>/dev/null)"
# caffeinate 가 없는 경우(리눅스·파드·CI)는 이 호스트에 caffeinate 가 없을 때만 본다 — PATH 를 꾸며 가리지 않는다.
if command -v caffeinate >/dev/null 2>&1; then
  echo "↷ caffeinate 없는 run_locked 검사 건너뜀 — 이 호스트에 caffeinate 가 있음"
else
  out=$(sh -c ". scripts/husky/test-lock.sh; TEST_GATE_LOCK='$TMP/gate.lock'; run_locked sh -c 'echo RAN; exit 3'" 2>&1) && r=0 || r=$?
  check "caffeinate 가 없으면 그대로 실행" "RAN" "$out"
  check_eq "caffeinate 없이도 종료 코드 그대로" "3" "$r"
fi

echo "hooks.test: 통과 $PASS, 실패 $FAIL"
[ "$FAIL" -eq 0 ]
