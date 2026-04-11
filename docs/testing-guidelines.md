# 테스트 / 커버리지 가이드라인

Smart Fire Hub의 테스트 작성과 커버리지 관리를 위한 단일 진실 문서(Single Source of Truth).
모든 앱(`firehub-api`, `firehub-ai-agent`, `firehub-web`)에 공통 적용된다.

---

## 1. 원칙 (Principles)

- **TDD 기본 지향**: 실패하는 테스트 먼저 → 테스트를 통과시키는 최소 구현 → 리팩터. 이 사이클을 작게 반복한다.
- **정상 + 예외 + 엣지**: Happy path만 테스트하는 것은 테스트가 아니다. 각 기능마다 최소한 정상 케이스, 예외 케이스, 경계값(엣지) 케이스를 함께 작성한다.
- **Mock 최소화**: 진짜 통합 테스트가 가능하다면 통합으로 작성한다. Mock은 **외부 시스템 경계에만** 사용한다(외부 HTTP API, 3rd-party SDK, 네트워크 경계 등). 내부 모듈을 과도하게 mocking하면 리팩터에 취약해지고, 실제 동작을 보장하지 않는다.
- **E2E는 API 모킹**: `firehub-web`의 Playwright E2E는 백엔드를 직접 기동하지 않고 `page.route()`로 `/api/v1/*`를 모킹한다(기존 관례, `apps/firehub-web/CLAUDE.md` 참고). 결정적 실행과 빠른 속도가 목적.
- **테스트 이름 = 스펙 문장**: 테스트 이름만 읽어도 기능 명세를 이해할 수 있어야 한다. `test1`, `shouldWork` 같은 이름은 금지.
- **깨진 테스트는 코드부터 의심**: 테스트가 실패하면 먼저 프로덕션 코드의 버그를 의심하고, 그 다음에 테스트 자체를 의심한다. "테스트가 이상하다"는 결론은 증거가 있을 때만 내린다.
- **Flaky 제로 유지**: 간헐적으로 실패하는 테스트는 기술 부채 1순위. 발견 즉시 루트 원인 이슈로 등록하고 수정한다. `.skip`로 숨기지 않는다.

---

## 2. 레이어별 TC 작성 지침

### 2.1 Backend — `firehub-api`

Java 21 / Spring Boot / jOOQ / JUnit 5 / JaCoCo.

- **Service 레이어 (가장 중요)**
  - `IntegrationTestBase`를 상속해 **실제 Postgres DB**를 사용한다.
  - `@MockitoBean`은 꼭 필요한 외부 경계(HTTP 클라이언트, S3, 외부 시스템)에만 사용한다. 과도하게 사용하면 Spring 컨텍스트 수명이 폭발하고, Postgres `too many clients` 오류가 발생한다.
  - 정상 + 예외(권한, 유효성) + 엣지(빈 입력, 대량, 중복) 모두 커버한다.
- **Repository / jOOQ**
  - 대부분 Service 테스트로 간접 커버된다.
  - 전용 테스트는 복잡한 SQL, 조인, 공간 쿼리 등 Service에서 검증하기 어려운 경우에만 작성한다.
- **Controller**
  - MockMvc로 **HTTP 계약**만 검증한다: route, 상태 코드, 요청/응답 body 구조, 인증/권한 가드.
  - 비즈니스 로직 검증은 Service 테스트에 맡긴다. Controller 테스트가 비즈니스 시나리오를 검증하지 않도록 주의.
- **Pipeline / Executor**
  - 각 `StepExecutor`는 **단위 테스트**로 입출력/에러 경로를 검증한다.
  - 전체 파이프라인은 **통합 E2E 테스트 1개**로 orchestration 동작을 확인한다.

### 2.2 AI Agent — `firehub-ai-agent`

Node.js / TypeScript / Vitest + nock / `@vitest/coverage-v8`.

