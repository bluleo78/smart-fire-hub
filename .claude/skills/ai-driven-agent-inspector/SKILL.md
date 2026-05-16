---
name: ai-driven-agent-inspector
description: >
  firehub-ai-agent에 정의된 subagent들(dataset-manager, pipeline-builder,
  data-analyst, dashboard-builder, report-writer 등)의 응답 품질·도구 호출 정확성·성능·UX
  결함을 탐색적으로 점검하는 스킬. firehub-ai-agent의 `POST /agent/chat` SSE API를 직접 호출해
  subagent 라우팅·tool_use·tool_result·토큰/지연 trace를 분석하고 결함을 GitHub Issues에
  자동 등록한다. 사용자가 "subagent 점검해줘", "ai 에이전트 품질 검증", "subagent 결함 찾아줘",
  "dataset-manager 검증", "MCP 도구 호출 점검", "에이전트 환각 찾아줘", "subagent inspect",
  "ai-agent 회귀 점검" 등을 요청할 때 반드시 이 스킬을 사용한다.
  관점(perspective)별 패스 지원: 정확성/환각 → accuracy(기본), 도구 호출 → tool,
  성능/토큰 → perf, 표현 품질 → ux.
  또한 ai-driven-solver가 resolved 처리한 subagent 관련 이슈 재검증("subagent 크로스체크",
  "에이전트 fix 확인")에도 이 스킬을 사용한다. UI 기반 탐색은 ai-driven-explorer가 담당하므로
  웹 UI 결함은 그쪽으로 보낸다.
---

# AI Subagent 탐색적 품질 점검

이 스킬은 firehub-ai-agent에 선언된 subagent들의 **품질 결함**을 탐색적으로 발견·등록한다.
ai-driven-explorer가 웹 UI에서 사용자 관점의 결함을 찾는다면, 이 스킬은 **agent 백엔드의
응답 품질·도구 호출 정확성**을 본다.

두 가지 모드로 동작한다:

- **점검 모드** (기본): /chat SSE를 직접 호출하며 시나리오별 trace를 검증하고 결함을 등록한다.
- **크로스 체크 모드**: solver가 resolved 처리한 subagent 관련 이슈를 fresh API 세션에서 재검증한다.

> **[필수 원칙] Inspector는 발견과 등록만 한다.**
> 결함 발견 → `gh issue create` 등록 → 점검 계속 → 보고서 작성 → 종료.
> 소스코드를 수정하지 않는다. 수정은 ai-driven-solver가 별도 사이클에서 처리한다.

## 1. 대상 파악 — Subagent 인벤토리

대상 subagent는 `apps/firehub-ai-agent/src/agent/subagents/*` 디렉토리에 선언되어 있다.
각 subagent는 다음 3개 파일을 가진다:

- `agent.md` — 역할·tools·rules·workflow (시나리오 기대치의 원천)
- `examples.md` — 정상/실패 예시 (시나리오 시드)
- `rules.md` — 도메인 규칙 (검증 기준)

### 0단계: Perspective 결정

| 사용자 발화 키워드 | perspective | 매트릭스 파일 | 시나리오 가이드 |
|------|----|----|----|
| 없음 / "정확성" / "환각" / "결함 찾아줘" | `accuracy` (default) | `.coverage-matrix-accuracy.md` | `references/perspectives/accuracy.md` |
| "도구", "tool", "MCP 호출" | `tool` | `.coverage-matrix-tool.md` | `references/perspectives/tool.md` |
| "성능", "지연", "토큰", "perf" | `perf` | `.coverage-matrix-perf.md` | `references/perspectives/perf.md` |
| "표현", "한국어", "UX", "ux" | `ux` | `.coverage-matrix-ux.md` | `references/perspectives/ux.md` |

진입 시 perspective 결정 → 해당 가이드 파일을 먼저 읽고 → 해당 매트릭스만 로드.

### 세션 시작 결정 흐름

```
세션 시작
├── perspective 결정 (위 표)
│
├── test-results/subagent-eval/.subagent-tree.md 있음?
│   ├── YES → 로드. 대상 subagent 섹션이 트리에 있나?
│   │         ├── YES (상세 내용 있음) → 재사용
│   │         └── NO (⬜ 미점검 또는 섹션 없음) → agent.md/examples.md/rules.md 읽고
│   │                                            해당 섹션 상세 작성
│   └── NO  → 11개 subagent 뼈대 신규 작성 (⬜ 미점검)
│             그 후 대상 subagent 섹션만 상세 작성
│
├── .coverage-matrix-<perspective>.md 있음?
│   ├── YES → 로드. ⬜(미시작) 항목만 이번 세션 대상
│   └── NO  → 트리 + perspective 가이드 시나리오 템플릿으로 신규 생성
│
└── 점검 시작 → ⬜ 항목 순서대로 진행
```

