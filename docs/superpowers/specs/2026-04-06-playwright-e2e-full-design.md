# Playwright E2E 테스트 전체 설계 스펙

**날짜**: 2026-04-06
**범위**: firehub-web 전체 유저 플로우 (6개 도메인, 120개+ 테스트)
**기반**: Phase 1 환경 구축 완료 (playwright.config.ts, fixtures, 로그인 테스트 5개)

## 결정 사항

| 항목 | 결정 |
|------|------|
| 범위 | 6개 전체 플로우 (인증, 데이터셋, 파이프라인, 분석, AI 인사이트, 관리자) |
| 디테일 | 최대 — 해피 패스 + 유효성 검사 + 엣지 케이스 |
| 파일 구조 | 혼합 — `flows/`(연속 시나리오) + `pages/`(개별 상세) |
| 모킹 데이터 | 팩토리 패턴 — `factories/`에서 동적 생성, `src/types/` 타입 적용 |
| 네이밍 | 한국어 describe/test |
| 셀렉터 | `getByRole`/`getByLabel`/`getByText` 우선, `data-testid`는 필요한 곳만 |
| API 전략 | `page.route()` 기반 모킹 (백엔드 불필요) |
| Git hooks | pre-commit(lint+typecheck), pre-push(e2e+api test) |

## 디렉토리 구조

```
e2e/
├── factories/              # 모킹 데이터 팩토리 (src/types 타입 적용)
│   ├── auth.factory.ts     # createMockUser, createMockToken 등
│   ├── dataset.factory.ts  # createMockDataset, createMockCategory 등
│   ├── pipeline.factory.ts # createMockPipeline, createMockStep 등
│   ├── analytics.factory.ts # createMockQuery, createMockChart, createMockDashboard
│   ├── ai-insight.factory.ts # createMockJob, createMockTemplate, createMockExecution
│   └── admin.factory.ts    # createMockRole, createMockAuditLog, createMockApiConnection
├── fixtures/               # Playwright fixture (API 모킹 + 인증)
│   ├── api-mock.ts         # mockApi, mockApis 유틸리티 (구축 완료)
│   └── auth.fixture.ts     # authMockedPage, authenticatedPage (구축 완료)
├── flows/                  # 유저 플로우 시나리오 (해피 패스 연속 시나리오)
│   ├── auth.spec.ts
│   ├── dataset-crud.spec.ts
│   ├── pipeline-workflow.spec.ts
│   ├── analytics-workflow.spec.ts
│   ├── ai-insight-workflow.spec.ts
│   └── admin-management.spec.ts
├── pages/                  # 개별 페이지 상세 (유효성 검사, 엣지 케이스)
│   ├── auth/
│   │   ├── login.spec.ts          # (기존 테스트 이동)
│   │   └── signup.spec.ts
│   ├── data/
│   │   ├── category-list.spec.ts
│   │   ├── dataset-list.spec.ts
│   │   ├── dataset-create.spec.ts
│   │   └── dataset-detail.spec.ts
│   ├── pipeline/
│   │   ├── pipeline-list.spec.ts
│   │   └── pipeline-editor.spec.ts
│   ├── analytics/
│   │   ├── query-list.spec.ts
│   │   ├── query-editor.spec.ts
│   │   ├── chart-list.spec.ts
│   │   ├── chart-builder.spec.ts
│   │   ├── dashboard-list.spec.ts
│   │   └── dashboard-editor.spec.ts
│   ├── ai-insights/
│   │   ├── job-list.spec.ts
│   │   ├── job-detail.spec.ts
│   │   ├── template-list.spec.ts
│   │   ├── template-detail.spec.ts
│   │   └── execution-detail.spec.ts
│   └── admin/
│       ├── user-management.spec.ts
│       ├── role-management.spec.ts
│       ├── audit-logs.spec.ts
│       ├── settings.spec.ts
│       └── api-connections.spec.ts
└── login.spec.ts           # (삭제 — pages/auth/login.spec.ts로 이동)
```

## 팩토리 패턴

```typescript
// e2e/factories/dataset.factory.ts
import type { DatasetResponse } from '../../src/types/dataset';

/** 데이터셋 모킹 데이터 생성 — 기본값 제공, overrides로 커스터마이징 */
export function createMockDataset(overrides?: Partial<DatasetResponse>): DatasetResponse {
  return {
    id: 1,
    name: '테스트 데이터셋',
    description: '테스트용 설명',
    categoryId: 1,
    categoryName: '테스트 카테고리',
    tableName: 'test_dataset_1',
    rowCount: 100,
    status: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  };
}

/** 빈 목록 응답 생성 */
export function createMockDatasetPage(items?: DatasetResponse[]) {
  return {
    content: items ?? [createMockDataset()],
    totalElements: items?.length ?? 1,
    totalPages: 1,
    number: 0,
    size: 20,
  };
}
```

## 셀렉터 전략

```typescript
// 우선: 사용자 관점 셀렉터
page.getByRole('button', { name: '생성' })
page.getByLabel('데이터셋 이름')
page.getByText('저장되었습니다')

// 필요한 경우만: data-testid (DAG 노드, 차트 캔버스 등)
page.getByTestId('pipeline-dag-canvas')
page.getByTestId('chart-preview')
```

