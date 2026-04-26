---
name: ai-driven-pilot
description: ai-driven-explorer와 ai-driven-solver를 subagent로 자율 호출하여 GitHub 이슈 사이클(신규 발견 → 수정 → 크로스체크 → close)을 **여러 이슈에 걸쳐 연속 자율 운영**하는 스킬. 사용자가 "파일럿 켜줘", "오토파일럿 시작", "이슈 자동 처리해줘", "야간 사이클 돌려줘", "이슈 다 처리해줘", "버그 자율 처리", "auto pilot", "autopilot 모드", "쌓인 이슈 정리해줘" 같이 **여러 이슈를 자율로 처리**하려는 의도가 보일 때 반드시 이 스킬을 사용한다. "55번 이슈 처리해줘"처럼 **단일 이슈 지정**은 ai-driven-solver로 라우팅. 자율 사이클이지만 회귀 3회·security 라벨·테스트/훅 반복 실패 등 임계값에 도달하면 일시정지하고 사람에게 에스컬레이션한다.
---

# AI-Driven Pilot

자율 이슈 사이클 운영 스킬. 사람은 보조석에서 모니터링·에스컬레이션만 처리한다.

> 이슈 라이프사이클 다이어그램·라벨 정의: `.claude/docs/issue-lifecycle.md`
> 하위 도구: `ai-driven-explorer`, `ai-driven-solver`

## 핵심 원칙

- **Pilot은 결정 주체**. explorer/solver는 도구. Pilot이 큐를 보고 우선순위 정하고 subagent로 호출만 한다.
- 사이클 진입은 **사용자 명시적 승인 필수**. 한 번 켜면 큐가 비거나 에스컬레이션이 발생할 때까지 자율 동작.
- **모든 자율 결정은 GitHub 이슈 코멘트로 기록** → 사후 감사 가능.
- 에스컬레이션 = 일시정지·보고. pilot이 임의로 우회·생략하지 않는다.
- subagent 호출은 메인 컨텍스트 보호용. 누적 이슈 50건도 메인 컨텍스트 안 터짐.
- **solver/explorer는 한 번에 1개만 실행**. 동시 실행 시 pre-commit hook(Gradle 테스트)이 병렬로 돌아 DB 연결 풀 고갈 → flaky 실패 → 재시도 낭비. 이전 subagent 완료 후 다음 dispatch.

---

## 1. 사전 점검 — 진입 전 환경 확인

하나라도 실패면 사용자에게 보고 후 **중단**. pilot이 임의로 환경을 바꾸지 않는다.

```bash
# 1) 작업 트리 깨끗?
git status --porcelain | head -1
# 출력 있으면 dirty → "stash 또는 commit 후 다시 호출하세요" 안내 후 종료

# 2) 현재 브랜치
git rev-parse --abbrev-ref HEAD
# main이 아니면 사용자에게 확인 ("이 브랜치에서 진행할까요?")

# 3) pnpm dev 가용?
curl -sf http://localhost:5173 > /dev/null && echo OK || echo NOPE
# NOPE이면 "pnpm dev 실행 후 다시 호출" 안내 후 종료

# 4) 미정리 pilot 라벨 잔존 체크 (이전 사이클이 비정상 종료된 흔적)
gh issue list --label "pilot:processing" --state open --json number
# 출력 있으면 사용자에게 "이전 처리 중이던 이슈입니다, 정리하고 시작할까요?"

# 5) 미정리 pilot playwright 세션 잔존 체크 (이전 사이클이 close 안 한 흔적)
playwright-cli list 2>&1 | grep -E "browser \"p[sce][0-9a-f]+\"" | head
# 출력 있으면 사용자에게 알리고, 동의 시 각 세션을 개별 close.
# 좀비(close해도 'is not open' 응답)가 잡히면, 다른 브라우저 세션이 없는 경우에 한해
# `playwright-cli kill-all` 사용을 사용자에게 제안. 좀비는 보통 소켓 경로가 길어 발생 →
# 세션 이름이 짧은 `ps<N>`/`pc<N>`/`pe<rand>` 컨벤션을 따르면 거의 발생하지 않음.
```

---

## 2. 한도 설정 — 옵션 (디폴트 무제한)

기본은 무제한. 사용자 인자가 있을 때만 적용.

```
사용자 발화 → 한도 해석 예:
- "파일럿 켜줘"               → 무제한 (큐 소진 또는 에스컬레이션까지)
- "파일럿 1시간만"            → 시간 2시간 → 1h
- "파일럿 3건만"              → 이슈 수 3
- "파일럿 야간 8시간 5건"     → 시간 8h + 이슈 수 5 (먼저 도달하는 쪽)
```

