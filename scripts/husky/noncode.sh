#!/usr/bin/env sh
# 「비코드 변경」 판정 규칙 — pre-commit/pre-push/full-regression 이 source 해 같은 규칙을 쓴다 (WD-459, smart-workplace WP-446 이식).
# 앱 동작과 회귀 결과를 바꾸지 않는 경로만: 문서·에이전트 작업 메모·편집기 설정.
# - Markdown 은 저장소 루트의 .md 와 README/CLAUDE/AGENTS 만 비코드다. apps/ 아래 .md 는 런타임이 읽는 것이 있어
#   (firehub-ai-agent 서브에이전트 정의 agent.md·rules.md 등) 코드로 본다.

NON_CODE_RE='^(docs/|\.claude/|\.work-loop/|[^/]*\.md$|.*/(README|CLAUDE|AGENTS)\.md$|LICENSE.*|\.gitignore$|\.gitattributes$|\.vscode/|\.idea/|\.editorconfig$|\.github/(ISSUE_TEMPLATE|PULL_REQUEST_TEMPLATE))'

# code_files <파일 목록(줄 단위)> — 비코드 규칙에 걸리지 않는 파일만 출력한다(빈 출력 = 비코드만).
code_files() {
  printf '%s\n' "$1" | sed '/^$/d' | grep -vE "$NON_CODE_RE" || true
}
