# Phase 6-1: AI Chat Generative UI — 데이터셋 미리보기 + 테이블

> **날짜**: 2026-03-28
> **스코프**: 위젯 인프라 + 데이터셋 미리보기 + 리치 테이블 + 딥링크 + 쿼리 캐시 갱신
> **접근**: 수직 슬라이스 (위젯 단위 E2E)

---

## 1. 개요

현재 AI 챗은 텍스트 응답 + `show_chart` 인라인 차트만 지원한다. Generative UI는 AI가 인터랙티브 위젯을 챗 안에 직접 렌더링하여 데이터를 즉시 파악하고 클릭으로 이동할 수 있게 한다.

이번 Phase 6-1에서는 가장 빈번한 2개 위젯(데이터셋 미리보기, 테이블)과 공통 인프라를 구축한다.

### 핵심 가치

- "데이터셋 보여줘" → 텍스트 나열이 아닌 클릭 가능한 카드/테이블
- AI 도구 실행 후 메인 UI 자동 갱신 (TanStack Query invalidation)
- 위젯에서 메인 UI로 딥링크 이동 (사이드 패널 챗 유지)

---

## 2. 위젯 인프라

### 2.1 WidgetRegistry

도구 이름 → React 컴포넌트 매핑 테이블. `MessageBubble`의 기존 하드코딩된 `if (cleanName === 'show_chart')` 분기를 Registry 룩업으로 교체한다.

```typescript
// components/ai/widgets/WidgetRegistry.ts

type WidgetEntry = {
  component: React.LazyExoticComponent<React.ComponentType<WidgetProps<any>>>;
  label: string;
  icon: string;
};

const WIDGET_REGISTRY: Record<string, WidgetEntry> = {
  show_chart: {
    component: lazy(() => import('./InlineChartWidgetAdapter')),
    label: '차트 표시',
    icon: '📊',
  },
  show_dataset_preview: {
    component: lazy(() => import('./InlineDatasetWidget')),
    label: '데이터셋 미리보기',
    icon: '📦',
  },
  show_table: {
    component: lazy(() => import('./InlineTableWidget')),
    label: '테이블 표시',
    icon: '📋',
  },
  navigate_to: {
    component: lazy(() => import('./NavigateToWidget')),
    label: '페이지 이동',
    icon: '🔗',
  },
};

export function getWidget(toolName: string): WidgetEntry | undefined {
  const cleanName = toolName.replace(/^mcp__firehub__/, '');
  return WIDGET_REGISTRY[cleanName];
}
```

### 2.2 WidgetProps (공통 인터페이스)

```typescript
// components/ai/widgets/types.ts

interface WidgetProps<T = Record<string, unknown>> {
  input: T;
  onNavigate?: (path: string) => void;
  displayMode: 'side' | 'floating' | 'fullscreen';
}
```

### 2.3 WidgetShell (공통 래퍼)

모든 위젯을 감싸는 카드 프레임.

- **헤더**: 아이콘 + 제목 + 우측 액션 버튼
- `navigateTo` prop이 있으면 "상세 보기 →" 링크 자동 표시
- `displayMode`에 따라 내부 max-height 자동 조절:
  - side / floating: 250px (내부 스크롤)
  - fullscreen: 450px
- 다크 테마 지원 (기존 디자인 토큰 사용)

```typescript
interface WidgetShellProps {
  title: string;
  icon: string;
  subtitle?: string;
  actions?: ReactNode;
  navigateTo?: string;
  onNavigate?: (path: string) => void;
  displayMode: 'side' | 'floating' | 'fullscreen';
  children: ReactNode;
}
```

### 2.4 WidgetErrorBoundary

위젯 렌더링 실패 시 "위젯을 표시할 수 없습니다" 폴백 UI. 기존 `ChartErrorBoundary`를 범용화한다.

### 2.5 InlineChartWidgetAdapter

기존 `InlineChartWidget`은 flat props(`{ sql, chartType, config, columns, rows }`)를 받는다. 새 패턴은 `{ input, onNavigate, displayMode }`를 사용하므로 브릿지 어댑터를 만든다. Registry는 어댑터를 가리킨다.

### 2.6 MessageBubble 리팩터링

기존 하드코딩 → Registry 룩업으로 교체:

