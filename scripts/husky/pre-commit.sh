#!/usr/bin/env sh
# pre-commit gate logic (#257)
# - 가벼운 정합성 검사는 항상 실행 (lint-staged, typecheck)
# - 무거운 회귀(E2E + gradle)는 변경 영역 분석으로 선택 실행
#   - 비코드 단독: skip
#   - 공유 영역/매핑 모호: 전체 E2E
#   - 도메인 단독: 전역 smoke + 해당 도메인 non-smoke (중복 0)
#   - firehub-web 외만 변경: gradle 만
# - 풀 회귀 안전망은 .husky/pre-push 가 담당
#
# 본 스크립트는 husky 외부에서도 테스트 가능하도록 분리됨.
# 환경변수 PRECOMMIT_CHANGED_FILES 로 변경 파일 목록을 주입하면 git stage 없이 검증 가능.

set -e

# 1) 변경 파일 목록 (테스트용 주입 우선, 없으면 git stage 에서 추출)
if [ -n "$PRECOMMIT_CHANGED_FILES" ]; then
  CHANGED="$PRECOMMIT_CHANGED_FILES"
else
  CHANGED=$(git diff --cached --name-only --diff-filter=ACMR)
fi

# lint-staged + typecheck 는 항상 (단, 테스트 주입 모드면 skip — 실제 stage 가 없어 의미 없음)
if [ -z "$PRECOMMIT_CHANGED_FILES" ]; then
  npx lint-staged
  pnpm typecheck
fi

# 2) 비코드만? → skip
# set -e 와 grep 의 빈 매치(exit 1) 충돌 방지: || true 로 안전 처리
NEEDS_REGRESSION=$(printf '%s\n' "$CHANGED" | grep -vE '^(docs/|.*\.md$|LICENSE.*|\.gitignore$|\.gitattributes$|\.vscode/|\.idea/|\.editorconfig$|\.github/(ISSUE_TEMPLATE|PULL_REQUEST_TEMPLATE))' || true)
if [ -z "$NEEDS_REGRESSION" ]; then
  echo "[pre-commit] 비코드 변경만 감지 — E2E + gradle test skip"
  exit 0
fi

# 3) 공유 영역 또는 매핑 외 코드 변경 → 전체 풀
WEB_DOMAINS_RE='admin|ai-insights|analytics|data|pipeline|settings'

FORCE_FULL=$(printf '%s\n' "$CHANGED" | grep -E '^apps/firehub-web/(src/(components|api|lib|hooks|types|main\.tsx|App\.tsx|index\.css|router\.tsx|vite-env\.d\.ts|setupTests\.ts)|(vite|playwright|eslint|postcss|tailwind)\.config\.(ts|js|cjs|mjs)|tsconfig.*\.json|package\.json|e2e/|scripts/)' 2>/dev/null | head -1 || true)

WEB_PAGE_CHANGES=$(printf '%s\n' "$CHANGED" | grep -E '^apps/firehub-web/src/pages/' || true)
NON_DOMAIN_PAGE=$(printf '%s\n' "$WEB_PAGE_CHANGES" | grep -vE "^apps/firehub-web/src/pages/($WEB_DOMAINS_RE)/" || true)

if [ -n "$FORCE_FULL" ] || [ -n "$NON_DOMAIN_PAGE" ]; then
  echo "[pre-commit] 공유 영역/매핑 외 변경 감지 — 전체 E2E 실행"
  [ -n "$PRECOMMIT_DRY_RUN" ] || pnpm test:e2e
elif [ -n "$WEB_PAGE_CHANGES" ]; then
  # 4) 도메인 단독 변경 → 전역 smoke + 해당 도메인의 non-smoke (중복 0)
  # 다중 도메인 지원: newline → space 명시 정규화 (zsh/bash 양쪽 안전)
  DOMAINS_STR=$(printf '%s\n' "$WEB_PAGE_CHANGES" | sed -E 's|^apps/firehub-web/src/pages/([^/]+)/.*$|\1|' | sort -u | tr '\n' ' ')
  echo "[pre-commit] 도메인 한정 변경 감지: ${DOMAINS_STR}— 전역 smoke + 해당 도메인 non-smoke 실행"

  if [ -z "$PRECOMMIT_DRY_RUN" ]; then
    cd apps/firehub-web
    # 도메인 spec dir 인자 구성
    DOMAIN_SPECS=""
    for d in $DOMAINS_STR; do
      DOMAIN_SPECS="$DOMAIN_SPECS e2e/pages/$d"
    done

    # step 1: 전역 smoke
    npx playwright test --grep "@smoke"
    # step 2: 해당 도메인의 non-smoke (--grep-invert 로 smoke 제외 → 중복 0)
    npx playwright test $DOMAIN_SPECS --grep-invert "@smoke"
    cd - >/dev/null
  fi
else
  # 5) firehub-web 외 변경 (api 단독 등) — gradle 단계에서 회귀 검증
  echo "[pre-commit] firehub-web 변경 없음 — E2E skip (gradle 단계에서 회귀 검증)"
fi

# 6) Gradle — 모든 코드 변경 케이스에서 실행. build-cache 로 변경 없으면 즉시 통과.
[ -n "$PRECOMMIT_DRY_RUN" ] && { echo "[pre-commit][dry-run] gradle test skip"; exit 0; }
cd apps/firehub-api && ./gradlew test -x generateJooq --build-cache --configuration-cache