해석한 한도를 사용자에게 한 번 더 확인:
```
파일럿 시작 — 한도: [무제한 / 1시간 / 5건]
진행 중 라벨: pilot:processing / pilot:escalated
시작할까요?
```

---

## 3. 사이클 루프

```
WHILE 큐가 비어있지 않음 AND 한도 미도달 AND 사용자 중단 신호 없음:
  3.1 현황 분류
  3.2 다음 액션 한 개 선정 (우선순위)
  3.3 에스컬레이션 트리거 사전 검사
  3.4 subagent 호출 (explorer 또는 solver)
  3.5 결과 수신 → 라벨 정리 → 다음 루프
```

### 3.1 현황 분류

```bash
gh issue list --state open --json number,title,labels --limit 100 > /tmp/issues.json

# 분류 우선순위 (위에서부터 검사 — 첫 매칭에서 바로 분류 결정):
# 1. design / a11y / perf / security 라벨 → 사람 큐 (스킵, 다른 라벨 무관)
#    이유: 본문 형식·검증 방법이 bug perspective와 달라 solver/explorer 자율 처리 부적합.
#    `resolved + design` 같이 두 라벨 동시 부착돼도 design이 우선 — explorer 크로스체크의
#    Step C3 "재현 단계 실행"이 design 본문엔 없으므로 일치하지 않음.
# 2. needs-info / pilot:escalated / on-hold / deferred 라벨 → 사람 큐 (스킵)
# 3. regression 라벨 → solver 재처리 큐
# 4. resolved 라벨 → explorer 크로스체크 큐
# 5. severity:* 라벨 + 위 어디에도 안 속함 → 신규 솔버 큐
```

### 3.2 우선순위 (높은 것부터)

| 순위 | 조건 | 사유 |
|------|------|------|
| 1 | `regression` + `severity:critical` | 회귀 + 치명도 → 가장 위험 |
| 2 | `regression` + 기타 severity | 회귀는 신뢰 회복이 우선 |
| 3 | `resolved` 라벨 | 검증 빨리 끝내야 다음 사이클 가능 |
| 4 | 신규 + `severity:critical` | 치명도 |
| 5 | 신규 + `severity:major` | |
| 6 | 신규 + `severity:minor` | |
| 7 | 신규 + `severity:ux` | |
| — | 큐 전체가 빔 | 탐색 1회 (선택, Section 3.6) |

### 3.3 에스컬레이션 트리거 (사전 검사)

이슈에 트리거 조건이 이미 충족되어 있으면 **subagent 호출 자체를 안 함**. 사람 큐로 빼고 다음으로.

| 트리거 | 검사 방법 | 동작 |
|--------|----------|------|
| **회귀 3회+** | 이슈 코멘트에서 "🔴 회귀 발견" 카운트 | `pilot:escalated` 라벨 + 코멘트 "회귀 3회 도달, 사람 검토 필요" + skip |
| **Security** | 라벨 `security` 부착됨 (또는 legacy 이슈는 본문에 "SQL/XSS/IDOR/권한 우회/보안" 키워드 + `severity:critical` AND `security` 라벨 미존재) | 코멘트 "보안 이슈는 사람 결정 후 close" + skip |
| **이미 escalated** | 라벨 `pilot:escalated` 있음 | 무조건 skip (사람이 라벨 제거할 때까지) |
| **Non-bug perspective** | 라벨 `design` / `a11y` / `perf` 있음 | skip — 본문 형식이 bug perspective와 달라 solver 자율 수정 부적합 (디자인 토큰 의사결정·측정값 캡처·SR 청취 등 사람 영역) |

### 3.4 Subagent 호출

호출 직전 `pilot:processing` 라벨 부착 + 보드 배정 + **Status → "In progress"** 전환 (Section 3.6 참조):
```bash
gh issue edit <번호> --add-label "pilot:processing"
# 보드에 추가 + 현재 iteration 배정 (이미 있으면 idempotent)
bash .claude/skills/ai-driven-pilot/scripts/add-to-board.sh <번호>
# 상태 전환
bash .claude/skills/ai-driven-pilot/scripts/board-status.sh <번호> in_progress
```

**액션별 subagent 프롬프트** (자기완결적이어야 함 — subagent는 메인 대화 못 봄).

