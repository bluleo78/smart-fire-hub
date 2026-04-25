---
name: ai-driven-solver
description: GitHub Issues에 등록된 **단일 이슈 한 건**을 분석하고 코드 수정 → 테스트 → resolved 라벨 부착 → 커밋까지 one-shot으로 처리한다 (실제 close는 explorer 크로스체크 통과 후 발생). "55번 이슈 처리해줘", "#54 해결해줘", "Major 이슈 수정해줘", "첫 번째 미처리 이슈 고쳐줘", "회귀 이슈 #21 다시 고쳐줘" 같이 **단일 이슈를 지목하거나 한 건 처리** 의도가 보이는 요청에 반드시 이 스킬을 사용한다. "이슈 다 처리해줘", "자율 사이클" 같이 **여러 이슈 자율 처리**를 원하면 ai-driven-pilot으로 라우팅. 모호한 경우 사용자에게 단건/다건 확인.
---

# AI-Driven Solver

GitHub Issues의 버그 이슈를 one-shot으로 처리한다.

> 이슈 라이프사이클 전체 다이어그램·라벨 정의는 `.claude/docs/issue-lifecycle.md` 참조.

---

## Step 0. Perspective 호환성 사전 확인

이 스킬은 **bug perspective 본문 형식**(`## 메타 / ## 현상 / ## 재현 / ## 원인 / ## 수정 방향`)을 가정한다. 이슈에 다음 라벨이 붙어 있으면 작업 진입 전에 차단한다 (자율 처리 부적합):

| 라벨 | 이유 |
|------|------|
| `design` | 본문에 `## 재현` 대신 `## 영향`/`## 비교`. 디자인 토큰 의사결정·시각 캡처가 사람 영역 |
| `a11y` | WCAG SC 의미 판단·SR 청취·키보드 흐름 검증이 사람 영역. 코드 한 줄 수정으로 안 끝남 |
| `perf` | 측정값(LCP/INP/CLS)·DevTools 캡처·heap snapshot 비교가 사람 영역 |
| `security` | 보안 결정은 자동화 부적합 (별도 에스컬레이션 트리거) |

```bash
# 라벨 조회
LABELS=$(gh issue view <번호> --json labels -q '.labels[].name' | tr '\n' ' ')
INCOMPAT_FOUND=""
for L in design a11y perf security; do
  echo "$LABELS" | grep -q "$L" && INCOMPAT_FOUND="$L" && break
done
```

`$INCOMPAT_FOUND`가 비어있지 않으면 호출 모드별로 다음과 같이 처리:

### Pilot subagent 모드 (subagent prompt에 "ai-driven-pilot이 자율 사이클로 호출함" 포함)
이슈에 코멘트 남기지 않고(이미 라벨로 분류됨) **stdout 마지막 줄에 `RESULT: #<N> / blocked / non_bug_perspective` 출력 후 작업 종료**. pilot이 받아서 다음 루프 계속(일시정지 X).

### 사용자 직접 호출 모드
사용자에게 보고 후 진행 여부 확인:
```
이슈 #<번호>는 `<INCOMPAT_FOUND>` 라벨 — bug perspective 본문 형식이 아닙니다.
- design/a11y/perf 이슈는 디자인 토큰 의사결정·시각 캡처·SR 청취·측정값 캡처가 필요해
  자율 코드 수정 부적합합니다.
- security 이슈는 사람 결정 후 close 정책입니다.

진행 옵션:
  (1) 그래도 진행 (사용자가 직접 안내하며 함께 작업)
  (2) needs-info 라벨 부착 후 종료 (사람 검토 큐에 남김)
  (3) 작업 취소
어떻게 할까요?
```
사용자가 (1)을 선택하면 Step 1로 진행하되, 본문 형식이 다르니 사용자와 대화하며 한 단계씩 확인. (2)이면 `gh issue edit <N> --add-label needs-info` 후 종료. (3)이면 그대로 종료.

---

## Step 1. 타겟 이슈 선택

**사용자가 번호를 지정한 경우**: 해당 번호의 이슈를 선택한다.

**지정하지 않은 경우**: GitHub Issues에서 open 상태의 버그 이슈 중 심각도 순(Critical > Major > Minor > UX)으로 첫 번째를 선택한다. 이때 `resolved` 라벨이 붙은 이슈(검증 대기 중)는 제외하고, `regression` 라벨이 붙은 이슈는 회귀이므로 우선순위가 동급이면 더 먼저 처리한다.