### Step 1: Subagent 트리 구성

`test-results/subagent-eval/.subagent-tree.md`에 저장.

뼈대 예시:
```
## admin-manager ⬜ 미점검
## api-connection-manager ⬜ 미점검
## audit-analyst ⬜ 미점검
## dashboard-builder ⬜ 미점검
## data-analyst ⬜ 미점검
## dataset-manager ⬜ 미점검
## pipeline-builder ⬜ 미점검
## report-writer ⬜ 미점검
## smart-job-manager ⬜ 미점검
## template-builder ⬜ 미점검
## trigger-manager ⬜ 미점검
```

상세 작성 시(예: dataset-manager):
```
### dataset-manager
- **역할**: 데이터셋 생성·수정·삭제·컬럼 변경·CSV/XLSX 임포트
- **선언 도구**: mcp__firehub__list_datasets, get_dataset, create_dataset,
  update_dataset, delete_dataset, add_dataset_column, drop_dataset_column,
  get_dataset_references, preview_csv, validate_import, start_import, import_status
- **위임 규칙**:
  - 분석/쿼리 → data-analyst
  - 파이프라인 → pipeline-builder
  - 단순 목록/스키마 조회 → 메인 에이전트
- **파괴 작업 confirm 필수**: 삭제, 컬럼 삭제, REPLACE 임포트
- **공간 데이터 자동 감지**: GEOMETRY + SRID 4326 제안
```

### Step 2: 커버리지 매트릭스

`.coverage-matrix-<perspective>.md`에 저장. **시나리오 레벨**로 작성하며, subagent당 **최소 12개**.

❌ 잘못된 예 (능력 레벨):
```
| dataset-manager > 생성 가능 여부 | ⬜ |
```

✅ 올바른 예 (accuracy perspective, dataset-manager):
```
| dataset-manager > 존재하지 않는 데이터셋 조회 요청     | hallucination 없이 not found 응답 | ⬜ |
| dataset-manager > "공간 데이터인 화재 발생 위치"      | GEOMETRY+SRID 4326 자동 제안       | ⬜ |
| dataset-manager > 삭제 요청 (확인 없이)              | confirm 요청 → 사용자 평문 응답 대기 | ⬜ |
| dataset-manager > "데이터 분석해줘" 요청              | data-analyst 위임 (직접 처리 X)    | ⬜ |
```

상태: ⬜ 미시작 → 🔄 진행 중 → ✅ 완료 → 🔴 결함 발견

> 시나리오 템플릿·질문 리스트는 `references/perspectives/<perspective>.md`에서 가져온다.

## 2. /chat API 호출 패턴

UI 없이 SSE 엔드포인트를 직접 호출한다.

### 환경 준비

```bash
# firehub-ai-agent 가동 확인 (port 3001)
curl -sf http://localhost:3001/agent/health > /dev/null && echo OK || echo NOPE

# 내부 인증 토큰 (firehub-ai-agent/.env.local의 INTERNAL_SERVICE_TOKEN)
TOKEN=$(grep INTERNAL_SERVICE_TOKEN apps/firehub-ai-agent/.env.local | cut -d= -f2)

# 점검 세션 디렉토리 (timestamp 중심)
TS=$(date +%Y-%m-%dT%H-%M)
SESSION_DIR="test-results/subagent-eval/$TS"
mkdir -p "$SESSION_DIR/traces"
```

### 시나리오 호출 (SSE → JSONL 트레이스 저장)

```bash
# 시나리오 ID, 대상 subagent, 프롬프트를 인자로
SCENARIO_ID="dataset-001"
PROMPT="공간 데이터인 화재 발생 위치 데이터셋 만들어줘. 컬럼은 시간, 위치, 사망자수."

curl -sN -X POST http://localhost:3001/agent/chat \
  -H "Authorization: Internal $TOKEN" \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg msg "$PROMPT" '{
    message: $msg,
    userId: 1,
    agentType: "cli",
    maxTurns: 8
  }')" > "$SESSION_DIR/traces/$SCENARIO_ID.sse" &
SSE_PID=$!

# 60초 타임아웃 (대화는 클라이언트 끊김과 무관하게 계속 진행됨)
sleep 60
kill $SSE_PID 2>/dev/null
```

