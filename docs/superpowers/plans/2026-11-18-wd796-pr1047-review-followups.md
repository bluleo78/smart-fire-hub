# WD-796 통합 PR #1047 리뷰 후속 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 팀장이 넘긴 지적 4건을 고친다. 고치는 것은 재조회 토스트 조사, 다른 곳에서 더한 추가 헤더, 증감률 재료 수 중복 셈, Qdrant 회귀 시험 정리 범위다.

**Architecture:** 세 앱에 걸친 작은 독립 수정 셋이다.
- web `ApiConnectionDetailPage.tsx` 의 재조회 병합(`syncStoredRows`·토스트)
- ai-agent `report-number-check.ts` 의 재료 수 판정
- api 시험 `QdrantRowSearchServiceTest`

**Tech Stack:** React + TS (Playwright E2E), Node TS (Vitest), Java 21 / JUnit5 (Testcontainers Qdrant)

**Spec:** `docs/superpowers/specs/2026-11-18-wd796-pr1047-review-followups-design.md`

## Global Constraints

- 한국어 주석 필수: 바꾼 함수·로직에 무엇을·왜를 적는다(WD-796 표기).
- 셸 명령은 단순하게 쓴다: 한 줄 셸 함수 정의, `${…:-{…}}` 중괄호 확장, `;`·`&&` 로 길게 이은 한 줄을 쓰지 않는다. 복잡하면 스크립트 파일로 실행한다.
- **gradle·Playwright E2E 는 돌리지 않는다.** 기차 게이트가 같은 컨테이너(메모리 16GB)에서 돌고 있어서다. 그 시험들은 작성만 하고, 실행은 컨트롤러가 게이트가 끝난 뒤 한다. 돌려도 되는 것: ai-agent `pnpm test`·`pnpm typecheck`, web `pnpm typecheck`·`pnpm lint`·vitest 단위, api `./gradlew compileTestJava`(컴파일만).
- 커밋은 과제마다 하나다. 커밋 훅이 무거우면 `git commit --no-verify` 로 하고 보고에 적는다 — 컨트롤러가 마지막에 하나로 합쳐 훅을 통과시킨다.

## Review Focus

1. 이름 변경(X-Id → x-id)은 「지워짐」과 「더해짐」 토스트가 함께 떠야 한다. 덩어리에는 x-id 저장 행이 들어가고, X-Id 는 손대지 않았으면 빠진다.
2. 사용자가 이미 같은 이름(대소문자 무시)의 새 행을 써 둔 상태에서 같은 헤더가 다른 곳에서 더해지면, 행이 둘이 되고 저장 때 중복 오류로 막혀야 한다. 소리 없이 하나를 버리면 안 된다.
3. 이전 기준에 있던 저장 행을 사용자가 덩어리에서 지운 경우, 그 헤더는 「더해진 것」으로 되살리면 안 된다(이전 기준에 있던 이름이므로 더해진 것이 아니다).
4. 재료 값이 모두 같은 줄(「100 · 100 · 100」)은 서로 다른 값 수가 1이라 쌍이 없다. 오류 없이 경고로 남아야 한다.
5. 소수가 있는 같은 값(28.80 과 28.8)은 nearlyEqual 로 같은 값으로 셈한다.

---

### Task 1: ai-agent — 재료 수를 서로 다른 값으로 센다

**Files:**
- Modify: `apps/firehub-ai-agent/src/agent/report-number-check.ts` (operandsOf 의 `values = verified.length > MAX_RATE_OPERANDS ? …`, matchesComputedRate 의 정수 % 상한 판정, 두 상수의 주석)
- Test: `apps/firehub-ai-agent/src/agent/report-number-check.test.ts` (WD-788 describe 근처에 WD-796 it 추가)

**Interfaces:** 내부 변경만 — `checkReportNumbers` 시그니처는 그대로다.

