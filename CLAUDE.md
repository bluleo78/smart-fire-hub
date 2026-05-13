# Smart Fire Hub

데이터셋 관리, ETL 파이프라인, AI 에이전트를 통합한 데이터 허브 플랫폼.

## Commands
- `pnpm dev` / `pnpm dev:full` / `pnpm build` / `pnpm test` / `pnpm lint`
- `pnpm db:up` / `pnpm db:down` / `pnpm db:reset`

## Key Files
- 앱별 상세: `apps/firehub-api/CLAUDE.md`, `apps/firehub-web/CLAUDE.md`, `apps/firehub-ai-agent/CLAUDE.md`
- 아키텍처/통신/스택: `.claude/docs/architecture.md`
- 팀 워크플로/계획 원칙: `.claude/docs/team-workflow.md`
- 배포/Docker 규칙: `.claude/docs/deploy.md`

## Rules
- **팀 구성**: 업무 지시 시 `/task-start` 스킬 실행.
- **한국어 주석 필수**: 클래스·메서드·주요 로직에 무엇을·왜 설명.
- **커밋/배포 금지**: 사용자 명시적 승인 후에만 실행. 배포 시 반드시 `.claude/docs/deploy.md` 먼저 읽고 진행.
- **테스트 필수**: backend/ai-agent → TC, frontend → Playwright E2E.
- **스크린샷**: 탐색 테스트 → `test-results/exploratory/<기능>/<timestamp>/screenshots/`, TC 테스트 → `test-results/tc/<suite>/`

## Serena MCP 활용 지침
코드 탐색·편집 시 Serena의 시맨틱 도구를 우선 사용한다. 전체 파일을 무작정 읽지 말고, 필요한 심볼·라인만 점진적으로 획득한다.

- **세션 시작**: 새 대화 시작 시 `activate_project`로 `smart-fire-hub` 활성화, `check_onboarding_performed`로 온보딩 상태 확인. 온보딩 메모리(`project_overview`, `suggested_commands`, `code_style_conventions`, `task_completion_checklist`, `team_workflow`)는 `list_memories` / `read_memory`로 참조.
- **코드 탐색 우선순위** (위에서 아래로):
  1. `get_symbols_overview` — 파일의 심볼 목록만 빠르게 파악
  2. `find_symbol` (`include_body=false`, `depth=1`) — 구조 파악
  3. `find_symbol` (`include_body=true`) — 필요한 심볼 본문만 읽기
  4. `find_referencing_symbols` — 호출/참조 관계 추적
  5. `search_for_pattern` — 심볼명 불명확 시 패턴 검색
  6. `read_file` 전체 읽기는 마지막 수단
- **편집 우선순위**:
  - 심볼 단위 교체: `replace_symbol_body`, `insert_before_symbol`, `insert_after_symbol`
  - 일부 라인만 수정: `replace_content` (regex)
  - 전체 재작성은 회피 (작은 변경은 diff-only)
- **참조 정합성**: 심볼 수정 시 `find_referencing_symbols`로 호출처 확인 후 일괄 업데이트 (backward-compatible 아닌 경우).
- **라인 번호**: Serena 도구가 반환하는 라인 번호는 **0-based**.
- **메모리 관리**: 프로젝트에 의미 있는 새 규칙·아키텍처 발견 시 `write_memory`로 저장. 잘못된/낡은 메모리는 `delete_memory` 또는 `edit_memory`.
- **언어 서버**: TypeScript / Java 모두 활성화됨 — `find_declaration`, `find_implementations`, `get_diagnostics_for_file` 사용 가능.
- **금지**: 시맨틱 검색이 가능한 작업에 `grep`/`find` 우선 사용, 이미 전체 읽은 파일을 다시 심볼 도구로 분석.