```typescript
// 기존
if (cleanName === 'show_chart' && tc.input) {
  return <InlineChartWidget ... />;
}
return <ToolCallDisplay ... />;

// 변경 후
const widget = getWidget(tc.name);
if (widget && tc.input) {
  return (
    <Suspense fallback={<WidgetSkeleton label={widget.label} />}>
      <WidgetErrorBoundary>
        <widget.component input={tc.input} onNavigate={navigate} displayMode={mode} />
      </WidgetErrorBoundary>
    </Suspense>
  );
}
return <ToolCallDisplay ... />;
```

---

## 3. 데이터셋 미리보기 위젯 (show_dataset_preview)

### 3.1 MCP 도구 (AI Agent)

- 도구명: `show_dataset_preview`
- 입력: `{ datasetId: number }`
- **Reference 패턴**: AI는 ID만 전달, 프론트엔드가 직접 API fetch
- 시스템 프롬프트: "데이터셋 정보를 보여줄 때 show_dataset_preview를 사용하라"

### 3.2 InlineDatasetWidget (프론트엔드)

**데이터 Fetch** (2개 API):
- `GET /api/v1/datasets/{id}` — 메타데이터 (이름, 타입, 컬럼 수, 행 수, 수정일)
- `GET /api/v1/datasets/{id}/data?limit=5` — 샘플 데이터 5행

**레이아웃**:
```
┌─────────────────────────────────────────────────┐
│ 📦 소방용수_현황  [SOURCE]         상세 보기 →  │  ← 헤더
├─────────────────────────────────────────────────┤
│ 📐 9개 컬럼  📏 1,247행  📅 2026-03-15 수정    │  ← 메타 행
├─────────────────────────────────────────────────┤
│ name       │ type │ address          │ status   │  ← 샘플 테이블
│ 강남소방서 │ 지상 │ 서울 강남구 ...  │ ● 정상   │
│ 역삼역 2번 │ 지하 │ 서울 강남구 ...  │ ● 점검중 │
│ 삼성동 공원 │ 지상 │ 서울 강남구 ...  │ ● 정상   │
├─────────────────────────────────────────────────┤
│               3 / 1,247건 미리보기              │  ← 푸터
└─────────────────────────────────────────────────┘
```

**모드별 크기**:
- side / floating: max-height 250px, 컬럼 4~5개까지 표시 (나머지 "+N개" 표시)
- fullscreen: max-height 350px, 제한 없음

**딥링크**: "상세 보기 →" 클릭 시 `/datasets/{id}` 이동, 사이드 패널 유지.

---

## 4. 테이블 위젯 (show_table)

### 4.1 MCP 도구 (AI Agent)

- 도구명: `show_table`
- 입력: `{ title?: string, sql: string, columns: string[], rows: Record<string, unknown>[], totalRows: number }`
- **Passthrough 패턴**: 쿼리 결과는 일회성이므로 AI가 데이터를 직접 전달
- 시스템 프롬프트: "데이터를 테이블로 보여줄 때 show_table 사용, 시각화할 때 show_chart 사용"

### 4.2 InlineTableWidget (프론트엔드)

**레이아웃**:
```
┌─────────────────────────────────────────────────┐
│ 📋 쿼리 결과  1,247건    SQL 보기  📥 내보내기  │  ← 헤더
├─────────────────────────────────────────────────┤
│ [name 필터] │ [type 필터] │ [status 필터]       │  ← 컬럼 필터
├─────────────────────────────────────────────────┤
│ name    ↕  │ type   ↕  │ status  ↕             │  ← 정렬 가능 헤더
│ 강남소방서 │ 지상      │ 정상                   │
│ 역삼역 2번 │ 지하      │ 점검중                 │
│ ...        │ ...       │ ...                    │
├─────────────────────────────────────────────────┤
│ 1–50 / 1,247                  ← 이전  다음 →   │  ← 페이지네이션
└─────────────────────────────────────────────────┘
```

**기능**:
- **정렬**: 컬럼 헤더 클릭 시 ↑↓ 토글 (클라이언트 사이드)
- **필터**: 각 컬럼 아래 텍스트 input, 클라이언트 사이드 필터링
- **페이지네이션**: 50행/페이지, "1–50 / 1,247" + 이전/다음 버튼
- **SQL 보기**: 접기/펼치기, syntax-highlighted SQL
- **내보내기**: 현재 필터 적용된 결과를 CSV blob 다운로드 (기존 `downloadBlob` 유틸 활용)

**모드별 크기**:
- side / floating: max-height 250px, 컬럼 4개까지 (나머지 가로 스크롤)
- fullscreen: max-height 450px, 제한 없음

---

## 5. 딥링크 + navigate_to

### 5.1 navigate_to 도구