- [ ] **Step 1: 실패하는 시험 셋을 쓴다**
  - `it('같은 값이 여러 번 나와도 서로 다른 값 수로 재료 상한을 센다 — 100·100·100·100 → 130 (+30%) 는 계산값이다 (WD-796)')`
    - 출처 숫자 {100, 130}, 리포트 한 줄 「100 · 100 · 100 · 100 → 130 (+30%)」.
    - 기대: `unverified` 에 「30%」가 없다. 지금은 재료 5개라 정수 % 상한(4)에 걸려 실패한다.
  - `it('서로 다른 값이 9개 이상이면 여전히 판정하지 않는다 (WD-796)')` — 기존 9개 시험과 같은 성질을 서로 다른 값으로 확인한다.
  - `it('값이 모두 같은 줄은 쌍이 없어 % 를 계산값으로 인정하지 않는다 (WD-796)')`
    - 출처 {100}, 「100 · 100 · 100 (0%)」.
    - 기대: 「0%」 가 unverified 에 있다.
- [ ] **Step 2: 실행해 첫 시험이 실패하는지 본다** — `cd apps/firehub-ai-agent` 뒤 `pnpm test -- src/agent/report-number-check.test.ts`
- [ ] **Step 3: 구현한다**
  - operandsOf 에서 확인된 값들을 nearlyEqual 로 중복을 뺀 배열로 만든다(첫 값을 남긴다).
  - 그 길이로 `MAX_RATE_OPERANDS` 를 본다. `matchesComputedRate` 는 이미 서로 다른 값 배열을 받으므로 `MAX_INTEGER_RATE_OPERANDS` 판정이 그대로 서로 다른 값 수가 된다.
  - 상수 주석의 「재료 수」를 「서로 다른 재료 값 수」로 고친다.
- [ ] **Step 4: 통과를 확인한다** — 같은 명령, 이어 `pnpm typecheck`
- [ ] **Step 5: 커밋한다**

### Task 2: web — 토스트 조사와 다른 곳에서 더한 추가 헤더

**Files:**
- Modify: `apps/firehub-web/src/pages/admin/ApiConnectionDetailPage.tsx` (`SyncedStoredRows`, `syncStoredRows`, `mergeAuthFields` 의 토스트 두 곳)
- Test: `apps/firehub-web/e2e/pages/admin/api-connections.spec.ts` (`WD-788 재조회 병합 후속` describe 뒤에 `WD-796` describe — 같은 헬퍼 패턴 `storedApiKeyConnection`·`openAuthEdit` 를 그 describe 안에서 다시 쓰거나 위로 꺼낸다)

**Interfaces:**
- `interface SyncedStoredRows { rows: ExtraHeaderRow[]; removedNames: string[]; addedNames: string[] }`
- `syncStoredRows(rows, prevRows, nextRows): SyncedStoredRows` — 시그니처는 그대로다.

- [ ] **Step 1: 토스트 문구를 바꾼다** — 정확히 `다른 곳에서 인증 방식이 바뀌어(${authTypeLabel(next.authType, 'full')}), 고친 인증 칸을 새 값으로 바꿨습니다.`
  - 기존 WD-788 E2E 가 옛 문구(「…으로 바뀌어」)를 단언하면 새 문구로 고친다.
  - API_KEY 로 바뀌는 경우의 시험을 하나 더한다: `toBeVisible` 로 「인증 방식이 바뀌어(API Key)」를 보고, 「API Key으로」가 없음을 단언한다.
- [ ] **Step 2: E2E 를 쓴다(실행은 컨트롤러가 한다)**
  - `test('고친 추가 헤더 덩어리에서 다른 곳이 더한 헤더는 목록에 들어가고 알리며, 저장 payload 에 남는다 (WD-796)')`
    - 저장된 연결: 추가 헤더 `X-A`. 사용자는 X-A 값을 바꾼다.
    - 재조회 응답: X-A 와 새 X-B(가린 값 `****bbbb`)가 있다(기존 WD-788 시험이 재조회를 일으키는 방식 — 기본 정보 저장으로 다시 불러오기 — 을 그대로 쓴다).
    - 기대: 토스트 「다른 곳에서 추가 헤더(X-B)가 더해져 목록에 넣었습니다.」, X-B 행이 보인다. 인증 저장 PUT 본문에 X-B 키가 그 가린 값(유지 의미)으로 들어간다 — 기존 저장 행 payload 규칙을 따른다.
  - `test('다른 곳에서 이름을 바꾼 추가 헤더(X-Id → x-id)는 지워짐·더해짐을 함께 알리고, 새 이름 행이 남는다 (WD-796)')`
  - `test('사용자가 같은 이름의 새 행을 써 둔 채 그 헤더가 더해지면 행이 둘이 되어 저장이 중복 오류로 막힌다 (WD-796)')`
  - `test('사용자가 덩어리에서 지운 저장 헤더는 재조회로 되살아나지 않는다 (WD-796)')` — Review Focus 3.