### SSE 트레이스 파싱

SSE 이벤트 타입: `init`, `text`, `tool_use`, `tool_result`, `turn`, `done`, `error`.
done 이벤트에 토큰 사용량과 결과가 포함된다.

> `agentType`은 **`"cli"`를 권장**한다. `"sdk"`는 별도 `apiKey`를 본문에 넘겨야 동작하고,
> 미지정 시 `event: error`로 끊긴다. `cli`는 로컬 Claude Code CLI 인증을 재사용한다.

```bash
# 도구 호출 목록만 추출 (toolName 필드 우선 — SDK는 toolName, 일부는 name)
grep "^event: tool_use$" -A1 "$SESSION_DIR/traces/$SCENARIO_ID.sse" \
  | grep "^data:" | sed 's/^data: //' | jq -r '.toolName // .name'

# tool 인자도 함께 보기 (rules.md 위반 자동 감지에 유용)
grep "^event: tool_use$" -A1 "$SESSION_DIR/traces/$SCENARIO_ID.sse" \
  | grep "^data:" | sed 's/^data: //' \
  | jq -c '{tool: (.toolName // .name), input: .input}'

# 최종 텍스트 응답
grep "^event: text$" -A1 "$SESSION_DIR/traces/$SCENARIO_ID.sse" \
  | grep "^data:" | sed 's/^data: //' | jq -r '.delta // .text // empty'

# 토큰/지연
grep "^event: done$" -A1 "$SESSION_DIR/traces/$SCENARIO_ID.sse" \
  | grep "^data:" | sed 's/^data: //' | jq '{tokens: .usage, duration_ms}'

# 에러 이벤트
grep "^event: error$" -A1 "$SESSION_DIR/traces/$SCENARIO_ID.sse"
```

### 세션 이력 조회 (멀티턴 시)

```bash
# done 이벤트의 sessionId를 추출하여 후속 턴에서 재사용
SESSION_ID=$(grep "^event: init$" -A1 "$SESSION_DIR/traces/$SCENARIO_ID.sse" \
  | grep "^data:" | sed 's/^data: //' | jq -r '.sessionId')

curl -s "http://localhost:3001/agent/history/$SESSION_ID" \
  -H "Authorization: Internal $TOKEN" | jq .
```

## 3. 검증 항목 (perspective별)

같은 시나리오라도 perspective가 다르면 보는 곳이 다르다. 자세한 체크리스트는
`references/perspectives/<perspective>.md` 참조.

### accuracy (기본 — 정확성/환각)

- 응답이 사실인가? (존재하지 않는 데이터셋·컬럼·기능 언급 금지)
- 위임 규칙을 지키는가? (담당 외 작업 → 적절한 subagent로 위임)
- 파괴 작업 confirm을 요구하는가?
- 도메인 규칙을 따르는가? (rules.md 명시 사항)

### tool (도구 호출)

- 선언된 tools 외 호출 없음
- 필수 도구가 호출됨 (예: create 직전 list/preview 검증)
- tool 인자 schema 위반 없음 (Zod validation 통과)
- tool_result 에러를 응답에 반영함 (무시·환각 금지)
- 순서 위반 없음 (예: confirm 전에 delete 호출)

### perf (성능)

- 응답 지연 (P50/P95 임계치)
- 토큰 사용량 (input/output/cache)
- maxTurns 도달 빈도
- 동일 도구 반복 호출 (불필요한 polling)

### ux (표현)

- 한국어 품질 (어색한 번역체, 누락된 단위·조사)
- 진행 상황 명시 (긴 작업 시)
- 결과 요약 일관성 (rules에 명시된 포맷)
- 다음 단계 제안 유무

## 4. 결함 판정 기준

- **Critical**: 환각으로 잘못된 도구 호출 → 데이터 변경/삭제, 권한 무시, 보안 우회
- **Major**: 위임 규칙 위반, confirm 누락 (파괴 작업), 필수 도구 미호출, 핵심 도메인 규칙 위반
- **Minor**: 불필요한 도구 반복, maxTurns 도달, 비효율적 흐름
- **UX**: 한국어 표현, 누락된 진행 표시, 포맷 불일치

## 5. 결함 문서화

발견된 결함은 GitHub Issues에 즉시 등록 후 보드에도 추가한다.

#### 등록 전 전제 가정 검증 (필수)