> 세션 이름 전달 방식: Agent SDK는 환경변수를 격리하므로 SESSION_NAME 환경변수 직접 전달이 불가하다. prompt 본문에 `SESSION=ps<N>` 형태로 export 명령을 박아넣어 subagent가 첫 명령으로 실행하도록 한다. solver/explorer는 `${SESSION_NAME:-default}` 폴백을 갖고 있어 양쪽 호환.

#### A. 신규/재처리 → solver
```
Agent(
  subagent_type="general-purpose",
  description="이슈 #N solver 처리",
  prompt="""ai-driven-solver 스킬을 사용하여 GitHub 이슈 #N을 처리하라.

배경:
- 이 작업은 ai-driven-pilot이 자율 사이클로 호출함
- **playwright-cli 세션 이름은 `ps<N>` 사용** (예: 이슈 #45 → `ps45`). 짧게 쓰는 이유: macOS Unix 소켓 경로 104바이트 한도 — 이름이 길면 소켓 생성 실패 → 좀비 세션 발생. 첫 명령은 `SESSION=ps<N>`으로 export 후 진행.
- 작업 종료 시(정상/실패 무관) 반드시 `playwright-cli -s=$SESSION close` 실행해 세션 leak 방지.
- 처리 완료 후 stdout 마지막 줄에 다음 중 하나로 보고 (이슈 번호 #N 포함):
  RESULT: #N / success / <커밋해시> / <변경파일수>
  RESULT: #N / test_failed_2 / <마지막 실패 메시지>
  RESULT: #N / hook_failed_2 / <어느 단계>
  RESULT: #N / reproduce_failed / <사유>
  RESULT: #N / blocked / <사유>

자율 사이클이므로 사용자 확인 단계는 skip하고 솔버의 자체 판단으로 진행하라.
단, 다음은 자동 진행 금지 — 그대로 결과 보고:
- 사전 재현이 안 됨 → reproduce_failed (solver가 needs-info 라벨 부착)
- 테스트 2회 실패 → test_failed_2
- 훅 2회 실패 → hook_failed_2
"""
)
```

#### B. resolved 검증 → explorer 크로스체크
```
Agent(
  subagent_type="general-purpose",
  description="이슈 #N 크로스체크",
  prompt="""ai-driven-explorer 스킬을 크로스체크 모드로 사용하여 이슈 #N을 검증하라.

배경:
- 이 작업은 ai-driven-pilot이 자율 사이클로 호출함
- **playwright-cli 세션 이름은 `pc<N>` 사용** (예: 이슈 #38 → `pc38`). 짧게 쓰는 이유는 solver와 동일 (소켓 경로 한도). `SESSION=pc<N>`으로 export 후 진행.
- 작업 종료 시(통과/회귀/blocked 무관) 반드시 `playwright-cli -s=$SESSION close` 실행해 세션 leak 방지.
- 검증 완료 후 stdout 마지막 줄에 다음 중 하나로 보고 (이슈 번호 #N 포함):
  RESULT: #N / passed / closed
  RESULT: #N / regression / <회귀 회차>
  RESULT: #N / blocked / <사유>

자율 사이클이므로 사용자 확인 단계는 skip하고 explorer의 자체 판단으로 진행하라.
"""
)
```

#### C. 큐 빔 → 탐색 (선택)

큐가 완전히 빈 경우만, 한 사이클당 1회까지. 사용자가 "탐색도 해줘" 옵션을 켰을 때만 작동. 탐색은 특정 이슈에 귀속되지 않으므로 `pilot:processing` 라벨/보드 in_progress 전환은 **하지 않는다** — 발견된 신규 이슈는 explorer가 자체적으로 보드에 추가한다.

```
Agent(
  subagent_type="general-purpose",
  description="탐색 사이클 1회",
  prompt="""ai-driven-explorer 스킬을 탐색 모드로 사용하여 신규 버그를 발견·등록하라.

배경:
- 이 작업은 ai-driven-pilot이 자율 사이클로 호출함
- **playwright-cli 세션 이름은 `pe<rand4>` 사용** (탐색은 이슈 번호 없으니 짧은 hex suffix). 짧게 쓰는 이유는 동일 (소켓 경로 한도). `SESSION=pe$(openssl rand -hex 2)`로 export 후 진행.
- 작업 종료 시 반드시 `playwright-cli -s=$SESSION close` 실행해 세션 leak 방지.
- 발견된 이슈는 `gh issue create` 직후 explorer 스킬의 안내에 따라 `add-to-board.sh`로 보드에 자동 추가됨.
- 탐색 완료 후 stdout 마지막 줄에 다음 중 하나로 보고:
  EXPLORER_DONE: <N>,<M>,...     (1건 이상 발견)
  EXPLORER_DONE: none            (탐색했으나 신규 없음)
  EXPLORER_DONE: blocked: <사유>  (브라우저 기동 실패 등)

자율 사이클이므로 사용자 확인 단계는 skip하고 explorer의 자체 판단으로 진행하라.
"""
)
```