## 플로우별 테스트 시나리오

### 1. 인증 (flows/auth.spec.ts)

| 시나리오 | 검증 |
|----------|------|
| 회원가입 → 로그인 → 프로필 확인 | 전체 인증 플로우 |
| 로그인 → 프로필 수정 → 확인 | 프로필 업데이트 반영 |
| 로그인 → 로그아웃 → 보호 페이지 접근 차단 | 세션 종료 확인 |

**pages/auth/ 상세:**
- login.spec.ts: 렌더링, 성공, 실패, 유효성 검사, 인증 상태 리다이렉트
- signup.spec.ts: 렌더링, 성공, 중복 아이디, 비밀번호 조건, 유효성 검사

### 2. 데이터셋 관리 (flows/dataset-crud.spec.ts)

| 시나리오 | 검증 |
|----------|------|
| 카테고리 생성 → 데이터셋 생성 → 목록 확인 | CRUD 해피 패스 |
| 데이터셋 생성 → 데이터 임포트 → 컬럼/행 확인 | 임포트 플로우 |
| 데이터셋 수정 → 확인 → 삭제 → 목록 반영 | 수정/삭제 |

**pages/data/ 상세:**
- category-list.spec.ts: 목록, 생성, 수정, 삭제, 빈 상태
- dataset-list.spec.ts: 목록, 검색, 필터, 페이지네이션, 빈 상태
- dataset-create.spec.ts: 폼 유효성, 카테고리 선택, 중복 이름 에러
- dataset-detail.spec.ts: 탭 전환(개요/컬럼/데이터/임포트), 컬럼 수정, 데이터 삭제, 서버 에러

### 3. 파이프라인 (flows/pipeline-workflow.spec.ts)

| 시나리오 | 검증 |
|----------|------|
| 파이프라인 생성 → SQL 스텝 추가 → 저장 | 기본 생성 |
| 스텝 연결 → 실행 → 결과 확인 | 실행 플로우 |
| 트리거 설정 → 스케줄 확인 | 자동 실행 설정 |

**pages/pipeline/ 상세:**
- pipeline-list.spec.ts: 목록, 생성 버튼, 삭제, 빈 상태
- pipeline-editor.spec.ts: DAG 캔버스, 스텝 추가/삭제/연결, SQL/Python/API 에디터, 유효성 검사, 실행 상태 폴링

### 4. 분석 (flows/analytics-workflow.spec.ts)

| 시나리오 | 검증 |
|----------|------|
| 쿼리 작성 → 실행 → 결과 확인 | 쿼리 플로우 |
| 쿼리 결과 → 차트 생성 → 대시보드 배치 | 분석 전체 플로우 |

**pages/analytics/ 상세:**
- query-list.spec.ts: 목록, 검색, 삭제
- query-editor.spec.ts: SQL 에디터, 실행, 결과 테이블, 에러 표시, 저장
- chart-list.spec.ts: 목록, 필터, 삭제
- chart-builder.spec.ts: 차트 타입 선택, 축 설정, 프리뷰, 저장
- dashboard-list.spec.ts: 목록, 생성, 삭제
- dashboard-editor.spec.ts: 위젯 추가/제거, 레이아웃 드래그, 저장

### 5. AI 인사이트 (flows/ai-insight-workflow.spec.ts)

| 시나리오 | 검증 |
|----------|------|
| 템플릿 생성 → 작업 생성 → 실행 → 리포트 확인 | 전체 플로우 |
| 작업 스케줄 설정 → 실행 이력 확인 | 반복 실행 |

**pages/ai-insights/ 상세:**
- job-list.spec.ts: 목록, 상태 필터, 삭제
- job-detail.spec.ts: 생성 폼, 데이터셋 선택, 스케줄 설정, 유효성 검사
- template-list.spec.ts: 목록, 검색, 삭제
- template-detail.spec.ts: 섹션 편집, 프롬프트 설정, 저장
- execution-detail.spec.ts: 실행 상태, 로그, 리포트 뷰어

### 6. 관리자 (flows/admin-management.spec.ts)

| 시나리오 | 검증 |
|----------|------|
| 사용자 목록 → 역할 변경 → 확인 | 사용자 관리 |
| 역할 생성 → 권한 설정 → 사용자 할당 | 역할 관리 |
| API 연결 생성 → 테스트 → 확인 | 외부 연결 |

**pages/admin/ 상세:**
- user-management.spec.ts: 목록, 검색, 활성화/비활성화, 역할 변경
- role-management.spec.ts: 목록, 생성, 권한 편집, 삭제 (시스템 역할 보호)
- audit-logs.spec.ts: 목록, 필터, 페이지네이션
- settings.spec.ts: 설정 조회, 변경, 저장
- api-connections.spec.ts: 목록, 생성 폼, 연결 테스트, 수정, 삭제

## 공통 엣지 케이스 (모든 페이지에 적용)

| 케이스 | 검증 |
|--------|------|
| 서버 에러 (500) | 에러 토스트/메시지 표시 |
| 네트워크 에러 | 에러 처리 |
| 빈 데이터 | 빈 상태 UI 표시 |
| 권한 없는 접근 | 403 → 적절한 안내 |
| 로딩 상태 | 스피너/스켈레톤 표시 |