- **MCP Tools**
  - 각 tool handler는 mock `apiClient`를 주입해 단위 테스트한다.
  - 정상 경로 + API 에러(4xx/5xx) + 입력 검증 실패 경로를 모두 커버한다.
- **api-client**
  - `nock`으로 HTTP를 모킹한다.
  - 각 백엔드 엔드포인트 호출마다 정상 응답 + 4xx/5xx/타임아웃 케이스를 작성한다.
- **Agent logic (`agent-sdk.ts`)**
  - 통합 테스트로 작성한다: SSE 이벤트 시퀀스, 세션 재개, 토큰 컴팩션, tool-use 라운드트립 등.
- **Subagent loader**
  - 실제 디스크를 사용하되, 임시 디렉터리(`os.tmpdir()`)에 픽스처를 배치한다.
  - 캐시 적중/무효화 동작도 검증한다.

### 2.3 Web — `firehub-web`

React / Vite / Playwright E2E / `monocart-coverage-reports`.

- **E2E (Playwright)**
  - 주요 사용자 플로우를 시나리오 단위로 작성한다.
  - `page.route()`로 API를 모킹해 결정적으로 실행한다.
  - 스크린샷은 `snapshots/`에 저장(CLAUDE.md 규약).
- **단위 테스트**
  - **현재 0개**. 복잡한 훅, 유틸, 상태 로직은 Vitest + `@testing-library/react`로 단위 테스트를 추가할 것을 권장한다.
  - 순수 프레젠테이셔널 컴포넌트는 E2E에 맡기고 단위 테스트를 작성하지 않는다.
- **시각적 회귀**
  - 현재 미도입. 필요 시 `@playwright/test` 스크린샷 비교(`toHaveScreenshot`)를 활용한다.

---

## 3. 커버리지 목표

**기본 원칙**: 신규 코드 ≥ **80%**, 전체 ≥ **70%**. 모든 레이어 공통 하한선이다.

**레이어별 상향 권장**:

| 레이어 | 신규 코드 | 전체 유지 |
|--------|---------|---------|
| 기본 최소치 (하한선) | **80%** | **70%** |
| Service / 비즈니스 로직 | 90% | 80% |
| 유틸리티 / 헬퍼 | 95% | 85% |
| Repository / DB 접근 | 80% | 70% |
| Controller / Route / HTTP | 80% | 70% |
| UI 컴포넌트 | 80% | 70% |
| DTO / Model / 엔트리 파일 | 제외 | 제외 |

**해석**:

- 80% 신규 / 70% 전체는 **절대 하한선**이다. 아래로 내려가서는 안 된다.
- Service, 유틸리티처럼 비즈니스 가치가 높은 레이어는 더 엄격하게 관리한다.
- 2026-04-11 현재 베이스라인은 모든 앱에서 하한선 미달이다. **신규 코드는 즉시 엄격 적용**, 기존 코드는 **점진적으로 상승**시킨다.

---

## 4. 측정 명령어

### 4.1 `firehub-api` (JaCoCo)

```bash
cd apps/firehub-api
./gradlew test jacocoTestReport
# 리포트: build/reports/jacoco/test/html/index.html

# 특정 패키지만:
./gradlew test jacocoTestReport --tests "com.smartfirehub.dataset.*"
```

### 4.2 `firehub-ai-agent` (Vitest + v8)

```bash
cd apps/firehub-ai-agent
pnpm test -- --coverage
# 리포트: coverage/index.html
# 요약 JSON: coverage/coverage-summary.json
```

### 4.3 `firehub-web` — Playwright E2E 커버리지

```bash
cd apps/firehub-web
pnpm exec playwright test --project=chromium
# 리포트: coverage/e2e/index.html
# 요약 JSON: coverage/e2e/coverage-summary.json
```

### 4.4 `firehub-web` — Vitest 단위 (현재 테스트 0개)

