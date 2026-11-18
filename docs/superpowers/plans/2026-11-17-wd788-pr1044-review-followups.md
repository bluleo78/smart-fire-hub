# WD-788 통합 PR #1044 리뷰 후속 — 계획

이슈 10항목을 코드로 확인했고, 모두 지적이 맞다. 항목마다 고칠지·어떻게 고칠지는 아래에 정했다. 과제는 앱별로 셋(A web·admin, B ai-agent, C api)이며 서로 파일이 겹치지 않는다.

## 과제 A — web·admin (항목 1·2·5·7)

### 1. 지워진 추가 헤더의 옛 가린 값 (`ApiConnectionDetailPage.tsx`, `syncUntouchedStoredMasks`·`mergeAuthFields`)

편집 중 재조회 때 고친 덩어리 안의 저장 행(storedName 있음)이 새 기준에 그 storedName 이 없으면(서버에서 지워짐):

- **손대지 않은 행**(이전 기준과 이름·값이 같음)은 목록에서 뺀다. 지워진 헤더를 다시 보내지 않게 하려는 것이다.
- **손댄 행**(이름이나 값을 바꿈)은 새 행으로 바꾼다. storedName·storedMasked 를 지우고 이름·값은 그대로 둔다.
  - 값이 비었으면 저장 때 행 검증이 「값 필수」로 잡는다. 가린 값을 보내지 않는다.
- 하나라도 그렇게 바꿨으면 토스트로 알린다. 문구는 「다른 곳에서 추가 헤더 <이름…>가 지워져 목록을 맞췄습니다」 정도로 한다.
- 행 id 가 바뀌지 않으면 행 오류는 그대로 둔다. 뺀 행의 오류는 지운다(`withoutChangedRowErrors` 활용).
- 덩어리를 고치지 않은 경우는 지금처럼 새 행으로 통째로 바꾸므로 해당하지 않는다.

### 2. 인증 방식만 따로 병합 (`mergeAuthFields`)

조건: 인증 방식이 재조회로 새 값으로 바뀌었을 때(사용자는 방식을 고치지 않았고 서버 쪽 방식이 달라짐). 이때는 방식에 딸린 칸을 새 기준 값으로 함께 바꾼다.

- 함께 바꾸는 칸: placement·headerName·paramName·username, 그리고 입력한 비밀 apiKey·token·password(비운다). 추가 헤더는 방식과 무관하니 지금 규칙 그대로 둔다.
- 사용자가 고친 딸린 칸이 있었다면 토스트로 알린다. 문구는 「다른 곳에서 인증 방식이 <새 방식>으로 바뀌어, 고친 인증 칸을 새 값으로 바꿨습니다」로 한다.
- 이렇게 하면 숨은 dirty 가 남지 않는다.
- 사용자가 방식을 고쳤으면 지금처럼 사용자 방식을 지키고 칸별 병합한다.

### 5. 콘솔 판정 순서 (`apps/firehub-admin/src/components/tenant/AddTenantMemberDialog.tsx` ~461행)

- 서버는 이제 「이미 멤버 → 비활성」 순서다(`PlatformTenantMemberAddTransaction`).
- NewAccountTab 에서 비활성 계정이면서 이미 멤버인 경우, 다른 탭처럼 「이미 멤버」 안내를 보이게 한다. 비활성이면서 멤버가 아니면 지금처럼 안내하지 않는다.
- 주석을 새 순서에 맞춘다.
- admin E2E(또는 그 앱의 시험 방식)로 회귀를 더한다.

### 7. `AddMemberDialog.tsx`(web, ~360행) 충돌 조회 하나로

- `conflict` 를 CONFLICTS 에서 한 번 꺼낸 뒤 MEMBER_SUSPENDED·ACCOUNT_ALREADY_EXISTS 갈래도 그 값을 쓰게 한다. 동작은 바꾸지 않는 정리다.
- 기존 member-add E2E 가 그대로 통과해야 한다.

### 시험

- web E2E 를 `e2e/pages/` 의 기존 API 연결 상세 spec 에 더한다. 항목 1·2 를 재현해 저장 payload 를 확인한다.
  - 항목 1: 지워진 헤더의 가린 값이 실리지 않는다.
  - 항목 2: 숨은 headerName 이 dirty 로 남지 않고, 미저장 경고가 뜨지 않으며, 토스트가 뜬다.
- 실행: web typecheck·lint, 바꾼 spec.

## 과제 B — ai-agent (항목 3·4, `report-number-check.ts`)

### 3. `matchesComputedRate` 좁히기