자신의 진단이 도메인/스펙 가정에 의존하는지 점검한다.
- agent.md/rules.md에 명시된 규칙을 어긴 경우 → 결함 (등록)
- 명시 규칙이 없고 inspector의 추정에 의존 → `needs-decision` 라벨 부착, `ai-fix`는 미부착

**Perspective별 라벨 매핑**:

| Perspective | 라벨 |
|---|---|
| `accuracy` | `bug,subagent-quality,severity:critical|major|minor,accuracy` |
| `tool` | `bug,subagent-quality,severity:critical|major,tool` |
| `perf` | `bug,subagent-quality,severity:major|minor,perf` |
| `ux` | `bug,subagent-quality,severity:ux,ux` |

> **`ai-fix` 라벨 정책** — pilot 자율 사이클이 solver로 픽업하려면 반드시 부착한다.
> 단, 등록 전 전제 가정 검증(아래)에서 `needs-decision`이 부착된 케이스는 `ai-fix`를 빼고
> 사람 검토 후 재부착하도록 한다.

> **이슈 자기소유 라벨**: `subagent-quality`가 결함 도메인 라우팅 키. pilot이 이 라벨로
> inspector vs explorer 크로스체크를 분기하고, solver는 본문의 `## Trace 근거`로
> 프롬프트 파일을 식별한다.

```bash
ISSUE_URL=$(gh issue create \
  --title "<subagent명> — 한 줄 요약" \
  --label "bug,subagent-quality,ai-fix,severity:major,accuracy" \
  --body "$(cat <<EOF
## 대상
- **Subagent**: dataset-manager
- **Perspective**: accuracy
- **시나리오 ID**: dataset-001

## 현상
한 문장 요약.

## 재현
1. POST /agent/chat 호출 (Authorization: Internal …)
2. 프롬프트: "공간 데이터인 화재 발생 위치 데이터셋 만들어줘. 컬럼은 시간, 위치, 사망자수."
3. 관찰: 응답이 GEOMETRY 컬럼 제안 없이 일반 TEXT로 처리 (agent.md 공간 데이터 자동 감지 규칙 위반)

## Trace 근거
\`\`\`
$SESSION_DIR/traces/dataset-001.sse
\`\`\`
- tool_use 시퀀스: list_datasets → create_dataset(columns=[{name:"위치",type:"TEXT"}])
- 기대: type=GEOMETRY, srid=4326

## 원인
- 파일: \`apps/firehub-ai-agent/src/agent/subagents/dataset-manager/rules.md:NN\`
- 분석: 공간 키워드 감지 트리거가 명시되어 있으나 시스템 프롬프트에서 누락 가능

## 수정 방향
구체적인 prompt/rule 수정 방향.

## 메타
- **발견**: $(date +%Y-%m-%d) (ai-driven-agent-inspector)
- **firehub-ai-agent**: \`apps/firehub-ai-agent/src/agent/subagents/dataset-manager/\`
EOF
)")

ISSUE_NUM=$(echo "$ISSUE_URL" | grep -oE '[0-9]+$')
bash .claude/skills/ai-driven-pilot/scripts/add-to-board.sh "$ISSUE_NUM"
```

> `subagent-quality` 라벨이 핵심 — pilot 라우팅 시 ai-agent-developer가 픽업하도록 분기.
> solver Step 0의 자동 차단 대상은 아니지만, 수정 시 반드시 trace 재현 + 회귀 테스트 추가 권고.

매트릭스의 🔴 항목에는 이슈 번호를 기록한다.

## 6. 점검 패턴

### Subagent 집중 원칙

**한 subagent의 12+ 시나리오를 모두 점검한 후 다음 subagent로 이동한다.**
결함을 2~3개 발견했다고 멈추지 말되, 시나리오를 무한히 늘리지 않는다.
목적은 *광범위한 발견* — 매트릭스 항목 완주가 우선.

### 시나리오 작성 원칙

1. **정상 경로** — 가장 흔한 사용 흐름
2. **엣지 케이스** — 빈 값, 길이 초과, 특수문자
3. **위임 경계** — 다른 subagent 담당 요청 시 위임하는가
4. **파괴 작업** — 삭제·교체 시 confirm 요구하는가
5. **도메인 규칙** — rules.md의 자동 감지·제안 조항 트리거
6. **에러 처리** — 권한·존재하지 않음·서버 오류 시 응답
7. **모호 입력** — 의도 불명확한 짧은 발화에 되묻는가
8. **다단계 흐름** — 멀티턴 상태 유지
9. **도구 인자 검증** — 잘못된 인자 시도 시 retry/abort

