#!/usr/bin/env sh
# pre-commit gate logic (#257)
# - 가벼운 정합성 검사는 항상 실행 (lint-staged, typecheck)
# - 무거운 회귀(E2E + gradle)는 변경 영역 분석으로 선택 실행
#   - 비코드 단독: skip
#   - 공유 영역/매핑 모호: 전체 E2E
#   - 도메인 단독: 전역 smoke + 해당 도메인 non-smoke (중복 0)
#   - firehub-web 외만 변경: E2E skip
#   - gradle: api 변경이 있을 때만, 바뀐 테스트·이름 규칙 매칭 테스트만(없으면 compileTestJava) (WD-459)
# - 풀 회귀 안전망은 .husky/pre-push 가 담당
#
# 본 스크립트는 husky 외부에서도 테스트 가능하도록 분리됨.
# 환경변수 PRECOMMIT_CHANGED_FILES 로 변경 파일 목록을 주입하면 git stage 없이 검증 가능.

set -e

# 무거운 단계(E2E·gradle)는 저장소 전역에서 한 번에 하나만 — test-lock.sh 참조 (WD-257)
. "$(dirname "$0")/test-lock.sh"

# 1) 변경 파일 목록 (테스트용 주입 우선, 없으면 git stage 에서 추출)
if [ -n "$PRECOMMIT_CHANGED_FILES" ]; then
  CHANGED="$PRECOMMIT_CHANGED_FILES"
else
  # 삭제(D)도 넣는다 — 지운 api 파일이 테스트 컴파일을 깨는지 gradle 단계가 봐야 한다. 한글 경로가 따옴표로 바뀌지 않게 quotePath 를 끈다 (WD-459)
  CHANGED=$(git -c core.quotePath=false diff --cached --name-only --diff-filter=ACMRD)
fi

# lint-staged 는 항상 (단, 테스트 주입 모드면 skip — 실제 stage 가 없어 의미 없음)
if [ -z "$PRECOMMIT_CHANGED_FILES" ]; then
  npx lint-staged
fi

# 2) 비코드만? → skip (규칙은 pre-push 와 공유 — noncode.sh, WD-459)
. "$(dirname "$0")/noncode.sh"
NEEDS_REGRESSION=$(code_files "$CHANGED")
if [ -z "$NEEDS_REGRESSION" ]; then
  echo "[pre-commit] 비코드 변경만 감지 — typecheck + E2E + gradle test skip"
  exit 0
fi

# 게이트 훅 자체를 바꿨으면 판정 테스트를 먼저 — 잘못된 판정이 회귀를 건너뛰거나 가짜 통과를 기록하지 않게 (WD-459)
if printf '%s\n' "$CHANGED" | grep -qE '^(scripts/husky/|\.husky/)'; then
  echo "[pre-commit] 게이트 훅 변경 — 판정 테스트(pnpm test:hooks) 실행"
  [ -n "$PRECOMMIT_DRY_RUN" ] || sh scripts/husky/hooks.test.sh
fi

# typecheck 는 코드 변경이 있을 때만 — 문서만 바꾼 커밋에서 전체 앱 타입 검사를 돌지 않게 (WD-459)
[ -n "$PRECOMMIT_CHANGED_FILES" ] || pnpm typecheck

