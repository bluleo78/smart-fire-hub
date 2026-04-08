# Task Start — 팀 구성 및 업무 시작

업무 지시를 받아 project-leader 중심의 팀을 구성하고, project-leader가 팀원을 지휘하여 자율 진행하도록 한다.

사용자 인자: $ARGUMENTS

## 인자 파싱

`$ARGUMENTS`에서 실행 모드 플래그와 업무 지시를 분리한다:

- **플래그**: `--auto`(또는 `자동으로`), `--skip-analysis`, `--skip-design`, `--skip-plan`, `--skip-verify` (선두에 위치)
- **업무 지시**: 플래그를 제외한 나머지 텍스트

예시:
- `/task-start 디자인 시스템 검토해줘` → 기본 모드, 업무: "디자인 시스템 검토해줘"
- `/task-start --auto 디자인 시스템 검토해줘` → 자동 모드, 업무: "디자인 시스템 검토해줘"
- `/task-start 자동으로 디자인 시스템 검토해줘` → 자동 모드, 업무: "디자인 시스템 검토해줘"

## 실행 절차

### 1단계: 팀 생성 및 초기 멤버 투입

project-leader와 analyst를 함께 투입하여 업무 분석과 팀 구성을 병렬로 진행한다.

```
TeamCreate(team_name="smart-fire-hub")

Agent(name="project-leader", subagent_type="project-leader", team_name="smart-fire-hub", run_in_background=true,
  prompt="당신은 smart-fire-hub 팀의 Project Leader입니다.
    업무 지시: {파싱된 업무 지시}
    실행 모드: {파싱된 실행 모드}
    팀원: analyst (이미 투입됨)
    .claude/agents/project-leader.md의 워크플로를 따라:
    1. analyst에게 업무 분석을 요청하세요
    2. 분석 결과를 바탕으로 필요한 추가 팀원을 team-lead에게 요청하세요")

Agent(name="analyst", subagent_type="analyst", team_name="smart-fire-hub", run_in_background=true,
  prompt="당신은 smart-fire-hub 팀의 Analyst입니다.
    업무 배경: {파싱된 업무 지시}
    project-leader가 곧 분석 요청을 보낼 예정입니다. 요청을 받으면 즉시 분석을 시작하세요.
    자율 진행 규칙: TaskUpdate로 상태 관리, SendMessage로 결과 전달")
```

project-leader는 analyst와 협업하여:
1. analyst에게 업무 분석 요청 (규모, 관련 앱, 영향 범위)
2. 분석 결과를 바탕으로 추가 팀원 목록을 team-lead에게 요청

### 2단계: 팀 확장

project-leader가 분석 결과를 바탕으로 추가 팀원을 요청하면, 메인 Claude가 해당 에이전트들을 팀에 추가:

```
Agent(name="{에이전트명}", subagent_type="{에이전트타입}", team_name="smart-fire-hub", run_in_background=true,
  prompt="당신은 smart-fire-hub 팀의 {역할}입니다.
    팀원: {현재 팀원 목록}
    project-leader의 작업 지시를 기다려주세요.
    
    자율 진행 규칙:
    1. project-leader로부터 작업 지시를 받으면 즉시 시작
    2. 태스크 시작 시 TaskUpdate(in_progress), 완료 시 TaskUpdate(completed) 마킹
    3. 완료 후 후속 팀원에게 SendMessage로 결과 전달
    4. TaskList 확인하여 다음 unblocked 자기 태스크 수행
    5. 블로커 발생 시 SendMessage(to='project-leader')로 보고")
```

project-leader가 요청한 에이전트만 추가한다 — 미리 전원 투입하지 않음.

### 3단계: project-leader에게 작업 시작 알림

```
SendMessage(to="project-leader", message="팀 구성 완료. 팀원: {추가된 팀원 목록}. 작업을 시작하세요.")
```

이후 project-leader가 팀 내에서 직접 오케스트레이션:
- TaskCreate로 태스크 생성 (owner, blockedBy 설정)
- SendMessage로 각 팀원에게 작업 지시
- 팀원 간 직접 소통 가능
- project-leader가 진행 상황 추적 및 중재

### 4단계: 모니터링 및 완료

메인 Claude는:
- project-leader/팀원 메시지를 자동 수신
- project-leader가 해결 못하는 블로커 발생 시 → 사용자에게 에스컬레이션
- project-leader가 최종 완료 보고 시 → 사용자에게 전달
- **커밋/배포는 반드시 사용자 승인 후 진행**

## 에이전트 매핑

| 역할 | Agent name | subagent_type |
|------|-----------|---------------|
| Project Leader | `project-leader` | `project-leader` |
| Analyst | `analyst` | `analyst` |
| Architect | `architect` | `architect` |
| Backend Developer | `backend-developer` | `backend-developer` |
| Frontend Developer | `frontend-developer` | `frontend-developer` |
| AI Agent Developer | `ai-agent-developer` | `ai-agent-developer` |
| UI/UX Designer | `ui-ux-designer` | `ui-ux-designer` |
| QA Tester | `qa-tester` | `qa-tester` |
| Project Manager | `project-manager` | `project-manager` |

## 실행 모드

| 모드 | 키워드 | 동작 |
|------|--------|------|
| 기본 | (없음) | project-leader가 각 Phase 완료 시 team-lead에게 보고 → 메인이 사용자에게 전달 |
| 자동 | `--auto`, `자동으로` | project-leader가 Phase 1~5 자율 진행, 완료 시에만 보고 |
| 부분 자동 | `--skip-analysis` 등 | 지정 Phase만 자동, 나머지는 보고 |

## 주의사항

- 메인 Claude는 팀 생성/확장만 담당, 실무 지시는 project-leader가 수행
- project-leader는 반드시 `TeamCreate` 후 팀 멤버로 스폰한다 — 팀 없이 단독 `Agent(subagent_type="project-leader")`로 스폰하면 위임이 동작하지 않음
- project-leader가 팀원 추가를 요청할 때만 팀을 확장 — 미리 전원 투입하지 않음

## 예외 처리

- **TeamCreate 실패 (team_name 충돌)**: 기존 팀이 있으면 `TeamDelete` 후 재생성하거나, 기존 팀에 멤버를 추가
- **project-leader가 팀원 요청 없이 직접 작업 시작**: SendMessage로 "팀원을 요청해주세요. 직접 코드 수정은 하지 마세요."라고 안내
- **project-leader 무응답**: 일정 시간 후 SendMessage로 상태 확인, 여전히 무응답이면 사용자에게 에스컬레이션
