# Task Start — 팀 구성 및 업무 시작

업무 지시를 받아 project-leader 중심의 팀을 구성하고, project-leader가 팀원을 지휘하여 자율 진행하도록 한다.

사용자 인자: $ARGUMENTS

## 핵심 원칙: 메인 Claude는 팀 매니저일 뿐

메인 Claude(team-lead)는 **팀 생성/확장/모니터링만** 담당한다. 다음 행위는 **절대 금지**:

- `git diff`, `git log`, `git status` 등 git 명령 실행
- `Read`, `Grep`, `Glob` 등으로 프로젝트 파일/코드 조회
- 코드 분석, 변경 사항 파악 등 조사 행위

이러한 조사는 **모두 팀원(analyst, developer 등)의 몫**이다. 메인은 사용자 지시를 그대로 project-leader에게 전달하고, 팀 인프라만 관리한다.

## 인자 파싱

`$ARGUMENTS`에서 실행 모드 플래그와 업무 지시를 분리한다:

- **플래그**: `--auto`(또는 `자동으로`), `--skip-analysis`, `--skip-design`, `--skip-plan`, `--skip-verify` (선두에 위치)
- **업무 지시**: 플래그를 제외한 나머지 텍스트

예시:
- `/task-start 디자인 시스템 검토해줘` → 기본 모드, 업무: "디자인 시스템 검토해줘"
- `/task-start --auto 디자인 시스템 검토해줘` → 자동 모드, 업무: "디자인 시스템 검토해줘"
- `/task-start 자동으로 디자인 시스템 검토해줘` → 자동 모드, 업무: "디자인 시스템 검토해줘"

## 진행 상태 표시 규칙

각 단계를 시작할 때 사용자에게 **명확한 상태 메시지**를 출력한다. 형식:

```
### [N/4] 단계명
> 설명 (무엇을 하는 중인지)
```

예시:
```
### [1/4] 팀 생성 및 초기 멤버 투입
> TeamCreate + project-leader, analyst 스폰 중...

### [2/4] 팀 확장
> project-leader 요청에 따라 frontend-developer, qa-tester 추가 중...

### [3/4] 작업 시작 알림
> project-leader에게 팀 구성 완료 알림 전송

### [4/4] 모니터링
> 팀 자율 진행 중. 완료 보고 또는 블로커 대기...
```

## 실행 절차

### 1단계: 팀 생성 및 초기 멤버 투입

사용자에게 상태를 표시한 뒤, project-leader와 analyst를 함께 투입한다. **사용자 지시를 그대로 전달**하고, 메인이 직접 조사하지 않는다.

```
# 사용자에게 상태 표시
"### [1/4] 팀 생성 및 초기 멤버 투입
> TeamCreate + project-leader, analyst 스폰 중..."

TeamCreate(team_name="smart-fire-hub")

Agent(name="project-leader", subagent_type="project-leader", team_name="smart-fire-hub", run_in_background=true,
  prompt="당신은 smart-fire-hub 팀의 Project Leader입니다.
    업무 지시: {파싱된 업무 지시 — 사용자 원문 그대로}
    실행 모드: {파싱된 실행 모드}
    팀원: analyst (이미 투입됨)
    .claude/agents/project-leader.md의 워크플로를 따라:
    1. analyst에게 업무 분석을 요청하세요
    2. 분석 결과를 바탕으로 필요한 추가 팀원을 team-lead에게 요청하세요")

Agent(name="analyst", subagent_type="analyst", team_name="smart-fire-hub", run_in_background=true,
  prompt="당신은 smart-fire-hub 팀의 Analyst입니다.
    업무 배경: {파싱된 업무 지시 — 사용자 원문 그대로}
    project-leader가 곧 분석 요청을 보낼 예정입니다. 요청을 받으면 즉시 분석을 시작하세요.
    자율 진행 규칙: TaskUpdate로 상태 관리, SendMessage로 결과 전달")
```

project-leader는 analyst와 협업하여:
1. analyst에게 업무 분석 요청 (규모, 관련 앱, 영향 범위)
2. 분석 결과를 바탕으로 추가 팀원 목록을 team-lead에게 요청

### 2단계: 팀 확장 (검증 포함)

project-leader가 추가 팀원을 요청하면, 메인 Claude가 **팀원 구성을 검증한 후** 팀에 추가한다.

#### 2-1. 팀원 구성 검증 (team-lead 안전망)

project-leader의 팀원 요청을 받으면, 업무 지시 키워드와 대조하여 누락된 필수 팀원이 없는지 확인한다:

| 업무 키워드 | 필수 팀원 |
|------------|----------|
| 디자인 시스템, 색상, 토큰, 타이포그래피, 테마, 다크모드 | `ui-ux-designer` |
| API, DB, 마이그레이션, 스키마, 엔드포인트 | `backend-developer` |
| 컴포넌트, 페이지, UI, 프론트엔드, 화면 | `frontend-developer` + `ui-ux-designer` |
| MCP, 에이전트, Claude SDK, 도구 | `ai-agent-developer` |
| 코드 수정, 구현, 리팩토링, 교체, 변환 | `qa-tester` |
| 아키텍처, 연동, 설계, 구조 | `architect` |

**검증 절차**:
1. 업무 지시 원문에서 키워드를 추출
2. 키워드에 해당하는 필수 팀원이 project-leader의 요청에 포함되어 있는지 확인
3. **누락된 필수 팀원이 있으면** → project-leader에게 반려:
   ```
   SendMessage(to="project-leader", "팀원 구성 검증 결과, 다음 필수 팀원이 누락되었습니다: {누락 목록}. 
   이유: {업무에 '키워드'가 포함되어 있어 해당 팀원이 필수입니다}. 팀원 목록을 수정하여 다시 요청해주세요.")
   ```
4. **검증 통과 시** → 팀원 추가 진행

#### 2-2. 팀원 추가

```
# 사용자에게 상태 표시
"### [2/4] 팀 확장
> project-leader 요청에 따라 {요청된 팀원 목록} 추가 중..."

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
# 사용자에게 상태 표시
"### [3/4] 작업 시작 알림
> project-leader에게 팀 구성 완료 알림 전송"

SendMessage(to="project-leader", message="팀 구성 완료. 팀원: {추가된 팀원 목록}. 작업을 시작하세요.")
```

이후 project-leader가 팀 내에서 직접 오케스트레이션:
- TaskCreate로 태스크 생성 (owner, blockedBy 설정)
- SendMessage로 각 팀원에게 작업 지시
- 팀원 간 직접 소통 가능
- project-leader가 진행 상황 추적 및 중재

### 4단계: 모니터링 및 완료

```
# 사용자에게 상태 표시
"### [4/4] 모니터링
> 팀 자율 진행 중. 완료 보고 또는 블로커 대기..."
```

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