# 3) 공유 영역 또는 매핑 외 코드 변경 → 전체 풀
# 도메인 = src/pages/<d>/ 와 e2e/pages/<d>/ 가 모두 있는 폴더 — 목록을 고정하면 새 도메인(graphrag 등)이 빠져 전체 E2E 로 간다 (WD-459).
WEB_DOMAINS_RE=$(for d in apps/firehub-web/src/pages/*/; do d=$(basename "$d"); [ -d "apps/firehub-web/e2e/pages/$d" ] && printf '%s\n' "$d"; done | paste -sd'|' -)
[ -n "$WEB_DOMAINS_RE" ] || WEB_DOMAINS_RE='^$'

FORCE_FULL=$(printf '%s\n' "$CHANGED" | grep -E '^apps/firehub-web/(src/(components|api|lib|hooks|types|main\.tsx|App\.tsx|index\.css|router\.tsx|vite-env\.d\.ts|setupTests\.ts)|(vite|playwright|eslint|postcss|tailwind)\.config\.(ts|js|cjs|mjs)|tsconfig.*\.json|package\.json|e2e/|scripts/)' 2>/dev/null | head -1 || true)

WEB_PAGE_CHANGES=$(printf '%s\n' "$CHANGED" | grep -E '^apps/firehub-web/src/pages/' || true)
NON_DOMAIN_PAGE=$(printf '%s\n' "$WEB_PAGE_CHANGES" | grep -vE "^apps/firehub-web/src/pages/($WEB_DOMAINS_RE)/" || true)

if [ -n "$FORCE_FULL" ] || [ -n "$NON_DOMAIN_PAGE" ]; then
  echo "[pre-commit] 공유 영역/매핑 외 변경 감지 — 전체 E2E 실행"
  # 전체 스위트는 빌드(~15초)보다 dev 서버 온디맨드 트랜스폼 비용이 훨씬 커서 preview 서버로 실행한다(playwright.config.ts 참조).
  # 도메인 한정 실행(아래)은 소수 spec 이라 dev 서버를 유지한다.
  [ -n "$PRECOMMIT_DRY_RUN" ] || (cd apps/firehub-web && run_locked env E2E_SERVER=preview pnpm test:e2e)
elif [ -n "$WEB_PAGE_CHANGES" ]; then
  # 4) 도메인 단독 변경 → 전역 smoke + 해당 도메인 spec
  # 다중 도메인 지원: newline → '|' 정규화
  DOMAIN_RE=$(printf '%s\n' "$WEB_PAGE_CHANGES" | sed -E 's|^apps/firehub-web/src/pages/([^/]+)/.*$|\1|' | sort -u | paste -sd'|' -)
  echo "[pre-commit] 도메인 한정 변경 감지: ${DOMAIN_RE} — 전역 smoke + 해당 도메인 spec 실행"

  # 전역 smoke + 해당 도메인 spec 을 playwright 1회 호출로 실행 (WD-459, smart-workplace WP-76 이식).
  # grep 은 파일 경로(e2e/ 기준)가 포함된 전체 타이틀에 매칭되므로 "@smoke|pages/<도메인>/" 하나로 두 집합의
  # 합집합을 고른다(겹치는 도메인 smoke 도 1회만). 2회 호출하면 서버 기동·락 대기가 두 번 든다.
  [ -n "$PRECOMMIT_DRY_RUN" ] || (cd apps/firehub-web && run_locked npx playwright test --grep "@smoke|pages/($DOMAIN_RE)/")
else
  # 5) firehub-web 외 변경 (api 단독 등) — E2E skip
  echo "[pre-commit] firehub-web 변경 없음 — E2E skip"
fi

# 6) Gradle — 변경분 기반 선택 실행 (WD-459, smart-workplace pre-commit 이식). 풀 회귀는 pre-push 가 담당.
#    - 변경된 Java 에서 실행할 테스트 클래스를 도출:
#        · 테스트 파일 변경 → 그 클래스 자신(--tests "*Xxx")
#        · 메인 파일 변경   → 이름 규칙(Foo*Test/Foo*Tests/Foo*IT) 매칭 테스트를 파일시스템에서 검색
#    - 매칭 테스트 있음 → 해당 --tests 만 실행(빠른 피드백)
#    - api 변경은 있으나 매칭 테스트 없음 → compileTestJava 만(전체 test 는 pre-push 위임)
#    - api 변경 없음 → skip
#    spotlessCheck 는 넣지 않는다 — main 에 이미 포맷 드리프트가 있어 무관한 커밋까지 막는다.
# docker/postgres/ 는 api 통합 테스트의 Testcontainers 이미지라 api 변경으로 본다(build.gradle.kts smartfirehub.repositoryRoot)
API_CHANGED=$(printf '%s\n' "$CHANGED" | grep -E '^(apps/firehub-api/|docker/postgres/)' || true)
if [ -z "$API_CHANGED" ]; then
  echo "[pre-commit] firehub-api 변경 없음 — gradle skip"
  exit 0
fi

JAVA_CHANGED=$(printf '%s\n' "$CHANGED" | grep -E '^apps/firehub-api/src/(main|test)/java/.*\.java$' || true)

# 변경된 Java → 실행할 테스트 --tests 패턴 누적(중복 제거)
TEST_ARGS=""
add_test() {
  case " $TEST_ARGS " in
    *" --tests \"$1\" "*) : ;;               # 이미 있음
    *) TEST_ARGS="$TEST_ARGS --tests \"$1\"" ;;
  esac
}

for f in $JAVA_CHANGED; do
  base=$(basename "$f" .java)
  case "$f" in
    apps/firehub-api/src/test/java/*)
      # 테스트 파일 변경 → 그 클래스 자신. 단 @Test 가 있는 구체 테스트일 때만(추상 베이스·헬퍼는
      # 0개 매칭 → 아무것도 안 돌고 통과하는 위험이라 제외 → compile 만으로 떨어져 pre-push 위임).
      if grep -qE '@Test|@ParameterizedTest|@RepeatedTest|@TestFactory' "$f" 2>/dev/null; then
        add_test "*$base"
      fi
      ;;
    apps/firehub-api/src/main/java/*)
      # 메인 파일 변경 → 이름 규칙 매칭 테스트를 파일시스템에서 검색(없으면 추가 안 함 → No tests found 회피)
      while IFS= read -r tf; do
        [ -n "$tf" ] || continue
        add_test "*$(basename "$tf" .java)"
      done <<EOF
$(find apps/firehub-api/src/test/java -type f \( -name "${base}*Test.java" -o -name "${base}*Tests.java" -o -name "${base}*IT.java" \) 2>/dev/null)
EOF
      ;;
  esac
done

# 커밋 단계는 통과/실패만 필요 — JaCoCo 리포트·계측을 끈다(-PnoCoverage, build.gradle.kts).
GRADLE_BASE="-PnoCoverage -x generateJooq -x jacocoTestReport --build-cache --configuration-cache"
if [ -n "$TEST_ARGS" ]; then
  CMD="./gradlew test$TEST_ARGS $GRADLE_BASE"
  echo "[pre-commit] 변경분 한정 gradle:$TEST_ARGS"
else
  CMD="./gradlew compileTestJava $GRADLE_BASE"
  echo "[pre-commit] 매칭 테스트 없음(이름 규칙 밖·비 Java 변경) — compile 만, 전체 test 는 pre-push 위임"
fi

if [ -n "$PRECOMMIT_DRY_RUN" ]; then
  echo "[pre-commit][dry-run] $CMD"
  exit 0
fi
cd apps/firehub-api && eval "run_locked $CMD"
