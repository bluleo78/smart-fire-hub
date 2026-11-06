# Smart Fire Hub

데이터셋 관리, ETL 파이프라인, AI 에이전트를 통합한 데이터 허브 플랫폼.

## Commands
- `pnpm dev` / `pnpm dev:full` / `pnpm build` / `pnpm test` / `pnpm lint`
- `pnpm db:up` / `pnpm db:down` / `pnpm db:reset`

## Key Files
- 앱별 상세: `apps/firehub-api/CLAUDE.md`, `apps/firehub-web/CLAUDE.md`, `apps/firehub-ai-agent/CLAUDE.md`
- 아키텍처/통신/스택: `.claude/docs/architecture.md`
- 계획 원칙: `.claude/docs/plan-principles.md`
- 배포/Docker 규칙: `.claude/docs/deploy.md`

## Rules
- **한국어 주석 필수**: 클래스·메서드·주요 로직에 무엇을·왜 설명.
- **커밋/배포 금지**: 사용자 명시적 승인 후에만 실행. 배포 시 반드시 `.claude/docs/deploy.md` 먼저 읽고 진행.
- **워크트리 기본**: 사용자가 명시적으로 메인 체크아웃에서 하라고 지시하지 않는 한, 작업 브랜치에 착수할 때는 `EnterWorktree`로 격리한다 — 메인 체크아웃에서 직접 코드를 수정하지 않는다. 병합은 `ExitWorktree`로 메인 체크아웃에 돌아온 뒤 일반 `git merge`로 한다. 자율 파이프라인(ai-driven-pilot/solver/explorer 등)은 각자 격리 전략이 있으므로 예외.
- **테스트 필수**: backend/ai-agent → TC, frontend → Playwright E2E.
- **스크린샷**: 탐색 테스트 → `test-results/exploratory/<기능>/<timestamp>/screenshots/`, TC 테스트 → `test-results/tc/<suite>/`

## work-loop 로 도는 AI 세션

`/genia-works:work-loop` 로 할당 이슈를 혼자 처리하는 AI 세션(AI 신원, 사람이 지켜보지 않음)에는 아래가 위 Rules·사용자 메모리의 규칙보다 먼저다. 사람과 함께 일하는 세션에는 해당하지 않는다.

- **work-loop 실행 = 커밋·PR·(검토자라면) PR 머지 승인이다.** 위 「커밋/배포 금지」의 커밋, 「main 머지는 의견 들은 뒤」, 「수정 전 설명·승인」 같은 규칙은 사람과 함께 일하는 세션의 규칙이라 적용하지 않는다. 개발한 일은 origin(GitHub `bluleo78/smart-fire-hub`)의 `main` 대상 PR 로 올리고, 검토자(AI 팀장)가 리뷰·머지한다. 최신 main 반영(rebase·충돌 해결)은 개발자가 한다. MAIN(메인 체크아웃)에는 머지하지 않는다.
- **묻는 때는 UI/UX·아키텍처·설계·접근 방안에서 명확하지 않은 부분이 있을 때뿐이다**(스킬 `process-issue.md` 4번 기준). 그 밖의 결정은 스스로 내리고 「내가 정한 것」으로 기록한다.
- **여전히 하지 않는다**: main 직접 push, gitlab 원격 push, 태그, 운영 배포(`scripts/deploy.sh`·`scripts/update.sh`·`docker-compose.prod.yml`), 이슈 DONE 처리. push 는 PR 흐름(개발자의 기차 브랜치)에서만 한다. 예외는 사람이 만든 이슈 본문이 명시적으로 시킨 닫기(그 지시로 내가 만든 하위와, 본문이 닫으라고 한 그 이슈 자신)뿐이다(스킬 `SKILL.md` 「본문 지시」 범위).
- **이슈 만들기·담당 바꾸기는 사람처럼 한다** — 방법은 스킬 `SKILL.md` 「이슈 만들기·담당 바꾸기」를 따른다.
- 테스트·라이브 검증·기차(PR) 흐름은 [`.work-loop/project.md`](.work-loop/project.md) 를 따른다. 실행 상태는 `.work-loop/state/`(git 무시).

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
