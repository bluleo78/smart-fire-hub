#!/usr/bin/env bash
# 소개 자료를 빈 격리 스택에서 처음부터 다시 만든다: DB 초기화 → 시드 → AI 작업 → 촬영 → PDF.
#
#   INTRO_AI_TOKEN=<AI 에이전트 토큰> docs/intro/scripts/run-all.sh
#
# 전제(README 참조): 웹(5273)과 ai-agent(5020)가 떠 있고, API jar 가 빌드돼 있다.
# 토큰은 환경변수로만 넘긴다 — 파일·커밋에 남기지 않는다.
set -euo pipefail
cd "$(dirname "$0")/../../.."
: "${INTRO_AI_TOKEN:?INTRO_AI_TOKEN 이 필요하다(AI 에이전트 토큰)}"
docs/intro/scripts/reset-stack.sh
node docs/intro/scripts/seed-demo.mjs
# 조직 이름 — 워크스페이스 이름 변경 API 가 없어 촬영용 DB 에서만 직접 바꾼다(사이드바 하단 표시).
docker exec intro-data-db psql -U app -d smartfirehub -qc "update tenant set name='㈜누리테크' where id=1"
node docs/intro/scripts/seed-ai.mjs
node docs/intro/scripts/capture-screens.mjs
node docs/intro/scripts/build-pdf.mjs