```bash
# 심각도 순 미처리 이슈 조회 (resolved 라벨은 제외)
gh issue list --label "severity:critical" --state open --json number,title,labels | jq '[.[] | select(.labels | map(.name) | contains(["resolved"]) | not)]' | head -10
gh issue list --label "severity:major"    --state open --json number,title,labels | jq '[.[] | select(.labels | map(.name) | contains(["resolved"]) | not)]' | head -10
gh issue list --label "severity:minor"    --state open --json number,title,labels | jq '[.[] | select(.labels | map(.name) | contains(["resolved"]) | not)]' | head -10
gh issue list --label "severity:ux"       --state open --json number,title,labels | jq '[.[] | select(.labels | map(.name) | contains(["resolved"]) | not)]' | head -10

# 회귀 이슈 (재처리 우선 대상)
gh issue list --label "regression" --state open --json number,title
```

선택 후 사용자에게 확인:
```
타겟: [#55] AddTriggerDialog — 유효하지 않은 Cron 표현식으로 트리거 생성 허용 (Major)
이대로 진행할까요?
```

사용자가 다른 이슈를 원하면 해당 이슈로 교체한다.

---

## Step 2. 이슈 분석

GitHub Issues에서 해당 이슈 본문을 읽고 다음을 파악한다:

```bash
gh issue view <번호> --json number,title,body,labels
```

- **컴포넌트**: `## 메타` 섹션의 파일 경로 및 라인 번호
- **현상**: `## 현상` 섹션
- **재현**: `## 재현` 섹션의 단계
- **원인**: `## 원인` 섹션의 코드 레벨 근본 원인
- **수정 방향**: `## 수정 방향` 섹션의 권고 수정 방법

원인이 프론트엔드/백엔드 모두에 걸쳐 있는지 파악한다.

---

## Step 2.5. 사전 재현 검증 ← 수정 진입 전 필수

**이 단계는 생략 불가다.** 코드를 한 줄도 수정하기 전에, 버그가 실제로 재현되는지 직접 확인한다. 재현이 안 되면 원인 분석이 틀렸을 수 있으므로 Step 2로 돌아간다.

> **환경 헬스체크 (특히 pilot subagent로 호출된 경우 필수)**: pilot의 사이클이 길어지면 도중에 dev 서버가 죽었을 수 있다. 진입 직후 `curl -sf http://localhost:5173 > /dev/null && echo OK || echo NOPE` 한 줄로 가용성 확인. NOPE이면 `RESULT: #N / blocked / dev_server_down` 반환 후 종료 (silent 재현 실패 방지).

### UI/프론트엔드 이슈

`playwright-cli`로 headed 모드 브라우저에서 재현한다 (explorer와 동일 도구 — 검증 일관성 확보, `references/pitfalls.md` 함정 공유).