pilot의 탐색 결과 처리:

| explorer 결과 | pilot 동작 |
|--------------|-----------|
| `EXPLORER_DONE: <N>,<M>,...` | 큐를 재스캔(3.1)하고 발견된 이슈를 신규 솔버 큐로 처리. 발견된 이슈는 explorer가 이미 보드 추가까지 완료한 상태. **사용자 확인 없이 자동으로 루프 계속** |
| `EXPLORER_DONE: none` | 큐도 비고 탐색도 신규 없음 → 사이클 정상 종료 |
| `EXPLORER_DONE: blocked: <사유>` | 일시정지, 코멘트 없이 종료 (특정 이슈에 귀속되지 않음) |
| EXPLORER_DONE 라인 없거나 형식 깨짐 | `blocked` 처리 + 일시정지 |

### 3.5 결과 수신 → 라벨 정리

```bash
# 처리 라벨 제거
gh issue edit <번호> --remove-label "pilot:processing"
```

RESULT 형식: `RESULT: #<N> / <상태> / <부가정보>` — `#<N>`은 처리한 이슈 번호 (정수, gh 컨벤션).

**Sanity check**: pilot이 dispatch한 이슈 번호와 RESULT의 `#<N>`이 다르면 → `blocked` 처리 + 일시정지 (subagent가 잘못된 이슈 건드린 사고).

| subagent RESULT (`#<N> /` 이후) | pilot 동작 | 보드 상태 전환 |
|-----------------|-----------|-----------|
| `success` | 진행 카운트 +1, 다음 루프 | `in_review` (crosscheck 대기) |
| `passed` (크로스체크 통과) | close는 explorer가 이미 함, 카운트 +1, 다음 루프 | `done` |
| `regression` (회귀 K회) | 다음 루프 (K < 3이면 다음 사이클에서 solver가 다시 잡음). K ≥ 3 도달은 다음 진입 시 3.3에서 차단됨. | 변경 없음 |
| `blocked / non_bug_perspective` | 정상 라우팅 (라벨 보고 거른 것). 일시정지 X, **다음 루프 계속**. design/a11y/perf 라벨이 이미 부착되어 있어 다음 사이클에서도 사람 큐로 분류됨 | `ready` (사람이 픽업해야 함) |
| `test_failed_2` / `hook_failed_2` / `reproduce_failed` / `blocked` (위 외) | `pilot:escalated` 라벨 부착 + 코멘트 + **사이클 일시정지** (환경/분석 문제는 다음 이슈에서도 반복될 가능성) | `ready` (사람이 픽업해야 함) |
| RESULT 라인 자체가 없거나 형식 깨짐 | `blocked` 처리 + `pilot:processing` 제거 + 일시정지 (silent failure 방지) | `ready` (사람이 픽업해야 함) |

---

## 3.6 GitHub Projects 보드 상태 동기화

이슈 사이클 단계(solver 시작 → 수정 완료 → 검증 통과 → 에스컬레이션)에 맞춰 보드 Status 필드를 자동 전환한다.

스크립트:
- `bash .claude/skills/ai-driven-pilot/scripts/add-to-board.sh <이슈번호>` — 보드 추가 + 현재 iteration 배정 + Status=ready (solver 호출 전 1회)
- `bash .claude/skills/ai-driven-pilot/scripts/board-status.sh <이슈번호> <상태>` — Status 전환만 (상태값: `ready` | `in_progress` | `in_review` | `done`)

보드에 없는 이슈는 `add-to-board.sh`가 자동 추가. `board-status.sh`는 이미 보드에 있는 이슈에만 동작.

### 상태 전환 매핑

| 타이밍 | 보드 Status | 이유 |
|--------|------------|------|
| solver 호출 직전 | `in_progress` | 작업 시작 |
| solver RESULT: success | `in_review` | crosscheck 대기 |
| crosscheck RESULT: passed | `done` | 완료 |
| 에스컬레이션 / blocked | `ready` | 사람이 픽업해야 함 ("backlog=안 시작됨"은 부정확) |

보드에 없는 이슈(ITEM_ID 조회 실패)는 조용히 스킵 — 파일럿 흐름 중단 없음.

---

## 4. 사이클 종료

