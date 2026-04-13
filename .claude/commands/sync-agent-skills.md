# Sync Agent Skills

에이전트 파일의 Skills 섹션을 현재 설치된 스킬 목록과 동기화한다.

## 실행 절차

### 1단계: 현재 스킬 목록 수집

시스템에 등록된 모든 스킬을 수집한다. 다음 소스를 확인:

- **시스템 스킬**: 대화 컨텍스트의 `<system-reminder>`에 나열된 스킬 목록
- **로컬 스킬**: `~/.claude/skills/*/SKILL.md` 파일들
- **프로젝트 스킬**: `.claude/commands/*.md` 파일들

각 스킬에 대해 다음 정보를 정리:
- 스킬 이름 (슬래시 커맨드 형태: `/prefix:name`)
- 한 줄 설명
- 대상 역할 키워드 (예: "frontend", "testing", "architecture", "debugging", "AI SDK")

### 2단계: 에이전트-스킬 매핑 규칙

각 에이전트 파일(`.claude/agents/*.md`)을 읽고, 에이전트의 Role과 Responsibilities를 분석하여 관련 스킬을 매핑한다.

#### 매핑 기준

| 에이전트 | 매핑 키워드 |
|---------|-----------|
| **Project Manager** | 계획(plan), 팀(team), 병렬(parallel), 실행(execute), 브랜치(branch), 자율(autopilot) |
| **Architect** | 아키텍처(architecture), 설계(design), 브레인스토밍(brainstorm), 리뷰(review), 추적(trace), 외부문서(external), 품질(quality) |
| **Backend Developer** | TDD, 디버깅(debug), 검증(verify), 코드품질(quality), 단순화(simplify), Java, Spring |
| **Frontend Developer** | 프론트엔드(frontend), React, UI, 디자인(design), TDD, 디버깅(debug), 성능(performance), 검증(verify) |
| **AI Agent Developer** | Claude API, Agent SDK, MCP, TDD, 디버깅(debug), 검증(verify), 외부문서(external) |
| **QA Tester** | QA, 검증(verify), 테스트(test), 디버깅(debug), 추적(trace), 리뷰(review) |
| **UI/UX Designer** | UI, UX, 프론트엔드(frontend), 디자인(design), 브레인스토밍(brainstorm), 시각(visual), 접근성(accessibility) |

#### 스킬 분류 태그

스킬을 다음 태그로 분류하여 에이전트에 매핑:

- `planning`: plan, ralplan, writing-plans, executing-plans, brainstorming
- `execution`: team, autopilot, ralph, ultrawork, dispatching-parallel-agents, subagent-driven-development
- `development`: test-driven-development, systematic-debugging, simplify, karpathy-guidelines
- `frontend`: frontend-design, web-design-guidelines, vercel-react-best-practices, visual-verdict
- `ai-sdk`: claude-api, agent-sdk-dev
- `review`: requesting-code-review, receiving-code-review, code-review
- `verification`: verification-before-completion, verify, ultraqa
- `investigation`: trace, debug, external-context
- `git`: finishing-a-development-branch

### 3단계: Skills 섹션 업데이트

각 에이전트 파일에서 `## Skills` 섹션을 찾아 교체한다.

**형식**:

```markdown
## Skills

{에이전트 역할}에서 다음 스킬을 활용한다:

| 스킬 | 용도 | 언제 사용 |
|------|------|-----------|
| `/{skill-name}` | {한 줄 설명} | {구체적 사용 시점} |
```

**규칙**:
- 에이전트당 최대 8개 스킬 (핵심만 선별)
- "언제 사용" 컬럼은 해당 에이전트의 실제 워크플로에 맞춰 구체적으로 작성
- 이전에 있던 스킬 중 여전히 유효한 것은 유지
- 새로 발견된 관련 스킬은 추가
- 더 이상 설치되지 않은 스킬은 제거

### 4단계: 결과 보고

업데이트 결과를 요약하여 보고:

```
## Agent Skills 동기화 결과

- 스캔된 스킬: N개
- 업데이트된 에이전트: N개
- 변경 사항:
  - {agent}: +{added}개 추가, -{removed}개 제거
  - ...
```

## 주의사항

- 에이전트 파일의 Skills 섹션 **외의** 내용(Role, Responsibilities, Workflow, Coordination 등)은 절대 수정하지 않는다
- `## Skills` 섹션이 없는 에이전트 파일에는 `## Coordination` 바로 앞에 새로 삽입한다
- 매핑이 애매한 스킬은 추가하지 않는다 — 확실한 것만 포함
