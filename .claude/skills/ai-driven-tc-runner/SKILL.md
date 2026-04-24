---
name: ai-driven-tc-runner
description: TC 명세 문서(docs/testing/ai-driven/*.md)를 읽고 playwright-cli로 브라우저를 자동 조작하여 테스트를 실행하고 결과를 보고한다. 사용자가 "TC 테스트", "테스트 케이스 실행", "AI-driven 테스트", "TC 문서 기반으로 테스트" 같은 말을 할 때 반드시 이 스킬을 사용한다. TC 문서 경로나 이름을 제시하는 경우에도 이 스킬을 발동한다.
---

# AI-Driven TC Runner (playwright-cli)

TC 명세 문서를 기반으로 playwright-cli로 테스트를 실행하고 결과를 보고하는 스킬이다.

---

## 1. 준비 단계

### 1-1. TC 문서 읽기
사용자가 지정한 TC 문서를 읽어 각 TC의 구조를 파악한다:
- **PreCondition**: 선행 조건 (TC 간 의존성 포함)
- **Steps**: 실행 단계 (스텝 유형을 분류하여 처리)
- **Expected Result**: 어설션 체크리스트
- **PostCondition**: 테스트 후 정리 작업

TC 간 의존성이 있으면 반드시 순서대로 실행한다.

### 1-2. 서버 기동

서버가 이미 실행 중이 아니면 프로젝트 루트에서 실행:
```bash
pnpm dev
```
firehub-web(5173), firehub-api(8090), firehub-ai-agent가 자동으로 기동된다.

---

## 2. 브라우저 열기 및 로그인

### 2-1. 인증 상태 캐시 확인

`test-results/.auth.json` 이 존재하면 재사용한다:
```bash
playwright-cli open --browser=chrome --headed http://localhost:5173
playwright-cli state-load test-results/.auth.json
playwright-cli goto http://localhost:5173/
playwright-cli snapshot
```
스냅샷에서 로그인 상태(홈 화면)가 확인되면 3번으로 이동한다.

### 2-2. 로그인 (캐시 없거나 만료 시)

credentials는 `memory/` 에서 확인한다.
```bash
playwright-cli open --browser=chrome --headed http://localhost:5173
playwright-cli snapshot  # → 이메일/비밀번호 필드 ref 확인
playwright-cli fill <email-ref> "bluleo78@gmail.com"
playwright-cli fill <password-ref> "<password>"
playwright-cli click <로그인버튼-ref>
playwright-cli run-code "async page => await page.waitForURL('http://localhost:5173/', {timeout: 10000}).catch(() => {})"
playwright-cli snapshot  # 로그인 성공 확인 (홈 화면 / URL: http://localhost:5173/)
playwright-cli state-save test-results/.auth.json
```

---

## 3. 각 TC 실행

TC를 순서대로 하나씩 실행한다. 실행 전 해당 TC 번호를 사용자에게 알린다.

각 TC 시작 시 영상 녹화 및 시작 스크린샷을 찍는다:
```bash
playwright-cli video-start test-results/<문서명>/<TC-ID>/recording.webm
playwright-cli screenshot --filename=test-results/<문서명>/<TC-ID>/start.png
```

### 3-0. PreCondition 처리

| PreCondition 항목 | 처리 방법 |
|------------------|-----------|
| 로그인 상태 | 섹션 2 완료 여부 확인 |
| AI 사이드 패널 열린 상태 | 상단 "AI 상태: AI 어시스턴트" 버튼 클릭 후 **마우스를 중앙으로 이동**(hover 해제), 입력창(placeholder: "메시지를 입력하세요...") 출현 확인. 아래 명령 사용: `playwright-cli run-code "async page => await page.locator('[aria-label*=\"AI 상태\"]').click()"` → `playwright-cli mousemove 600 400` (hover 해제). **패널 닫힘 후 재오픈 시**: 페이지 리로드 후 `[aria-label*="AI 상태"]` aria-label이 변경될 수 있으므로 `page.locator('[aria-label*="AI"]').first().click()` 또는 snapshot에서 최신 ref 확인. |
| 특정 데이터/항목이 존재해야 함 | 이전 TC 결과 활용 또는 API/UI로 직접 생성 |
| 특정 데이터/항목이 없어야 함 | 목록 확인 후 존재하면 삭제 |

### 3-1. 스텝 유형별 실행

TC Steps의 각 단계를 아래 4가지 유형으로 분류하여 처리한다.

---

#### 유형 A. AI 채팅 메시지 전송

Steps에 AI 채팅에 입력할 자연어 메시지가 명시된 경우:

> **주의**: `<main>` 요소가 pointer events를 가로채므로 `click` 대신 `run-code`로 textarea를 직접 포커스/채워야 한다.

```bash
playwright-cli run-code "async page => { const el = page.getByPlaceholder('메시지를 입력하세요...'); await el.focus(); await el.fill('<메시지>'); }"
playwright-cli press Enter
```

**응답 완료 대기** — "생각하는 중"과 "응답 생성 중" 텍스트가 모두 사라질 때까지 기다린다:
```bash
playwright-cli run-code "async page => await page.waitForFunction(() => !document.body.innerText.includes('생각하는 중') && !document.body.innerText.includes('응답 생성 중'), {timeout: 90000})"
```
"데이터를 분석하는 중" 같은 다른 중간 상태 텍스트가 남아있으면 다시 대기한다.

**응답 텍스트 추출:**
```bash
playwright-cli --raw snapshot > /tmp/snapshot.yml
grep -A 20 "message-bubble\|assistant\|ai-response" /tmp/snapshot.yml | tail -40
```
MCP 도구 호출 레이블(예: "데이터셋 생성", "delete_dataset 완료")도 텍스트에 포함된다.

---

#### 유형 B. 직접 UI 조작

버튼 클릭, 페이지 이동, 폼 입력 등:

```bash
playwright-cli snapshot           # 대상 요소 ref 확인
playwright-cli click <ref>
playwright-cli goto "http://..."
playwright-cli fill <ref> "<값>"
playwright-cli run-code "async page => await page.waitForSelector('...', {timeout: 30000})"
playwright-cli snapshot           # 상태 변화 확인
```

---

#### 유형 C. API 직접 호출

PreCondition/PostCondition에서 데이터 준비·정리:

```bash
# 토큰 발급 (Vite proxy: 5173 → 8090)
TOKEN=$(curl -s -X POST http://localhost:5173/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"bluleo78@gmail.com","password":"<password>"}' | jq -r '.accessToken')

# 예: 데이터셋 생성 (컬럼 필드명 주의: columnName/displayName 사용)
curl -X POST http://localhost:5173/api/v1/datasets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "test_dataset", "tableName": "test_dataset", "columns": [{"columnName":"col1","displayName":"컬럼1","dataType":"VARCHAR","maxLength":100}]}'

# 예: 데이터셋 삭제
curl -X DELETE http://localhost:5173/api/v1/datasets/{id} \
  -H "Authorization: Bearer $TOKEN"
```

---

#### 유형 D. UI 상태 확인

목록·카운트·항목 존재 여부 검증:

```bash
playwright-cli goto "http://localhost:5173"  # 해당 페이지로 이동
playwright-cli --raw snapshot > /tmp/snapshot.yml
grep "<검증할 텍스트>" /tmp/snapshot.yml
```

---

## 4. 어설션 검증

| 어설션 유형 | 검증 방법 |
|------------|-----------|
| 텍스트 키워드 포함 | snapshot YAML에서 `grep` |
| MCP 도구 호출 여부 | snapshot에서 도구 레이블 텍스트 확인 |
| 재확인 요청 여부 | AI 응답 텍스트에서 "확인", "삭제할까요" 등 판단 |
| UI 목록 변화 | 카운트 숫자 또는 항목명 비교 |
| 오류 메시지 부재 | "오류", "실패" 키워드 미포함 확인 |

조건부 단계(예: "AI가 재확인을 요청하는 경우")는 응답 내용에 따라 분기하여 처리하고, 해당 분기 결과를 어설션에 반영한다.

TC 완료 시 스크린샷 및 영상 저장:
```bash
playwright-cli screenshot --filename=test-results/<문서명>/<TC-ID>/end.png
playwright-cli video-stop
```

---

## 5. PostCondition 정리

각 TC의 PostCondition에 명시된 정리 작업을 수행한다:
- 데이터 삭제: AI 채팅 요청(유형 A) 또는 API 직접 호출(유형 C)
- "없음"이면 스킵

모든 TC 완료 후 브라우저를 닫는다:
```bash
playwright-cli close
```

---

## 6. 결과 보고

모든 TC 실행 후 다음 형식으로 보고하고 `test-results/<문서명>/result.md` 에 저장한다:

```
## TC 실행 결과 — <문서명>

| TC | 테스트 항목 | 결과 | 소요 시간 | 비고 |
|----|------------|------|-----------|------|
| TC-XX-01 | 항목명 | ✅ 통과 / ⚠️ 부분 / ❌ 실패 | 00:00 | 실패 이유 |

### 어설션 상세

**TC-XX-01**
- [x] 어설션 1 — 확인됨
- [x] 어설션 2 — 확인됨
- [ ] 어설션 3 — **미충족**: <이유>

### 주요 이슈
- TC-XX: <이슈 설명 및 개선 권고>

---
실행 일시: YYYY-MM-DD HH:MM  
총 소요 시간: 00:00  
통과/부분/실패: N / N / N
```

---

## 주의사항

- `playwright-cli snapshot` ref는 동적으로 변하므로 매 단계마다 최신 snapshot에서 가져온다
- AI 응답 대기: `run-code`로 `waitForFunction` 사용 (timeout: 60000ms)
- 일반 UI 조작 대기: `run-code`로 `waitForSelector` 사용 (timeout: 30000ms)
- `test-results/.auth.json` 은 git에 커밋하지 않는다 (.gitignore 확인)
- 스냅샷 분석: `playwright-cli --raw snapshot > /tmp/snapshot.yml` 후 `grep`으로 검색
- **`<main>` pointer event 차단**: AI 패널의 많은 버튼이 `<main>` 요소에 의해 클릭이 차단됨. `playwright-cli click` 대신 아래 방법 사용:
  - textarea 입력: `run-code "async page => { const el = page.getByPlaceholder('메시지를 입력하세요...'); await el.focus(); await el.fill('...'); }"`
  - 새 대화 버튼: `run-code "async page => { const btns = await page.getByRole('button', { name: '새 대화' }).all(); for (const btn of btns) { await btn.dispatchEvent('click'); } }"`
  - 기타 버튼: `run-code "async page => { await page.locator('...').dispatchEvent('click'); }"`
