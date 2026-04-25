# GitHub Issue 라이프사이클

explorer/solver 두 스킬이 공유하는 이슈 상태 모델. GitHub native `state`(open/closed)와 라벨로 표현한다.

## 상태 다이어그램

```
[없음]
   │
   │ explorer 발견
   │ create + bug + severity:{critical|major|minor|ux}
   ▼
OPEN (bug)                                  ← 작업 대기
   │
   │ solver 수정 완료
   │ +label "resolved" + 코멘트 (close 하지 않음)
   ▼
OPEN + resolved                             ← 검증 대기 (Jira의 RESOLVED 대응)
   │
   ├─ explorer 통과
   │   gh issue close --reason completed
   │   −resolved (선택: +verified)
   │   ▼
   │  CLOSED (completed)                    ← 정상 최종
   │
   └─ explorer 회귀
       −resolved +regression
       ▼
       OPEN + regression                    ← 다시 작업 대기 (reopen 불필요)
```

## 라벨

| 라벨 | 부착 시점 | 부착 주체 |
|------|----------|----------|
| `bug` | 이슈 생성 | explorer |
| `severity:critical|major|minor|ux` | 이슈 생성 | explorer |
| `resolved` | 솔버 수정 후 | solver |
| `regression` | 크로스체크 회귀 시 | explorer |
| `verified` (선택) | 크로스체크 통과 시 | explorer |
| `needs-info` | 재현 정보 부족 시 | solver/사용자 |
| `on-hold` / `deferred` | 보류 결정 | 사용자 |
| `duplicate` | 중복 닫음 | 사용자 |
| `wontfix` / `by-design` | 수정 안 함으로 닫음 | 사용자 |
| `pilot:processing` | pilot 자율 사이클 처리 중 | pilot (작업 종료 시 자동 제거) |
| `pilot:escalated` | pilot 사이클이 사람 결정으로 넘김 | pilot (사람이 라벨 제거 후에야 재진입) |
| `security` (선택) | 보안 이슈 식별 | explorer/사용자 |

## 검색 패턴

| 목적 | 명령 |
|------|------|
| solver가 처리할 신규 이슈 | `gh issue list --state open --label "severity:critical"` (severity 우선순위 순) |
| explorer가 검증할 이슈 | `gh issue list --state open --label "resolved"` |
| 회귀 이슈 (재처리 대상) | `gh issue list --state open --label "regression"` |
| 정보 부족으로 멈춘 이슈 | `gh issue list --state open --label "needs-info"` |

## 회귀 사이클

회귀 이슈는 `OPEN + regression`. solver가 재처리할 때:
1. `regression` 라벨 **유지** (회귀 흔적 보존)
2. 수정 완료 후 `+resolved` 추가 (regression은 그대로)
3. 크로스체크 통과 시 `-resolved` + close. **`regression` 라벨은 유지** — closed 이슈에도 남아 "이 이슈는 회귀를 겪었음" 흔적이 됨

흔적 보존의 가치: 회귀가 잦은 컴포넌트·영역을 사후 통계로 식별 가능 (`gh issue list --state closed --label regression`).

## 마이그레이션 (legacy 호환)

이전 모델로 처리된 이슈는 그대로 둔다:
- `CLOSED + crosscheck-pending` (legacy 검증 대기) — explorer가 처리할 때 신규 모델로 정리: `−crosscheck-pending +resolved` 후 reopen하지 말고 그대로 close 유지하며 검증 → 통과 시 `−resolved +verified`
- `CLOSED + crosscheck-passed` (legacy 정상 최종) — 그대로 둠
- 신규 이슈부터는 위 다이어그램만 따른다

explorer는 검증 대상을 잡을 때 마이그레이션 기간 동안 두 형식 모두 검색:
```bash
gh issue list --state all \
  --search "(label:resolved state:open) OR (label:crosscheck-pending state:closed)"
```

**호환 코드 제거 시점**: 다음 명령으로 legacy 잔존 0건 확인 후 explorer Step C1/C4의 legacy 호환 주석/명령 제거.
```bash
gh issue list --state all --label "crosscheck-pending" --json number | jq length
gh issue list --state all --label "crosscheck-passed" --json number | jq length
# 둘 다 0이면 legacy 호환 코드 제거 가능
```

## 종료 상태 분류

| close reason | 라벨 | 의미 |
|--------------|------|------|
| completed | (없음) 또는 `verified` | 정상 처리·검증 완료 |
| not_planned | `duplicate` | 다른 이슈와 중복 |
| not_planned | `wontfix` | 재현되지만 수정 안 함 |
| not_planned | `by-design` | 의도된 동작 |
| not_planned | `not-reproducible` | 재현 불가, 추가 정보 없음 |
| not_planned | `invalid` | 이슈 양식·내용 자체가 잘못됨 |

이 중 1번(completed)만 explorer/solver가 자율적으로 만들고, 나머지(not_planned 트랙)는 사용자 결정으로 닫는다.
