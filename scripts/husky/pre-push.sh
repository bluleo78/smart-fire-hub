#!/usr/bin/env sh
# pre-push gate logic (#257)
# - push 직전 전체 회귀 안전망 (E2E 전체 + gradle 풀)
# - pre-commit 단계에서 도메인 한정/smoke 로 우회된 spec 들을 풀로 재검증
# - AI 자동화가 여러 커밋을 쌓아 push 할 때 누적 회귀를 차단한다

set -e

pnpm test:e2e
cd apps/firehub-api && ./gradlew test -x generateJooq --build-cache --configuration-cache
