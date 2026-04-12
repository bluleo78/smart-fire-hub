# Chart Types Expansion — Design Spec

**날짜**: 2026-04-12  
**상태**: 승인됨  
**범위**: firehub-web (Frontend) + firehub-ai-agent (AI Agent)

---

## 목표

data-analyst 에이전트 및 대시보드에서 사용할 수 있는 차트 타입을 8종 → 17종으로 확장한다.
다양한 데이터 분석 패턴(분포, 이상치, 패턴, 계층, 전환, 다차원)을 시각화할 수 있도록 한다.

---

## 추가 차트 종류 (9종)

| 차트 | 구현 방식 | 주요 용도 |
|------|---------|---------|
| HISTOGRAM | Recharts BarChart + bin 계산 | 수치 분포 |
| BOX PLOT | Recharts ComposedChart + 커스텀 Shape | 이상치 탐지, IQR |
| HEATMAP | `@nivo/heatmap` | 시간대·요일 패턴 |
| TREEMAP | Recharts Treemap (공식) | 계층형 비율 |
| FUNNEL | Recharts FunnelChart (공식) | 단계별 전환율 |
| RADAR | Recharts RadarChart (공식) | 다차원 비교 |
| WATERFALL | Recharts ComposedChart | 누적 증감 |
| GAUGE | Recharts RadialBarChart + 커스텀 SVG | 단일 KPI |
| CANDLESTICK | Recharts Bar + ErrorBar | 수치 범위 변동 |

---

## 라이브러리 전략

- **Recharts**: 기존 8종 + 신규 8종 (HEATMAP 제외 전부)
- **@nivo/heatmap**: HEATMAP 1종 전용
- **근거**: Recharts 공식 지원 차트(TREEMAP, FUNNEL, RADAR)와 커스텀 구현 가능한 차트는 Recharts로 통일. HEATMAP만 Nivo 추가 — 추가 의존성 최소화
- **향후 교체 용이성**: 추상화 레이어로 보장 (아래 참조)

---

## 컴포넌트 아키텍처

### 공통 인터페이스 (계약)

```typescript
// apps/firehub-web/src/components/analytics/chart-view-props.ts

export interface ChartViewProps {
  data: Record<string, unknown>[];
  config: ChartConfig;
  theme: ChartTheme;
  height?: number;
}

export interface ChartTheme {
  colors: string[];          // CSS 변수 기반 색상 배열
  background: string;
  textColor: string;
  gridColor: string;
  tooltipBackground: string;
}
```

모든 차트 컴포넌트는 `ChartViewProps`를 props 타입으로 사용한다.
상위 코드(`ChartRenderer`)는 라이브러리를 직접 알지 못한다.

### 테마 훅

```typescript
// apps/firehub-web/src/components/analytics/useChartTheme.ts

// CSS 변수(디자인 토큰) → ChartTheme 변환
// Recharts, Nivo 모두 이 훅으로 테마를 주입받아 다크모드 일관성 보장
export function useChartTheme(): ChartTheme
```

### 디렉토리 구조

```
apps/firehub-web/src/components/analytics/
├── chart-view-props.ts              # 공통 인터페이스 (계약)
├── useChartTheme.ts                 # CSS 변수 → ChartTheme 훅
├── ChartRenderer.tsx                # switch-case 라우터 (수정)
├── ChartTypeSelector.tsx            # 아이콘 선택기 (수정)
│
├── recharts/                        # Recharts 구현체
│   ├── BarChartView.tsx             # (기존 이동)
│   ├── LineChartView.tsx            # (기존 이동)
│   ├── AreaChartView.tsx            # (기존 이동)
│   ├── PieChartView.tsx             # (기존 이동)
│   ├── ScatterChartView.tsx         # (기존 이동)
│   ├── MapChartView.tsx             # (기존 이동)
│   ├── HistogramChartView.tsx       # 신규
│   ├── BoxPlotChartView.tsx         # 신규
│   ├── TreemapChartView.tsx         # 신규
│   ├── FunnelChartView.tsx          # 신규
│   ├── RadarChartView.tsx           # 신규
│   ├── WaterfallChartView.tsx       # 신규
│   ├── GaugeChartView.tsx           # 신규
│   └── CandlestickChartView.tsx     # 신규
│
└── nivo/                            # Nivo 구현체
    └── HeatmapChartView.tsx         # 신규 (@nivo/heatmap)
```