**세션 이름 규약**:
- 사용자 직접 호출: `SESSION="solver#$(openssl rand -hex 3)"` (random)
- Pilot subagent 호출: pilot이 prompt에 전달한 이름 사용 — `ps<이슈번호>` 형태 (예: 이슈 #45 → `ps45`). 이름을 짧게 유지하는 이유: macOS Unix 소켓 경로 한도(104바이트)를 넘기면 socket bind 실패로 좀비 세션 발생. pilot 사이클 종료 시 `p[sce][0-9a-f]+` 패턴으로 일괄 정리.

```bash
SESSION="${SESSION_NAME:-solver#$(openssl rand -hex 3)}"
playwright-cli -s=$SESSION --headed open http://localhost:5173
playwright-cli -s=$SESSION state-load .playwright-cli/state.json
```

```
1. pnpm dev가 실행 중인지 확인 (localhost:5173)
2. gh issue view <번호>의 `## 재현` 섹션 단계를 그대로 따라간다
3. 버그 현상이 나타나는 순간 스크린샷 저장
   → test-results/issues/<이슈번호>/before.png
```

playwright-cli 명령이 막히면 explorer 스킬의 `references/pitfalls.md` 참고 (오버레이/Radix UI/React 입력 등 14가지 함정 정리됨).

**mock 사용 규칙**: 실제 백엔드 없이 특정 SSE 이벤트 시퀀스를 재현해야 하는 경우(예: 서버가 tool_result 없이 done을 보내는 케이스), `page.route()`로 해당 시퀀스를 모킹할 수 있다. 단, **사용자에게 반드시 다음을 명시**해야 한다:
- mock을 사용하는 이유
- mock이 재현하는 정확한 이벤트 시퀀스
- 왜 이 mock이 실제 버그 조건과 동일한지

### 백엔드 API 이슈

`curl`로 `gh issue view <번호>`의 `## 재현` 섹션 시나리오를 그대로 실행하고, 버그 응답(에러 코드, 잘못된 데이터 등)을 확인한다.

### 재현 성공 시

사용자에게 보고:
```
사전 검증 완료: 버그 재현됨
- 스크린샷: test-results/issues/<번호>/before.png
- 현상: [실제로 본 것]
수정을 시작합니다.
```

### 재현 실패 시

수정을 중단하고 이슈에 `needs-info` 라벨을 부착한 뒤 사용자에게 보고:

```bash
gh issue edit <번호> --add-label "needs-info"
gh issue comment <번호> --body "⚠️ 사전 재현 실패 (YYYY-MM-DD)

**시도한 조건**: [내용]
**관찰 결과**: [내용]
**필요한 정보**: 추가 재현 단계 / 환경 / 입력값"
```

```
사전 검증 실패: 버그가 재현되지 않음 → needs-info 라벨 부착됨
- 시도한 조건: [내용]
- 관찰 결과: [내용]
이슈 분석을 재검토하거나 추가 정보가 필요합니다.
```

---

## Step 3. 계획 수립

`superpowers:writing-plans` 스킬을 호출하여 구현 계획을 작성한다.

계획에 반드시 포함할 항목:
- 수정할 파일 목록 (정확한 경로, 변경 위치)
- 각 파일에서 변경할 내용 (before → after)
- 프론트엔드 변경이 포함된 경우: E2E 테스트 추가 계획 (어떤 케이스를 검증할지)
- 테스트 실행 명령어

---

## Step 4. 구현

`superpowers:subagent-driven-development` 스킬을 호출하여 계획을 실행한다. 실행 방식은 항상 Subagent-Driven으로 진행하며 사용자에게 선택을 묻지 않는다.

**프론트엔드 변경 시 필수 사항**:
- `apps/firehub-web/CLAUDE.md`의 E2E 테스트 작성 원칙에 따라 회귀 테스트를 함께 작성한다
- 테스트는 "요소가 보이는가"가 아닌 입력→처리→출력 파이프라인을 검증해야 한다

**백엔드 변경 시 필수 사항**:
- TC 케이스가 있으면 해당 TC도 업데이트한다

---

## Step 5. 테스트 검증

구현 완료 후 빌드 및 테스트를 실행한다.

```bash
# 프론트엔드 변경 포함 시
cd apps/firehub-web && pnpm build && pnpm test:e2e

# 백엔드 변경 포함 시
cd apps/firehub-api && ./gradlew test -x generateJooq --tests "<관련테스트클래스>"

# 전체 빌드 확인
pnpm build
```

테스트 실패 시 원인을 파악하고 수정 후 재시도한다. 2회 이상 실패하면 사용자에게 상황을 보고하고 방향을 묻는다.

**주의**: 코드 변경이 기존 테스트를 깨뜨릴 수 있다. 직접 관련 TC뿐만 아니라 전체 테스트(`./gradlew test -x generateJooq`)를 돌려 회귀를 확인한다.

---

## Step 6. 수정 후 재현 검증 ← Step 2.5와 같은 조건으로 반드시 실행

**이 단계는 생략 불가다.** E2E 자동 테스트 통과만으로는 충분하지 않다. Step 2.5에서 버그를 재현했던 **동일한 조건**으로 다시 실행하여, 버그가 실제로 사라졌는지 직접 눈으로 확인한다.

### UI/프론트엔드 이슈

Step 2.5와 동일한 방법으로 재현 조건을 실행한다 (mock을 사용했다면 동일한 mock 적용).

```
- 수정 전 발생하던 문제가 더 이상 발생하지 않는지 확인
- 정상 동작 스크린샷 저장: test-results/issues/<이슈번호>/verified.png
```

mock 사용 시: Step 2.5에서 이미 사용자에게 설명했으므로 추가 설명 불필요. 단, before/verified 두 스크린샷을 나란히 제시하여 차이를 명확히 보여준다.

### 백엔드 API 이슈

Step 2.5와 동일한 `curl` 명령으로 재실행하고, 올바른 응답이 오는지 확인한다.

### 검증 실패 시

Step 4로 돌아가 구현을 재검토한다.

### 세션 종료 (이 단계 끝나면 브라우저 사용 종료점)

```bash
playwright-cli -s=$SESSION close
```

세션 leak 방지를 위해 검증 종료 직후 close. 백엔드 API 이슈처럼 브라우저를 안 쓴 경우엔 skip.

---

## Step 7. 이슈 resolved 처리

수정 완료 후 이슈는 **닫지 않는다**. `resolved` 라벨만 추가하고 OPEN 유지 — explorer가 크로스체크 통과시켜야 close 된다.

```bash
gh issue edit <번호> --add-label "resolved"

gh issue comment <번호> --body "✅ 수정 완료 (YYYY-MM-DD)

수정 내용: [변경 파일 요약]
커밋: [커밋 해시]

크로스체크 대기 중 (label: resolved)."
```

회귀(regression) 이슈를 재처리하는 경우 `regression` 라벨은 **제거하지 않는다** (회귀 흔적 보존). `resolved`만 추가해서 `OPEN + regression + resolved` 상태가 되도록 한다.

이슈 라이프사이클 전체 다이어그램은 `.claude/docs/issue-lifecycle.md` 참조.

---

## Step 8. 커밋

변경 내용을 요약하여 커밋한다. 사용자가 자동 커밋을 승인한 경우가 아니라면, 먼저 확인을 요청한다.

**pre-commit 훅 실패 시**: 이 프로젝트의 훅은 `lint-staged → typecheck → e2e → ./gradlew test -x generateJooq` 순으로 실행된다. 커밋이 실패하면 훅 출력을 읽어 어느 단계에서 막혔는지 파악하고, 원인을 수정한 뒤 재커밋한다. 훅 실패는 Step 5의 테스트 실패와 별개로 발생할 수 있으므로(예: Flyway 마이그레이션 오류, JaCoCo 버전 비호환) 독립적으로 진단한다.

> ⚠️ **자동 close 키워드 금지**: 커밋 메시지에 `closes #N`, `fixes #N`, `resolves #N` 같은 GitHub 자동 close 키워드를 **쓰지 않는다**. 이 프로젝트에서 close는 explorer 크로스체크 통과 후에만 발생한다. 이슈 참조는 단순히 `(#55)` 또는 `(refs #55)` 형태로 한다.

```
변경 파일:
- apps/firehub-web/src/pages/pipeline/components/AddTriggerDialog.tsx
- apps/firehub-web/e2e/pages/pipeline-trigger.spec.ts

커밋 메시지:
fix(pipeline): 유효하지 않은 Cron 표현식으로 트리거 생성 허용 (#55)

커밋할까요?
```

---

## Pilot subagent 모드 — RESULT 보고 (호출된 경우만)

`ai-driven-pilot`이 자율 사이클로 solver를 subagent로 호출한 경우, **본문의 모든 사용자 확인 단계를 skip**하고 자체 판단으로 진행한 뒤 결과를 **stdout 마지막 줄**에 정형 RESULT로 출력한다 (pilot이 파싱).

skip 대상 확인 단계:
- **Step 1**: "이대로 진행할까요?" → pilot이 dispatch한 이슈 번호 그대로 진행
- **Step 5**: 테스트 2회 실패 시 "사용자에게 상황 보고" → 보고 대신 `RESULT: #N / test_failed_2 / ...` 반환
- **Step 8**: "커밋할까요?" → 자동 커밋 진행 (pre-commit 훅 실패 시 1회 재시도, 그래도 실패면 `RESULT: #N / hook_failed_2 / ...`)

이유: subagent가 사용자 input을 대기하면 영원히 끝나지 않아 pilot 사이클이 멈춤. 자율 모드에선 솔버가 자체 판단으로 끝까지 진행하거나 정형 RESULT로 결과를 반환해야 한다.

| 결과 | RESULT 라인 | 부수 동작 |
|------|-----------|----------|
| 정상 처리 (resolved 부착 + 커밋) | `RESULT: #<N> / success / <커밋해시> / <변경파일수>` | 평소대로 |
| Step 2.5 사전 재현 실패 | `RESULT: #<N> / reproduce_failed / <사유>` | **`needs-info` 라벨 부착** + 코멘트 |
| Step 5 테스트 2회 실패 | `RESULT: #<N> / test_failed_2 / <마지막 실패 메시지>` | 코멘트 |
| Step 8 pre-commit 훅 2회 실패 | `RESULT: #<N> / hook_failed_2 / <어느 단계>` | 코멘트 |
| Step 0 perspective 호환성 차단 | `RESULT: #<N> / blocked / non_bug_perspective` | 코멘트 (사람 검토 필요) |
| 그 외 진행 불가 | `RESULT: #<N> / blocked / <사유>` | 코멘트 |

`<N>`은 처리한 이슈 번호. pilot이 dispatch한 번호와 일치 검증용 sanity check + 로그 추적용.

호출 모드 식별: subagent prompt에 "ai-driven-pilot이 자율 사이클로 호출함" 같은 문구가 있으면 이 모드로 진입.

---

## 주의사항

- 이슈의 **수정 방향**은 참고 사항이며 코드를 실제 읽어 더 나은 방법이 있으면 적용해도 된다
- 수정 범위는 이슈에 명시된 컴포넌트에 집중하고, 관련 없는 코드는 건드리지 않는다
- E2E 테스트는 기존 `e2e/` 디렉토리 패턴을 따른다 (factories → fixtures → spec)
- 커밋 메시지: `fix(<도메인>): <이슈 제목 요약> (#이슈번호)` 형식 — `closes/fixes/resolves` 키워드는 자동 close를 트리거하므로 사용 금지
