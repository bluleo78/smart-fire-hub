#!/usr/bin/env sh
# pre-commit gate logic (#257)
# - 가벼운 정합성 검사는 항상 실행 (lint-staged, typecheck)
# - 무거운 회귀(E2E + gradle)는 변경 영역 분석으로 선택 실행
#   - 비코드 단독: skip
#   - firehub-web 코드 변경: @smoke + 바꾼 도메인 spec + 바꾼 spec 파일을 한 번에 (공유 영역·매핑 밖도 @smoke).
#     전체 E2E 는 커밋 때 돌지 않고 push 게이트가 한 번 돈다 (WD-764)
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
# 기차·마이그레이션 번호 검사 스크립트도 같은 묶음으로 본다 — test:hooks 가 husky/*.test.sh 전부와 번호 검사 테스트를 돈다 (WD-506)
if printf '%s\n' "$CHANGED" | grep -qE '^(scripts/husky/|\.husky/|scripts/check-migration-versions)'; then
  echo "[pre-commit] 게이트 훅 변경 — 판정 테스트(pnpm test:hooks) 실행"
  [ -n "$PRECOMMIT_DRY_RUN" ] || pnpm -s test:hooks
fi

# typecheck 는 코드 변경이 있을 때만 — 문서만 바꾼 커밋에서 전체 앱 타입 검사를 돌지 않게 (WD-459)
[ -n "$PRECOMMIT_CHANGED_FILES" ] || pnpm typecheck

# web 코드 변경 = apps/firehub-web/ 아래이면서 비코드(README·CLAUDE 등)가 아닌 파일 — vitest·E2E 판정이 함께 쓴다.
WEB_CODE=$(printf '%s\n' "$NEEDS_REGRESSION" | grep -E '^apps/firehub-web/' || true)

# firehub-web 코드를 바꿨으면 web 단위 테스트(vitest)도 — 디자인·알파 게이트가 정적 검사라 E2E 로는 못 잡는다(WD-668).
# 전체가 10초 안쪽이라 관련 파일만 고르지 않고 다 돈다. e2e/ 만 바꾼 커밋(spec·e2e 헬퍼)은 vitest 대상이 아니라 건너뛴다.
if printf '%s\n' "$WEB_CODE" | grep -vE '^apps/firehub-web/e2e/' | grep -q .; then
  echo "[pre-commit] firehub-web 변경 — web 단위 테스트(vitest) 실행"
  [ -n "$PRECOMMIT_DRY_RUN" ] || (cd apps/firehub-web && pnpm exec vitest run)
fi

# 3) web E2E — 커밋 때는 전체를 돌지 않고 @smoke + 바꾼 도메인 + 바꾼 spec 만 한 번에 (WD-764)
# 전체 E2E 는 push 게이트(full-regression.sh)가 한 번 돈다. 커밋마다 전체(수 분)를 돌면 커밋이 느려지고
# 같은 커밋을 push 때 또 돌게 되므로, 커밋 때는 빠른 피드백만 주고 안전망은 push 에 맡긴다.
if [ -z "$WEB_CODE" ]; then
  echo "[pre-commit] firehub-web 변경 없음 — E2E skip"