> **교체 시나리오**: 특정 차트를 다른 라이브러리로 교체 시 해당 구현체 파일만 재작성 + ChartRenderer import 1줄 변경. 상위 코드 무변경.

---

## ChartConfig 확장

기존 `ChartConfig` 인터페이스에 optional 필드 추가. DB는 JSONB 저장이므로 마이그레이션 불필요.

```typescript
export interface ChartConfig {
  // 기존 필드 (변경 없음)
  xAxis: string;
  yAxis: string[];
  groupBy?: string;
  colors?: string[];
  showLegend?: boolean;
  showGrid?: boolean;
  xAxisLabel?: string;
  yAxisLabel?: string;
  stacked?: boolean;
  spatialColumn?: string;
  colorByColumn?: string;

  // 신규 optional 필드
  bins?: number;          // HISTOGRAM: 구간 수 (기본 20)
  valueColumn?: string;   // HEATMAP: 셀 색상 기준 컬럼
  min?: number;           // GAUGE: 최솟값 (기본 0)
  max?: number;           // GAUGE: 최댓값 (기본 100)
  target?: number;        // GAUGE: 목표값 (선택)
  open?: string;          // CANDLESTICK: 시가 컬럼명
  high?: string;          // CANDLESTICK: 고가 컬럼명
  low?: string;           // CANDLESTICK: 저가 컬럼명
  close?: string;         // CANDLESTICK: 종가 컬럼명
}
```

---

## AI 에이전트 업데이트

### 파일 변경 목록

| 파일 | 변경 내용 |
|------|---------|
| `apps/firehub-ai-agent/src/mcp/api-client/analytics-api.ts` | ChartType union에 9종 추가 |
| `apps/firehub-ai-agent/src/mcp/tools/analytics-tools.ts` | Zod enum 확장, show_chart 도구 schema 업데이트 |
| `apps/firehub-ai-agent/src/agent/subagents/data-analyst/rules.md` | 차트 선택 기준 테이블 확장 |
| `apps/firehub-ai-agent/src/agent/system-prompt.ts` | show_chart 도구 설명 업데이트 |

### 차트 자동 추천 기준 추가

| 분석 결과 패턴 | 권장 타입 |
|-------------|---------|
| 단일 수치 컬럼, 분포 확인 목적 | `HISTOGRAM` |
| 그룹별 min/q1/median/q3/max 통계 | `BOX PLOT` |
| 2개 카테고리 × 수치 교차표 | `HEATMAP` |
| 계층 구조 + 크기(비율) | `TREEMAP` |
| 단계명 + 순차 감소 수치 | `FUNNEL` |
| 여러 카테고리 × 여러 지표 | `RADAR` |
| 카테고리 + 증감 수치(양수/음수 혼재) | `WATERFALL` |
| 단일 퍼센트/달성률 | `GAUGE` |
| 날짜 + open/high/low/close 4컬럼 | `CANDLESTICK` |

---

## 구현 배치 계획

### Batch 1 — data-analyst 에이전트 즉시 활용 (우선)
- HISTOGRAM
- BOX PLOT
- HEATMAP

### Batch 2 — 일반 분석 확장
- TREEMAP
- FUNNEL
- RADAR
- WATERFALL

### Batch 3 — 특수 목적
- GAUGE
- CANDLESTICK

각 배치는 독립적으로 배포 가능하다.

---

## 테스트 전략

- 각 신규 차트 컴포넌트: 데이터 정상 렌더링 + 빈 데이터 처리 단위 테스트
- ChartRenderer: 새 ChartType enum 값 라우팅 테스트
- AI 에이전트: 새 chartType 값 Zod 검증 테스트 (기존 패턴 동일)
- Playwright E2E: ChartBuilderPage에서 신규 차트 타입 생성 → 저장 → 대시보드 렌더링

---

## 범위 외 (이번 작업에 포함하지 않음)

- 기존 8종 컴포넌트의 recharts/ 디렉토리 이동은 Batch 1과 함께 진행
- 3D 차트, 지리통계 차트 등 고급 시각화
- 차트 애니메이션 고도화
