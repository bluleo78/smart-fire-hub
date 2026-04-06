# E2E 테스트 강화 설계 문서

## 목표

firehub-web의 기존 176개 Playwright E2E 테스트를 smoke-test 수준(요소 존재 확인)에서 **전체 비즈니스 로직 검증** 수준으로 강화한다. 입력→API payload→응답→UI 반영 전체 파이프라인을 검증하는 프로덕션 등급 테스트로 업그레이드한다.

## 현황 분석

### 현재 assertion 분포

| assertion 유형 | 비율 | 개수(추정) |
|---------------|------|-----------|
| `toBeVisible()` | ~70% | 240+ |
| `toHaveURL()` | ~15% | 52 |
| `toHaveValue()` | ~8% | 27 |
| `toHaveText()` | ~5% | 18 |
| 기타 (attribute/count) | ~2% | 6 |

### 핵심 부재 영역 (6가지)

1. **Request payload 검증** — 0건
2. **Data→UI 셀 수준 검증** — 0건
3. **필터/검색 파라미터 검증** — 0건
4. **비즈니스 로직 검증** — 0건
5. **에러 상태 검증** — 거의 없음
6. **Zod 스키마 유효성 검증** — 거의 없음

### 인프라 상태

- 팩토리/픽스처 인프라(~400 LOC)는 잘 구축되어 있으나 **테스트에서 20%만 활용**
- `mockApi()` 헬퍼는 응답 설정만 가능, 요청 캡처 불가

## 설계

### 1. 인프라 변경: mockApi 캡처 확장

`api-mock.ts`에 capture 옵션을 추가하여 요청 캡처 기능을 구현한다.

```typescript
/** 캡처된 요청 정보 */
interface CapturedRequest {
  payload: unknown;                // route.request().postDataJSON()
  url: URL;                        // 전체 URL
  searchParams: URLSearchParams;   // query params 편의 접근자
}

/** mockApi의 capture: true 반환 타입 */
interface MockApiCapture {
  requests: CapturedRequest[];                    // 캡처된 모든 요청
  lastRequest: () => CapturedRequest | undefined; // 마지막 요청
  waitForRequest: () => Promise<CapturedRequest>;  // 다음 요청 대기 (Promise)
}
```

**사용법:**
```typescript
// capture 미지정 → 기존 동작 (void 반환)
await mockApi(page, 'GET', '/api/v1/datasets', mockList);

// capture: true → MockApiCapture 반환
const capture = await mockApi(page, 'POST', '/api/v1/datasets', mockResponse, { capture: true });
await page.getByRole('button', { name: '생성' }).click();
const req = await capture.waitForRequest();
expect(req.payload).toMatchObject({ name: '새 데이터셋' });
```

기존 `mockApi()` 호출은 변경 없이 동작한다 (하위 호환 보장).

### 2. 6개 강화 패턴 표준

각 테스트는 해당되는 패턴을 **모두** 적용한다:

#### 패턴 1: Payload 검증 (폼 제출 테스트)

POST/PUT/PATCH 요청의 body를 캡처하여 필드별 검증.

```typescript
const capture = await mockApi(page, 'POST', path, response, { capture: true });
await page.getByLabel('이름').fill('새 데이터셋');
await page.getByRole('button', { name: '생성' }).click();
const req = await capture.waitForRequest();
expect(req.payload).toMatchObject({
  name: '새 데이터셋',
  tableName: 'new_dataset',
  columns: expect.arrayContaining([expect.objectContaining({ columnName: 'id' })]),
});
```

#### 패턴 2: Data→UI 셀 수준 검증 (목록/상세 테스트)

모킹 API 응답 데이터가 테이블/카드에 셀 단위로 정확히 렌더링되는지 검증.

```typescript
const row = page.getByRole('row', { name: /테스트 데이터셋/ });
await expect(row.getByRole('cell').nth(0)).toHaveText('테스트 데이터셋');
await expect(row.getByRole('cell').nth(1)).toHaveText('test_table');
await expect(row.getByRole('cell').nth(2)).toHaveText('100');
```

#### 패턴 3: 필터/검색 파라미터 검증

검색어, 정렬, 페이지 번호가 API query param으로 전달되는지 캡처하여 검증.

```typescript
const capture = await mockApi(page, 'GET', path, response, { capture: true });
await page.getByPlaceholder('검색...').fill('소방');
const req = await capture.waitForRequest();
expect(req.searchParams.get('keyword')).toBe('소방');
```