else
  # 도메인 = src/pages/<d>/ 와 e2e/pages/<d>/ 가 모두 있는 폴더 — 목록을 고정하면 새 도메인(graphrag 등)이 빠진다 (WD-459).
  WEB_DOMAINS_RE=$(for d in apps/firehub-web/src/pages/*/; do
    d=${d%/}
    d=${d##*/}
    [ -d "apps/firehub-web/e2e/pages/$d" ] && printf '%s\n' "$d"
  done | paste -sd'|' -)
  [ -n "$WEB_DOMAINS_RE" ] || WEB_DOMAINS_RE='^$'

  # 바꾼 도메인 페이지(매핑에 드는 src/pages/<d>/ 아래)와 바꾼 spec 파일 — 아래 grep 식과 안내가 모두 이 둘에서 나온다.
  DOMAIN_PAGES=$(printf '%s\n' "$WEB_CODE" | grep -E "^apps/firehub-web/src/pages/($WEB_DOMAINS_RE)/" || true)
  WEB_SPECS=$(printf '%s\n' "$WEB_CODE" | grep -E '^apps/firehub-web/e2e/.*\.spec\.ts$' || true)

  # 바꾼 도메인 이름 (다중 도메인은 '|' 로 합친다)
  DOMAIN_RE=$(printf '%s\n' "$DOMAIN_PAGES" | sed -E 's|^apps/firehub-web/src/pages/([^/]+)/.*$|\1|' | sort -u | paste -sd'|' -)

  # 바꾼 spec 파일: e2e/ 접두어를 떼고 정규식 메타문자를 이스케이프한다 (playwright --grep 은 e2e 기준 경로를 포함한 제목에 매칭).
  # 대괄호 식은 ']' 를 맨 앞에, '.' 를 '[' 앞에 둬야 POSIX·BSD sed 모두에서 안전하다.
  SPEC_RE=$(printf '%s\n' "$WEB_SPECS" | sed -e 's|^apps/firehub-web/e2e/||' -e 's/[].[+*?(){}|^$\\]/\\&/g' | paste -sd'|' -)

  # 도메인 페이지·spec 어디에도 짝이 없는 web 코드(공용 영역·e2e 헬퍼·설정·매핑 밖 페이지 등)는 영향받는 spec 이 모호하다 —
  # 커밋 때는 @smoke 가 맡고 전체는 push 게이트가 본다. 안내는 실제로 고른 것과 맞춘다.
  # 빈 패턴 줄은 grep -F 구현마다 다르게 다룰 수 있어 빼고 넘긴다.
  PAIRED=$(printf '%s\n%s\n' "$DOMAIN_PAGES" "$WEB_SPECS" | sed '/^$/d')
  if [ -n "$PAIRED" ]; then
    UNPAIRED=$(printf '%s\n' "$WEB_CODE" | grep -vxF "$PAIRED" || true)
  else
    UNPAIRED=$WEB_CODE
  fi
  if [ -n "$UNPAIRED" ]; then
    echo "[pre-commit] 도메인·spec 에 짝이 없는 web 변경(공용 영역·e2e 헬퍼·매핑 밖 페이지 등) — 커밋 때는 @smoke, 전체 E2E 는 push 게이트"
  elif [ -z "$DOMAIN_PAGES" ]; then
    echo "[pre-commit] e2e spec 변경 — 커밋 때는 @smoke + 바꾼 spec, 전체 E2E 는 push 게이트"
  fi

  # @smoke + 바꾼 도메인 + 바꾼 spec 을 playwright 1회 호출로 — 합집합이라 겹쳐도 한 번만 돌고 서버 기동·락 대기도 한 번이다 (WD-459).
  GREP_EXPR="@smoke"
  if [ -n "$DOMAIN_RE" ]; then
    GREP_EXPR="$GREP_EXPR|pages/($DOMAIN_RE)/"
  fi
  if [ -n "$SPEC_RE" ]; then
    GREP_EXPR="$GREP_EXPR|($SPEC_RE)"
  fi
  echo "[pre-commit] firehub-web 변경 — E2E: @smoke + 바꾼 도메인(${DOMAIN_RE:-없음}) + 바꾼 spec 만 실행"
  if [ -n "$PRECOMMIT_DRY_RUN" ]; then
    echo "[pre-commit][dry-run] playwright --grep \"$GREP_EXPR\""
  else
    # 소수 spec 이라 dev 서버를 유지한다(전체용 preview 는 push 게이트가 쓴다).
    (cd apps/firehub-web && run_locked pnpm exec playwright test --grep "$GREP_EXPR")
  fi
fi

# 4) Gradle — 변경분 기반 선택 실행 (WD-459, smart-workplace pre-commit 이식). 풀 회귀는 pre-push 가 담당.
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