종료 사유:
- 큐 소진 (정상)
- 한도 도달 (시간 또는 이슈 수)
- `test_failed_2` / `hook_failed_2` / `reproduce_failed` / `blocked` 발생 (일시정지)
- 사용자 중단 명령 ("파일럿 멈춰")

### 종료 직전 cleanup

종료 사유와 무관하게 **반드시 다음 정리 수행**:

```bash
# 1) pilot:processing 라벨 잔존 제거 (subagent가 비정상 종료한 흔적)
for issue in $(gh issue list --label "pilot:processing" --state open --json number -q '.[].number'); do
  gh issue edit $issue --remove-label "pilot:processing"
done

# 2) pilot 관련 playwright 세션 잔존 close (subagent가 close 누락한 흔적)
# `sort -u` 안 쓰는 이유: 같은 이름 중복 등록 시 모두 닫기 위함.
for s in $(playwright-cli list 2>&1 | grep -oE 'browser "p[sce][0-9a-f]+"' | sed 's/browser "//; s/"//'); do
  playwright-cli -s="$s" close 2>/dev/null
done

# 좀비 감지 — close가 'is not open' 응답하는 entry는 소켓 경로 EINVAL 등으로 비정상 등록된 좀비.
# 짧은 컨벤션(`ps<N>`/`pc<N>`/`pe<rand>`)이면 거의 발생하지 않지만,
# 만약 발견되면 다른 브라우저가 없는지 확인 후 사용자 동의 하에 `playwright-cli kill-all` 검토.
```

이 cleanup 덕에 다음 사이클 진입 시 사전 점검(Section 1)에서 stale 흔적이 안 잡힘 → 깨끗한 시작.

### 종료 보고 포맷

```
## 파일럿 사이클 — 시작 YYYY-MM-DDTHH:MM / 종료 HH:MM (총 N분)

처리 완료: M건
- #45 (Major) — solver fix → resolved → crosscheck pass → close
- #38 (Minor) — crosscheck pass → close
- #99 (Critical) — solver fix → resolved (다음 사이클 크로스체크 대상)
- #102 (UX) — solver fix → resolved → crosscheck regression (회귀 1회)

에스컬레이션: K건 (pilot:escalated 라벨 부착됨)
- #21 — 회귀 3회 도달
- #88 — security severity (사람 검토 필요)
- #15 — solver test_failed_2 (테스트 두 번 실패)

스킵 (사람 큐): L건
- #18 (needs-info — 추가 정보 필요)
- #25 (design — 디자인 토큰 의사결정 필요)
- #31 (a11y — WCAG SC 의미 판단 + SR 청취)
- #44 (perf — DevTools 측정값·heap snapshot 비교 필요)
- #88 (security — 사람 결정 후 close)

종료 사유: 큐 소진 / 한도 도달 / 일시정지(상세)
다음 사이클 추천: 사람이 에스컬레이션 라벨 제거 후 재시작
```

---

## 5. 일시정지 / 중단

- 사용자가 "파일럿 멈춰" / "오토파일럿 끄기" 발화 → **진행 중인 subagent 완료 대기** 후 그래스풀 중단 (라벨 정리)
- 강제 중단(세션 종료) 시 `pilot:processing` 라벨이 남을 수 있음 → 다음 시작 시 사전 점검 4번 항목에서 감지

---

## 주의사항

- **메인 브랜치 직접 작업** 환경: pre-commit 훅(lint/typecheck/e2e/gradle test)이 자동 차단망 역할. PR 라인 한도 같은 별도 게이트는 두지 않음.
- 모든 자율 close 결정은 **반드시 explorer 크로스체크를 거침**. solver의 fix만으로 close 되지 않는다 (라이프사이클 모델).
- `pilot:escalated`가 부착된 이슈는 **사람이 라벨을 제거할 때까지 pilot이 다시 손대지 않음**. 같은 이슈에서 자율 사이클이 무한히 시도하는 것을 방지.
- subagent 호출 자체가 사용자가 명시적으로 켰을 때만 발생. cron 등 자동 트리거는 안정 확인 후 별도 검토.
- subagent의 RESULT 보고가 없거나 파싱 실패 시 → `blocked` 처리하고 일시정지 (silent failure 방지).
- 이슈 라이프사이클 변경 (라벨 set, close reason 등)은 모두 `.claude/docs/issue-lifecycle.md` 모델을 따른다. pilot이 별도 라벨 체계를 만들지 않는다 (단, `pilot:processing` / `pilot:escalated` 두 메타 라벨만 도입).