- AI가 리소스 생성/수정 후 자동 이동에 사용
- 입력: `{ type: 'dataset' | 'pipeline' | 'dashboard', id: number, label: string }`
- 라우팅:
  - `dataset` → `/datasets/{id}`
  - `pipeline` → `/pipelines/{id}`
  - `dashboard` → `/analytics/dashboards/{id}`

### 5.2 NavigateToWidget

- 챗에 작은 링크 카드로 표시: "📎 소방용수_현황 데이터셋으로 이동했습니다"
- 자동으로 `onNavigate(path)` 호출하여 메인 UI 이동
- 전체화면 모드였으면 사이드 모드로 전환 후 이동 (챗 유지)

### 5.3 위젯 내 딥링크

- WidgetShell의 `navigateTo` prop으로 통일
- 클릭 시 React Router `navigate()` + 전체화면이면 사이드 전환
- 모든 위젯의 "상세 보기 →"가 동일한 동작

---

## 6. 쿼리 캐시 자동 갱신

### 6.1 Invalidation 매핑

```typescript
// components/ai/widgets/invalidationMap.ts

const TOOL_INVALIDATION_MAP: Record<string, string[][]> = {
  create_dataset: [['datasets']],
  update_dataset: [['datasets']],
  delete_dataset: [['datasets']],
  create_pipeline: [['pipelines']],
  run_pipeline: [['pipelines']],
  delete_pipeline: [['pipelines']],
  create_chart: [['charts'], ['dashboards']],
  save_chart: [['charts'], ['dashboards']],
};
```

### 6.2 통합 위치

- `useAIChat` 훅에서 SSE `tool_result` 이벤트 수신 시:
  1. 도구 이름으로 매핑 테이블 조회
  2. 매칭되면 `queryClient.invalidateQueries()` 호출
  3. 메인 UI 목록이 자동으로 갱신됨

---

## 7. 파일 구조

```
apps/firehub-web/src/components/ai/widgets/
├── WidgetRegistry.ts          # 도구명 → 컴포넌트 매핑
├── WidgetShell.tsx             # 공통 카드 래퍼
├── WidgetErrorBoundary.tsx     # 에러 폴백
├── WidgetSkeleton.tsx          # 로딩 스켈레톤
├── types.ts                    # WidgetProps 등 공통 타입
├── invalidationMap.ts          # 도구 → 쿼리 키 매핑
├── InlineChartWidgetAdapter.tsx # 기존 차트 어댑터
├── InlineDatasetWidget.tsx     # 데이터셋 미리보기
├── InlineTableWidget.tsx       # 리치 테이블
└── NavigateToWidget.tsx        # 딥링크 이동 카드

apps/firehub-ai-agent/src/tools/
├── show-dataset-preview.ts     # MCP 도구
├── show-table.ts               # MCP 도구
└── navigate-to.ts              # MCP 도구
```

---

## 8. 실행 순서 (수직 슬라이스)

1. **위젯 인프라**: WidgetRegistry + WidgetShell + ErrorBoundary + Skeleton + types + InlineChartWidgetAdapter + MessageBubble 리팩터링
2. **데이터셋 미리보기 E2E**: MCP 도구(show_dataset_preview) → InlineDatasetWidget → 딥링크
3. **테이블 E2E**: MCP 도구(show_table) → InlineTableWidget (정렬/필터/페이지네이션/내보내기)
4. **딥링크 + navigate_to**: NavigateToWidget + 자동 이동 로직
5. **쿼리 캐시 갱신**: invalidationMap + useAIChat 통합
6. **시스템 프롬프트**: 도구 사용 가이드 + TOOL_LABELS 업데이트

---

## 9. 검증 기준

- [ ] WidgetRegistry로 show_chart 기존 동작 유지 (레그레션 없음)
- [ ] "데이터셋 보여줘" → InlineDatasetWidget 렌더링 (메타 + 샘플 5행)
- [ ] "데이터 조회해줘" → InlineTableWidget 렌더링 (정렬/필터/페이지네이션/내보내기)
- [ ] 위젯 "상세 보기 →" 클릭 시 메인 UI 이동 + 사이드 패널 유지
- [ ] navigate_to 자동 이동 동작
- [ ] AI가 create_dataset 실행 후 메인 UI 데이터셋 목록 자동 갱신
- [ ] 사이드/플로팅/전체화면 3모드에서 위젯 크기 자동 조절
- [ ] 위젯 렌더링 실패 시 ErrorBoundary 폴백 표시
- [ ] 빌드 + 타입체크 통과
- [ ] AI Agent 테스트 통과
