# Smart Fire Hub

데이터셋 관리, ETL 파이프라인, AI 에이전트를 통합한 데이터 허브 플랫폼.

## Commands
- `pnpm dev` / `pnpm dev:full` / `pnpm build` / `pnpm test` / `pnpm lint`
- `pnpm db:up` / `pnpm db:down` / `pnpm db:reset`

## Key Files
- 로드맵: `docs/ROADMAP.md`
- 앱별 상세: `apps/firehub-api/CLAUDE.md`, `apps/firehub-web/CLAUDE.md`, `apps/firehub-ai-agent/CLAUDE.md`
- 아키텍처/통신/스택: `.claude/docs/architecture.md`
- 팀 워크플로/계획 원칙: `.claude/docs/team-workflow.md`
- 배포/Docker 규칙: `.claude/docs/deploy.md`

## Rules
- **신규 기능/구현 작업 시작 전**: `docs/ROADMAP.md` 읽고 진행 중 항목 파악. 없으면 사용자에게 확인 후 시작. (탐색 테스트·이슈 수정·크로스체크는 불필요)
- **팀 구성**: 업무 지시 시 `/task-start` 스킬 실행.
- **한국어 주석 필수**: 클래스·메서드·주요 로직에 무엇을·왜 설명.
- **커밋/배포 금지**: 사용자 명시적 승인 후에만 실행. 배포 시 반드시 `.claude/docs/deploy.md` 먼저 읽고 진행.
- **완료 시**: ROADMAP.md 상태 업데이트(⬜→✅) 후 커밋.
- **테스트 필수**: backend/ai-agent → TC, frontend → Playwright E2E.
- **스크린샷**: 탐색 테스트 → `test-results/exploratory/<기능>/<timestamp>/screenshots/`, TC 테스트 → `test-results/tc/<suite>/`
