# 이슈 라이프사이클 (WD 트래커)

explorer/solver/pilot/agent-inspector 네 스킬이 공유하는 이슈 상태 모델. **2026-10-15부터 GitHub Issues 대신 WD
(Gen:iA Data, smart-workplace 프로젝트) 이슈 트래커를 쓴다.** 호출 방법은 [[wd-mcp-howto]](`.claude/docs/wd-mcp-howto.md`)
참고 — 이 문서는 **상태 모델**만 다룬다.

> WD에는 라벨이 없다(API로 즉석 생성 불가, 사전 등록된 라벨 0개). 과거 GitHub 모델의 라벨 게이트는 전부
> **본문(body) 맨 앞 `## 자동화 상태` 메타 블록의 필드**로 대체했다. 실제 WD `status` 필드(TODO/IN_PROGRESS/DONE/
> CANCELED)는 큰 상태 전환에만 병행해서 맞춘다.

## 메타 블록

```
## 자동화 상태
- 유형: bug
- 성격: ui | subagent-quality:accuracy | subagent-quality:tool | subagent-quality:perf | subagent-quality:ux
- 심각도: critical | major | minor | ux
- 플래그: security, a11y, design, needs-decision, needs-info, on-hold, deferred  (해당 없으면 "없음")
- 처리: ai-fix | needs-human
- 단계: open | processing | resolved | regression:N | closed:completed | closed:duplicate | closed:wontfix | closed:by-design | closed:not-reproducible | closed:invalid

---

(사람이 읽는 원래 설명)
```

## 상태 다이어그램

```
[없음]
   │
   │ explorer 발견
   │ create_issue (메타: 성격=ui 또는 subagent-quality:*, 심각도=critical|major|minor|ux, 처리=ai-fix, 단계=open)
   ▼
단계=open (status=TODO)                     ← 작업 대기
   │
   │ solver 수정 완료
   │ 단계 → resolved (status=IN_PROGRESS), 코멘트 추가 (close 하지 않음)
   ▼
단계=resolved (status=IN_PROGRESS)          ← 검증 대기 (Jira의 RESOLVED 대응)
   │
   ├─ explorer 통과
   │   단계 → closed:completed, status=DONE
   │   ▼
   │  단계=closed:completed (status=DONE)   ← 정상 최종
   │
   └─ explorer 회귀
       단계 → regression:N (N = 누적 회귀 횟수), status=IN_PROGRESS 유지
       ▼
       단계=regression:N                    ← 다시 작업 대기 (close된 적 없으니 reopen 불필요)
```

## 메타 블록 필드

| 필드 | 값 | 기록 시점 | 기록 주체 |
|------|----|----------|----------|
| 유형 | `bug` (고정) | 이슈 생성 | explorer/inspector |
| 성격 | `ui` 또는 `subagent-quality:{accuracy\|tool\|perf\|ux}` | 이슈 생성 | explorer(ui) / inspector(subagent-quality) |
| 심각도 | `critical`\|`major`\|`minor`\|`ux` | 이슈 생성 | explorer/inspector |
| 처리 | `ai-fix`(자율 처리 옵트인) \| `needs-human` | 이슈 생성(기본 ai-fix) / pilot이 사람 개입 필요 판단 시 needs-human으로 전환 | 사용자/explorer 생성, pilot 전환 |
| 단계 | 위 다이어그램 값들 | 각 전이 시점 | pilot/solver/explorer |
| 플래그(보조) | `security`, `a11y`, `design`, `needs-decision`, `needs-info`, `on-hold`, `deferred` (쉼표로 복수 가능) | 필요 시 | solver/사용자 |

> pilot이 사람 개입 필요로 판단하면 메타의 "처리"를 `needs-human`으로 바꾸고 `status=TODO`로 되돌린다(= 과거 GitHub
> 모델의 "ai-fix 라벨 제거 + 보드 backlog 이동"과 동일한 의미). 별도 "에스컬레이션" 마커는 두지 않는다 — 상태는 메타
> 블록 하나로만 표현한다.

## 회귀 카운트 (코멘트 기반 — 그대로 유지)

라벨이 없어도 코멘트는 그대로 쓸 수 있다. 회귀 발생 시 explorer가 이슈에 코멘트로 "🔴 회귀 발견 (N회차)"를 남기고,
pilot은 `get_issue_detail(allComments:true)`로 댓글 전체를 받아 그 문구 개수를 센다(과거 GitHub 버전과 동일한 방식,
변경 없음). 메타 블록의 "단계: regression:N"의 N도 이 카운트와 맞춘다.

## 검색 패턴 (WD)

| 목적 | 호출 |
|------|------|
| solver가 처리할 신규 이슈 | `list_issues(projectKey="WD", status="TODO", q="처리: ai-fix")` 후 본문에서 "단계: open" 재확인(q 정확도 낮을 수 있음, [[wd-mcp-howto]] 참고) |
| explorer가 검증할 이슈 | `list_issues(projectKey="WD", status="IN_PROGRESS", q="단계: resolved")` |
| 회귀 이슈 (재처리 대상) | `list_issues(projectKey="WD", status="IN_PROGRESS", q="regression:")` |
| 정보 부족으로 멈춘 이슈 | `list_issues(projectKey="WD", q="needs-info")` |

`q` 매칭 정확도가 떨어지면(동음이의 매치 등) 후보를 모두 `get_issue_detail`로 열어 메타 블록을 직접 정규식으로
재확인한다 — **q는 1차 필터일 뿐, 최종 판단은 항상 메타 블록 직접 파싱**.

## 종료 상태 분류

| 단계 값 | WD status | 의미 |
|--------------|------|------|
| `closed:completed` | DONE | 정상 처리·검증 완료 |
| `closed:duplicate` | CANCELED | 다른 이슈와 중복 |
| `closed:wontfix` | CANCELED | 재현되지만 수정 안 함 |
| `closed:by-design` | CANCELED | 의도된 동작 |
| `closed:not-reproducible` | CANCELED | 재현 불가, 추가 정보 없음 |
| `closed:invalid` | CANCELED | 이슈 양식·내용 자체가 잘못됨 |

이 중 `closed:completed`만 explorer/solver가 자율적으로 만든다. 나머지는 사용자 결정으로 닫는다(pilot/solver가
`needs-human`으로 전환 후 사용자가 직접 메타 "단계"를 바꾸고 status를 CANCELED로 맞춘다).

## 레거시(GitHub Issues) 안내

2026-10-15 이전에 GitHub Issues(`bluleo78/smart-fire-hub`)에 등록된 이슈는 그 자리에 남아 있다. 새 이슈는 전부 WD에
등록한다. 과거 GitHub 이슈 중 사람 개입 대기 중이던 것을 계속 다루려면, 그 내용을 WD로 옮겨 적고(본문에
"출처: smart-fire-hub GitHub #N" 한 줄 포함) GitHub 쪽은 `gh issue close`로 정리한다(2026-10-13 WD-50/WD-51 선례 참고).