- [ ] **Step 3: 구현한다**
  - `syncStoredRows`: 기존 반복 뒤, `nextRows` 중 storedName 이 `prevRows` 에 없고 결과 행들의 storedName 에도 없는 행을 끝에 더한다. 그리고 `addedNames` 에 그 storedName 을 넣고 changed 로 둔다.
  - 행 id 는 nextRows 의 것을 쓴다(extraHeaderRowsFromConfig 가 만든 새 id).
  - 이름 비교는 storedName 그대로(서버 키)다. 대소문자가 다른 사용자 새 행과는 합치지 않는다 — 중복 검증이 잡는다.
  - 함수 JSDoc 에 「새 기준에만 있는 저장 헤더(다른 곳에서 더함·이름 변경의 새 이름)는 끝에 더한다(WD-796)」를 적는다.
  - `mergeAuthFields`: `synced.addedNames.length > 0` 이면 `toast.info(\`다른 곳에서 추가 헤더(${names})가 더해져 목록에 넣었습니다.\`, { id: 'api-connection-extra-headers-added' })` 를 띄운다.
- [ ] **Step 4: 확인한다** — `cd apps/firehub-web` 뒤 `pnpm typecheck`, 이어 `pnpm lint`. E2E 는 돌리지 않는다.
- [ ] **Step 5: 커밋한다**

### Task 3: api 시험 — 회귀 시험은 자기 컬렉션만 지우고, `ready` 를 못 읽으면 실패한다

**Files:**
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/dataset/rowsearch/QdrantRowSearchServiceTest.java` (`restoreCollectionsWipedByOtherTests`, `rowCollectionsAreRecreatedAfterAnotherTestWipedEverything`)

- [ ] **Step 1: 회귀 시험을 고친다**
  - `QdrantTestContainer.deleteAllCollections(client)` 를 `client.deleteCollectionAsync(keyword).get(30, TimeUnit.SECONDS)` 로 바꾼다.
  - 시험 이름은 `rowCollectionIsRecreatedAfterAnotherTestDeletedIt` 로, Javadoc 은 「다른 시험이 행 컬렉션을 지운 뒤에도…(WD-796 — 공유 Qdrant 의 다른 컬렉션은 건드리지 않는다)」로 바꾼다.
- [ ] **Step 2: `ready` 를 못 읽으면 실패시킨다** — `ready == null` 이면 `IllegalStateException("QdrantVectorStoreLifecycle 의 ready 필드를 읽지 못했습니다 — 필드 이름이 바뀌었으면 이 시험을 고쳐야 합니다")` 를 던진다(조용히 넘기지 않게).
- [ ] **Step 3: 컴파일만 한다** — `cd apps/firehub-api` 뒤 `./gradlew compileTestJava -x generateJooq`. 실행은 컨트롤러가 한다.
- [ ] **Step 4: 다른 Qdrant 시험들의 전체 삭제 영향 범위를 조사해 보고한다**
  - `deleteAllCollections` 를 부르는 곳을 grep 해 목록을 만든다.
  - 그 컨텍스트들이 복구 장치 없이 같은 JVM 에서 캐시되는지 본다.
  - 코드는 바꾸지 않는다 — 컬렉션 이름이 고정이라 좁힐 수 없다.
- [ ] **Step 5: 커밋한다**