- `MAX_RATE_OPERANDS` 를 30 에서 8 로 낮춘다.
- 값이 같은 쌍(a == b, 상대오차 EPSILON 이내)은 건너뛴다. 0%·100% 를 막기 위해서다.
- 재료가 4개를 넘는 줄에서는 소수 자릿수가 있는 %(decimals ≥ 1)만 계산 증감률로 허용한다.
  - 기존 시험(WD-775·781·783)이 깨지면 이 규칙은 빼고 「내가 정한 것」에 이유를 적는다.
- 지어낸 % 가 통과하지 않는다는 시험을 더한다.
  - 같은 값 둘 → 0%·100% 거부.
  - 재료 많은 줄의 지어낸 정수 % 거부.
  - 재료 9개 이상인 줄은 계산하지 않음.

### 4. 단위 토큰의 숫자 그대로 비교 — 주석으로 닫는다

- 출처 숫자는 `Set<number>` 로만 들어와 열 이름이 없다. 열 이름으로 좁히려면 `collectSourceNumbers` 부터 구조를 바꿔야 해서 비용이 크다.
- `NumberIndex.matches` 주석에 이 위험을 적는다. 예: 「1.3억」이 출처의 관계없는 비율 1.3 과 맞을 수 있고, 경고 전용이라 받아들인다.

### 시험

- `pnpm --filter firehub-ai-agent typecheck`, `test`(전체).

## 과제 C — api (항목 6·8·9)

### 6. `UserService.lookupMember` Javadoc (~323행)

- addMember 쪽(`TenantMemberAdder` ~104행)이 실제로 쓰는 판정과 맞춘다. 두 판정이 같은 `userRepository.findByUsernameIgnoreCase` 로 같은 계정을 고른다는 것을 정확히 적는다.
- 같은 헬퍼로 합칠 수 있으면(동작이 같고 작은 변경이면) 합친다. 아니면 문서만 고친다.

### 8. Qdrant 시험의 「모두 지움」이 캐시된 다른 컨텍스트에 미치는 영향

- 컬렉션 이름에 접두사 설정이 없어(QdrantCollectionSpec 고정 이름) 「이 시험 것만 지우기」는 같은 이름을 지우는 것과 같다.
- 이 시험의 `@BeforeEach` 는 원래부터 모두 지우고 다시 준비했다. 다른 Qdrant 시험도 대부분 `@BeforeEach` 에서 그렇게 한다.
- 예외는 `QdrantRowSearchServiceTest`(앱 기동 때 준비에 기댐)다. 그 시험에 `@BeforeEach` 로 `new QdrantVectorStoreLifecycle(client, PROPS).prepare()`(멱등)를 더해, 다른 시험이 지운 뒤에도 기본 컬렉션이 있게 한다.
- `TiberoQdrantRowSearchLiveTest` 도 같은지 보고 필요하면 같게 한다.
- `QdrantPlatformSearchIntegrationTest` 의 주석에 이 관계를 적는다.

### 9. `DatasetReferenceRepositoryContractTest` 의 SUSPENDED

- 폴러를 끄는 설정은 없다. `@Scheduled(fixedDelay = 30000)` 이 고정이라, 끄려면 운영 코드에 시험 전용 스위치를 더해야 한다.
- 대신 시험 주석에 「저장소 계약 메서드는 테넌트 상태로 거르지 않는다 — 거르게 바뀌면 이 정지 상태를 다시 볼 것」을 적는다.
- 가능하면 그 전제를 단언으로 둔다. 예: SUSPENDED 테넌트에서도 `findDatasetChangeTriggers` 가 결과를 낸다는 것은 이미 시험이 단언한다 → 주석으로 연결.

### 시험

- `./gradlew test -PtestForks=1 --tests` 로 아래를 돌린다.
  - 바뀐 클래스
  - `QdrantPlatformSearchIntegrationTest`·`QdrantRowSearchServiceTest`
  - `MemberAddServiceTest`·`PlatformTenantMemberAddTest`
  - `DatasetReferenceRepositoryContractTest` 하위 클래스들

## 항목 10 — 기록만

- 이번 커밋 제목 끝 키는 `(WD-788)`. 고칠 코드는 없다.

## Review Focus

- 항목 1: 손댄 행을 새 행으로 바꿀 때 사용자가 쓴 값이 그대로 가는지, 가린 값(`****`)이 절대 실리지 않는지.
- 항목 2: 방식이 바뀌는 재조회에서 비밀 입력을 비우는 것이 맞는지. 옛 방식의 키를 새 방식에 보내지 않는다.
- 항목 3: 상한·같은 값 쌍 제외가 WD-781 이 고친 실제 사례(이슈 5건)를 다시 경고하게 만들지 않는지.
- 항목 8: prepare 멱등성. 구성이 다르면 예외인데, 같은 PROPS 라 괜찮은지.