```bash
cd apps/firehub-web
pnpm exec vitest run --coverage
# 리포트: coverage/unit/index.html (테스트 추가 후 생성)
```

---

## 5. 리포트 해석 가이드

### 5.1 지표 의미

- **Lines %**: 실행된 소스 라인의 비율. 가장 일반적으로 인용되는 지표.
- **Branches %**: `if/else`, `switch`, 삼항 연산자 등의 분기 각각이 실행되었는지. 보통 Lines보다 낮게 나온다.
- **Functions %**: 최소 한 번이라도 호출된 함수의 비율.
- **Statements %**: JS/TS 고유 지표. 실행된 statement의 비율로, Lines와 거의 동일한 값을 보인다.

### 5.2 수치가 덜 중요한 경우

- 단순 getter/setter, 데이터 클래스, DTO
- 서드파티 라이브러리의 얇은 passthrough 래퍼
- 엔트리 파일(`main.tsx`, `index.ts`)

이런 파일은 커버리지 제외 대상으로 명시하거나, 수치를 참고하지 않는다.

### 5.3 시그널 vs 노이즈

- 커버리지 수치는 **품질 보장이 아니다**. 의미 없는 assertion으로 수치만 올리는 것은 기만이다.
- **Branches % < Lines %**: 조건 분기 테스트가 부족하다는 뜻. 우선 보강 대상.
- **Functions % 가 낮다**: 일부 함수가 어느 테스트에서도 호출되지 않는다. 먼저 **불필요한 dead code**인지 검토한 뒤, 살아있는 코드라면 테스트를 추가한다.
- 전체 수치가 목표 미달이면 **낮은 레이어(Service, 유틸)부터** 보강한다. UI부터 손대면 ROI가 낮다.

---

## 6. 신규 코드 정책 (CI 도입 시 적용)

CI는 현재 미구축 상태이다. 아래 정책은 **정책 문서로만 존재**하며, 실제 강제는 CI 도입 이후 시작된다.

- **신규 코드 ≥ 80% (Hard fail)**: PR에서 추가/수정된 파일의 커버리지가 80% 미만이면 머지 차단.
- **전체 ≥ 70% (Soft report)**: 머지 후 전체 커버리지가 70% 미만으로 떨어지면 경고만 발생, 차단은 하지 않음.
- CI 도입 시 GitHub Actions + Codecov(또는 유사 도구) 연동을 고려한다. 이는 별도 Phase로 다룬다.

**지금 당장 해야 할 일**: 로컬 self-check.
PR 작성 전에 위 측정 명령어를 실행해 본인이 만진 파일의 수치를 확인하고, 목표 미달이면 테스트를 추가한 뒤 머지한다.

---

## 7. FAQ

**Q. 테스트가 flaky한데 일단 `.skip`로 넘기고 가도 되나요?**
A. 안 됩니다. Flaky는 기술 부채 1순위이며, 근본 원인(타이밍 이슈, 전역 상태 오염, 외부 의존성 등)을 찾아서 수정합니다. 수정 전까지는 `.skip` 대신 **루트 원인 이슈를 등록**하고 해당 PR 내에서 수정합니다.

**Q. Mock을 얼마나 써야 하나요?**
A. 가능하면 **안 쓰는 방향**으로. DB는 실제 Postgres, HTTP는 nock/MockMvc, 파일 I/O는 임시 디렉터리를 사용합니다. Mock은 외부 시스템 경계(외부 HTTP API, 3rd-party SDK)에만 사용합니다. 내부 모듈을 mocking 하는 순간 리팩터에 취약해지고 실제 동작을 보장하지 못합니다.

**Q. E2E가 너무 느립니다. 단위 테스트로 대체하면 안 되나요?**
A. 둘의 목적이 다릅니다. 단위 테스트는 개별 모듈의 정확성을, E2E는 통합된 사용자 시나리오를 검증합니다. 둘 다 필요합니다. 느린 E2E는 **병렬화 + API 모킹 + 불필요한 wait 제거**로 가속시킵니다.