각 시나리오에 대해: 호출 → SSE 트레이스 → 검증 → 매트릭스 업데이트 → (결함이면) 이슈 등록.

## 7. 최종 보고서

`test-results/subagent-eval/<YYYY-MM-DDTHH-MM>/report.md`에 작성.

```
test-results/
└── subagent-eval/                   ← 본 스킬 결과
    ├── .subagent-tree.md            ← subagent 인벤토리 (perspective 무관, 공유)
    ├── .coverage-matrix-accuracy.md
    ├── .coverage-matrix-tool.md
    ├── .coverage-matrix-perf.md
    ├── .coverage-matrix-ux.md
    └── <YYYY-MM-DDTHH-MM>/
        ├── report.md
        └── traces/                  ← SSE 원본
            ├── dataset-001.sse
            └── ...
```

보고서 양식:
```
# Subagent Inspection Report — YYYY-MM-DD

## Perspective: accuracy

| Subagent          | 시나리오 | 결함 | 이슈                  |
|-------------------|---------|------|----------------------|
| dataset-manager   | 14      | 2    | #N1, #N2             |
| pipeline-builder  | 12      | 0    | -                    |

## 결함 요약
- #N1 (Major) — dataset-manager: 공간 데이터 자동 감지 실패
- ...

## 다음 패스 권고
- tool perspective 미실행
```

## 크로스 체크 모드 — 이슈 수정 검증

solver가 ✅ 수정 완료(resolved 라벨)로 표시한 subagent 관련 이슈를 fresh API 세션에서 재검증.

### Step C1. 검증 대상 선택

```bash
gh issue list --label "resolved,subagent-quality" --state open --json number,title,body,labels

# 사용자가 번호를 지정한 경우
gh issue view <번호> --json number,title,body,labels,state
```

이슈의 `## 재현` 섹션을 그대로 curl로 다시 실행한다.

### Step C2. Fresh 호출

```bash
TS=$(date +%Y-%m-%dT%H-%M)
TRACE="test-results/subagent-eval/$TS/traces/crosscheck-<번호>.sse"
mkdir -p "$(dirname "$TRACE")"
# 이슈 본문의 프롬프트로 호출
```

### Step C3. 결과 판정

1. **결함 사라짐** → ✅ 수정 확인
2. **여전히 재현** → 🔴 회귀 (regression 라벨)
3. **다른 결함 발생** → 새 이슈 등록

### Step C4. GitHub 업데이트

```bash
# 패스
gh issue edit <번호> --remove-label "resolved"
gh issue close <번호> --reason completed \
  --comment "✅ 크로스체크 완료 ($(date +%Y-%m-%d)) — trace 재현 안 됨, 수정 확인"

# 회귀
gh issue edit <번호> --remove-label "resolved" --add-label "regression"
gh issue comment <번호> --body "🔴 회귀 발견 ($(date +%Y-%m-%d))

**관찰 trace**: $TRACE
**기대 동작과의 차이**: …"
```

### Pilot subagent 모드 — 정형 보고

pilot이 자율 사이클로 호출한 경우 stdout 마지막 줄에:

| 결과 | RESULT 라인 |
|------|-----------|
| 크로스체크 통과 | `RESULT: #<N> / passed / closed` |
| 회귀 | `RESULT: #<N> / regression / <K>` |
| 진행 불가 (agent 서버 다운 등) | `RESULT: #<N> / blocked / <사유>` |
| 점검 모드 발견 보고 | `INSPECTOR_DONE: <N>,<M>,...` 또는 `INSPECTOR_DONE: none` |

---

## 주의사항

- `gh issue` 명령과 `test-results/`만 변경 (소스 수정 금지)
- SSE 트레이스 원본은 `test-results/subagent-eval/<TS>/traces/`에 저장
- firehub-ai-agent가 다운이면 즉시 `blocked` 종료
- internal token이 없으면 `apps/firehub-ai-agent/.env.local`을 사람에게 요청 (자동 생성 금지)
- 트레이스의 sessionId·userId·token은 보고서·이슈 본문에 포함하지 않는다 (PII/secret 보호)
- subagent의 도메인 규칙은 agent.md/rules.md가 single source of truth — 추정 금지
- UI에서만 재현 가능한 결함은 ai-driven-explorer 영역 → 이쪽에서 등록 금지