#### 패턴 4: 비즈니스 로직 검증

계산된 값, 상태 매핑, 조건부 렌더링 등 프론트엔드 변환 로직을 검증.

```typescript
// 실행 시간 계산
await expect(row.getByRole('cell').nth(3)).toHaveText('1분 0초');
// 상태 배지 색상
await expect(badge).toHaveAttribute('data-variant', 'destructive');
```

#### 패턴 5: 에러 상태 검증

서버 에러(400/404/500) 시 구체적 에러 메시지가 올바르게 표시되는지 검증.

```typescript
await mockApi(page, 'POST', path, { message: '이미 존재하는 이름입니다.' }, { status: 400 });
await page.getByRole('button', { name: '생성' }).click();
await expect(page.getByText('이미 존재하는 이름입니다.')).toBeVisible();
```

#### 패턴 6: Zod 유효성 검사 검증

스키마별 구체적 에러 메시지 매칭 (regex 패턴 금지).

```typescript
await page.getByLabel('테이블명').fill('Invalid-Name');
await page.getByRole('button', { name: '생성' }).click();
await expect(page.getByText('영문 소문자와 밑줄만 사용할 수 있습니다')).toBeVisible();
```

### 3. 도메인별 강화 순서

도메인 순차 방식으로 진행한다. 첫 도메인(Dataset)에서 모든 강화 패턴을 확립하여 레퍼런스 구현을 만들고, 나머지 도메인에 일관되게 적용한다.

| 순서 | 도메인 | 테스트 수 | 파일 수 | 주요 강화 포인트 |
|------|--------|----------|--------|----------------|
| 0 | 인프라 | - | 1 | mockApi 캡처 확장 |
| 1 | Dataset | 29 | 6 | 생성 payload, 컬럼 스키마, 셀 수준 렌더링, 검색 param |
| 2 | Pipeline | 20 | 3 | 스텝 구성 payload, 실행 이력 셀 검증, 트리거 cron |
| 3 | Analytics | 36 | 7 | 쿼리 실행 결과, 차트 추천 로직, 대시보드 위젯 데이터 |
| 4 | AI Insights | 28 | 6 | 작업 생성 payload, 실행 상태, 빌더↔JSON 동기화 |
| 5 | Admin | 38 | 5 | 사용자/역할 CRUD payload, 권한 체크박스, 감사 로그 필터 |
| 6 | Auth | 17 | 3 | 로그인/회원가입 payload, 유효성 메시지 구체화 |
| 7 | Home | 8 | 1 | 대시보드 카드 수치, 최근 활동 렌더링 |

### 4. 수정 방식

기존 테스트 파일을 **in-place 수정**한다:
- describe/test 블록 구조 유지
- 테스트 이름 유지
- `toBeVisible()` assertion을 payload/data/error 검증으로 교체·확장
- 필요 시 새로운 assertion 추가 (기존 assertion 삭제는 최소화)

### 5. 완료 기준

#### 정량 기준

| 테스트 유형 | 필수 검증 항목 |
|------------|---------------|
| 폼 제출 테스트 | payload 캡처 + `toMatchObject` 검증 |
| 목록 테스트 | 최소 1개 행의 셀 단위 데이터 검증 |
| 필터/검색 테스트 | API query param 캡처 + 검증 |
| 에러 테스트 | 구체적 에러 메시지 매칭 (regex 패턴 금지) |
| Zod 유효성 테스트 | 스키마별 에러 메시지 매칭 |

#### 핸들러 커버리지 기준

각 페이지 컴포넌트의 `onSubmit`, `onClick`, `onChange` 핸들러가 **최소 1개 테스트에서 검증**되어야 한다.

#### 전체 완료 조건

- 176개 전체 테스트가 강화 완료
- `pnpm test:e2e` 전체 통과
- `npx tsc -p tsconfig.e2e.json --noEmit` 타입 체크 통과

## 제약사항

- 기존 테스트 인프라(fixtures, factories, api-mock) 구조를 유지하되 mockApi만 확장
- 팩토리 함수에 필요한 오버라이드를 추가할 수 있으나 기존 시그니처는 변경하지 않음
- 소스 코드(src/) 수정은 테스트 중 발견된 실제 버그에 한해서만 허용 (사용자 승인 필수)
- 새로운 테스트 파일 생성 없이 기존 파일 내에서 강화