**Q. 하한선 미달인데 긴급 배포가 필요합니다.**
A. 사용자에게 명시적으로 승인받고 **기록을 남긴 뒤** 일회성 예외를 허용합니다. 반드시 후속 PR에서 테스트를 보강하는 것이 조건입니다.

**Q. UI 컴포넌트 단위 테스트는 어떻게 씁니까?**
A. `firehub-web`에 아직 단위 테스트가 0개입니다. `@testing-library/react`와 Vitest를 도입한 뒤 **복잡한 훅 / 상태 / 유틸 로직부터** 시작하세요. 순수 프레젠테이셔널 렌더링 컴포넌트는 E2E에 맡깁니다.

**Q. Playwright 커버리지는 정확한가요?**
A. Vite dev mode + V8 Coverage API + `monocart-coverage-reports`가 소스맵을 복원하여 실제 소스 파일 단위로 측정하므로 신뢰할 수 있습니다. 다만 제약 사항이 있습니다:
1. **Chromium에서만** 동작합니다(V8 Coverage API).
2. **브라우저에서 실행된 코드만** 측정됩니다(SSR 코드는 커버 불가).
3. **API가 모킹**되어 있으므로 백엔드 호출 경로는 커버되지 않습니다.

**Q. `firehub-api` 전체 커버리지가 14%인데 맞나요?**
A. 2026-04-11의 측정은 `dataset.*` 테스트에 한정된 부분 측정입니다. 전체 스위트 실행은 기존(pre-existing) 실패 때문에 보류 중입니다. 전체 `./gradlew test`가 green이 되는 시점에 베이스라인을 갱신할 예정입니다.

---

## 8. PR 체크리스트

PR을 올리기 전에 **스스로** 다음을 확인한다.

- [ ] 신규 기능은 실패 테스트부터 작성했는가 (TDD)
- [ ] 정상 케이스뿐 아니라 예외/엣지 케이스도 테스트했는가
- [ ] Mock 사용이 정당한가 (외부 시스템 경계에만)
- [ ] 테스트 이름만 읽어도 스펙이 이해되는가
- [ ] 로컬에서 `test --coverage`를 실행해 수치를 확인했는가
- [ ] 변경한 파일의 커버리지가 **80% 이상**인가
- [ ] Flaky한 테스트를 추가하지 않았는가 (최소 3회 반복 실행 후 확인)
- [ ] 한국어 주석/메시지 규약을 지켰는가 (CLAUDE.md)

---

## Baseline (2026-04-11)

최초 도구 도입 시점의 수치와 가이드라인 시행 직후 수치:

| App | 초기 | 현재 | 비고 |
|-----|------|------|------|
| firehub-api | 14.69% (dataset 한정) → 69.22% (jOOQ 제외 기준 전체) | **73.02%** | jOOQ/dto/exception 제외. FileParserService 93%, AiClassifyExecutor 87% 등 서비스 테스트 보강 |
| firehub-ai-agent | 67.47% | **72.79%** | api-client 테스트 보강 (proactive, analytics) |
| firehub-web (E2E) | 60.42% | **70.27%** | Playwright monocart. Round 1 3개 + Round 2 5개 + Round 3 6개 시나리오 추가. Vitest+RTL 단위 테스트 프레임워크 도입 |

**전체 3앱 ≥70% 라인 커버리지 달성 (2026-04-11)**.

이후 변경 이력은 아래 섹션에 추가한다.

## 변경 이력

| 일자 | 변경 | 작성자 |
|------|-----|--------|
| 2026-04-11 | 가이드라인 최초 작성 및 커버리지 도구 도입 | Claude + DongHee |
| 2026-04-11 | 3앱 커버리지 도구 도입 + TC 보강으로 전원 70% 돌파 | Claude + DongHee |
