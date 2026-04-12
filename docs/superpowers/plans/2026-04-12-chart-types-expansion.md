# Chart Types Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 8종 차트에 9종(HISTOGRAM, BOX PLOT, HEATMAP, TREEMAP, FUNNEL, RADAR, WATERFALL, GAUGE, CANDLESTICK)을 추가하여 data-analyst 에이전트가 다양한 데이터 분석 패턴을 시각화할 수 있게 한다.

**Architecture:** `ChartViewProps` 공통 인터페이스로 신규 차트 컴포넌트를 추상화한다. 신규 Recharts 구현체는 `components/analytics/recharts/`, Nivo 구현체는 `components/analytics/nivo/` 에 배치한다. 기존 컴포넌트는 현 위치 유지. `ChartRenderer.tsx` switch-case에 9종 추가.

**Tech Stack:** Recharts, @nivo/heatmap, React 19, TypeScript, Playwright

---

## 파일 변경 목록

### 신규 생성
- `apps/firehub-web/src/components/analytics/chart-view-props.ts` — 공통 인터페이스
- `apps/firehub-web/src/components/analytics/recharts/HistogramChartView.tsx`
- `apps/firehub-web/src/components/analytics/recharts/BoxPlotChartView.tsx`
- `apps/firehub-web/src/components/analytics/recharts/TreemapChartView.tsx`
- `apps/firehub-web/src/components/analytics/recharts/FunnelChartView.tsx`
- `apps/firehub-web/src/components/analytics/recharts/RadarChartView.tsx`
- `apps/firehub-web/src/components/analytics/recharts/WaterfallChartView.tsx`
- `apps/firehub-web/src/components/analytics/recharts/GaugeChartView.tsx`
- `apps/firehub-web/src/components/analytics/recharts/CandlestickChartView.tsx`
- `apps/firehub-web/src/components/analytics/nivo/HeatmapChartView.tsx`

### 수정
- `apps/firehub-web/src/types/analytics.ts` — ChartType 9종 추가, ChartConfig optional 필드 추가
- `apps/firehub-web/src/components/analytics/ChartRenderer.tsx` — switch-case 9종 추가
- `apps/firehub-web/src/components/analytics/ChartTypeSelector.tsx` — 아이콘 9개 추가
- `apps/firehub-web/src/pages/analytics/ChartBuilderPage.tsx` — 자동 추천 로직 확장
- `apps/firehub-ai-agent/src/mcp/api-client/analytics-api.ts` — ChartType 9종 추가
- `apps/firehub-ai-agent/src/mcp/tools/analytics-tools.ts` — Zod enum 확장
- `apps/firehub-ai-agent/src/agent/subagents/data-analyst/rules.md` — 차트 선택 기준 확장
- `apps/firehub-ai-agent/src/agent/system-prompt.ts` — show_chart 설명 업데이트

---

## Task 1: 공통 인터페이스 + 타입 확장

**Files:**
- Create: `apps/firehub-web/src/components/analytics/chart-view-props.ts`
- Modify: `apps/firehub-web/src/types/analytics.ts`

- [ ] **Step 1: ChartViewProps 파일 생성**

```typescript
// apps/firehub-web/src/components/analytics/chart-view-props.ts
// 모든 신규 차트 컴포넌트가 구현해야 하는 공통 props 계약.
// 라이브러리 교체 시 이 인터페이스만 유지하면 ChartRenderer 수정 불필요.
import type { ChartConfig } from '../../types/analytics';

export interface ChartViewProps {
  data: Record<string, unknown>[];
  config: ChartConfig;
  height?: number; // undefined이면 fillParent 모드 (h-full)
}
```

- [ ] **Step 2: ChartType union 확장**

`apps/firehub-web/src/types/analytics.ts` 90번째 줄 수정:

```typescript
export type ChartType =
  | 'BAR' | 'LINE' | 'PIE' | 'AREA' | 'SCATTER' | 'DONUT' | 'TABLE' | 'MAP'
  | 'HISTOGRAM' | 'BOXPLOT' | 'HEATMAP' | 'TREEMAP' | 'FUNNEL'
  | 'RADAR' | 'WATERFALL' | 'GAUGE' | 'CANDLESTICK';
```

- [ ] **Step 3: ChartConfig optional 필드 추가**

`apps/firehub-web/src/types/analytics.ts` ChartConfig 인터페이스에 추가:

```typescript
export interface ChartConfig {
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
  // HISTOGRAM: 구간 수 (기본 20)
  bins?: number;
  // HEATMAP: 셀 색상 기준 컬럼 (xAxis=행, yAxis[0]=열)
  valueColumn?: string;
  // GAUGE: 값 범위 및 목표
  min?: number;
  max?: number;
  target?: number;
  // CANDLESTICK: 시가/고가/저가/종가 컬럼명
  open?: string;
  high?: string;
  low?: string;
  close?: string;
}
```

- [ ] **Step 4: 빌드 확인**

```bash
cd apps/firehub-web && pnpm typecheck
```

Expected: 에러 없음 (ChartRenderer default case가 새 타입을 처리함)

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/chart-view-props.ts \
        apps/firehub-web/src/types/analytics.ts
git commit -m "feat(web): ChartType 9종 추가 + ChartConfig optional 필드 확장"
```

---

## Task 2: @nivo/heatmap 설치

**Files:**
- Modify: `apps/firehub-web/package.json` (pnpm이 자동 수정)

- [ ] **Step 1: 패키지 설치**

```bash
cd apps/firehub-web && pnpm add @nivo/heatmap @nivo/core
```

- [ ] **Step 2: 타입 확인**

```bash
pnpm typecheck
```

Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/package.json pnpm-lock.yaml
git commit -m "feat(web): @nivo/heatmap 패키지 추가"
```

---

## Task 3: HISTOGRAM

**Files:**
- Create: `apps/firehub-web/src/components/analytics/recharts/HistogramChartView.tsx`

- [ ] **Step 1: 컴포넌트 작성**

```tsx
// apps/firehub-web/src/components/analytics/recharts/HistogramChartView.tsx
// 연속 수치 데이터를 N개 구간(bin)으로 집계하여 빈도 막대 차트로 표시.
// config.xAxis = 수치 컬럼명, config.bins = 구간 수 (기본 20)
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import type { ChartViewProps } from '../chart-view-props';
import { TOOLTIP_CONTENT_STYLE, BAR_CURSOR_STYLE } from '../chart-styles';

/** 수치 배열을 N개 구간으로 집계 */
function binValues(values: number[], bins: number) {
  if (values.length === 0) return [];
  const min = Math.min(...values);
  const max = Math.max(...values);
  if (min === max) return [{ label: String(min), count: values.length }];
  const size = (max - min) / bins;
  const result = Array.from({ length: bins }, (_, i) => ({
    label: (min + i * size).toFixed(1),
    count: 0,
  }));
  values.forEach(v => {
    const idx = Math.min(Math.floor((v - min) / size), bins - 1);
    result[idx].count++;
  });
  return result;
}

export function HistogramChartView({ data, config, height = 300 }: ChartViewProps) {
  const bins = config.bins ?? 20;
  const values = data
    .map(d => Number(d[config.xAxis]))
    .filter(v => !isNaN(v));
  const binnedData = binValues(values, bins);

  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <BarChart data={binnedData} barCategoryGap={1}>
        <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
        <XAxis
          dataKey="label"
          tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }}
          label={config.xAxisLabel ? { value: config.xAxisLabel, position: 'insideBottom', offset: -4, fontSize: 11 } : undefined}
        />
        <YAxis
          tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }}
          label={config.yAxisLabel ? { value: config.yAxisLabel, angle: -90, position: 'insideLeft', fontSize: 11 } : undefined}
        />
        <Tooltip
          contentStyle={TOOLTIP_CONTENT_STYLE}
          cursor={BAR_CURSOR_STYLE}
          formatter={(v: number) => [v, '빈도']}
        />
        <Bar dataKey="count" fill="hsl(var(--chart-1, 220 70% 50%))" radius={[2, 2, 0, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}
```

- [ ] **Step 2: 타입 확인**

```bash
cd apps/firehub-web && pnpm typecheck
```

Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/recharts/HistogramChartView.tsx
git commit -m "feat(web): HistogramChartView — Recharts BarChart 기반 빈도 분포 차트"
```

---

## Task 4: BOX PLOT

**Files:**
- Create: `apps/firehub-web/src/components/analytics/recharts/BoxPlotChartView.tsx`

- [ ] **Step 1: 컴포넌트 작성**

데이터 형식: SQL에서 `MIN`, `PERCENTILE_CONT(0.25)`, `PERCENTILE_CONT(0.5)`, `PERCENTILE_CONT(0.75)`, `MAX` 로 사전 집계된 행.
예: `[{ category: 'Group A', min: 10, q1: 25, median: 40, q3: 60, max: 80 }]`

```tsx
// apps/firehub-web/src/components/analytics/recharts/BoxPlotChartView.tsx
// 카테고리별 통계(min/q1/median/q3/max)를 Box Plot으로 표시.
// ResizeObserver로 컨테이너 크기를 감지하고 SVG를 직접 렌더링한다.
// config.xAxis = 카테고리 컬럼명
// 데이터에 min, q1, median, q3, max 컬럼이 있어야 함
import { useEffect, useRef, useState } from 'react';
import type { ChartViewProps } from '../chart-view-props';

const MARGIN = { top: 20, right: 20, bottom: 40, left: 50 };
const BOX_WIDTH_RATIO = 0.4; // 카테고리 폭 대비 박스 폭 비율

interface BoxStat {
  category: string;
  min: number;
  q1: number;
  median: number;
  q3: number;
  max: number;
}

export function BoxPlotChartView({ data, config, height = 300 }: ChartViewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [dims, setDims] = useState({ width: 0, height: 0 });

  // 컨테이너 크기 감지
  useEffect(() => {
    if (!containerRef.current) return;
    const obs = new ResizeObserver(entries => {
      const { width, height: h } = entries[0].contentRect;
      setDims({ width, height: h });
    });
    obs.observe(containerRef.current);
    return () => obs.disconnect();
  }, []);

  const stats: BoxStat[] = data.map(d => ({
    category: String(d[config.xAxis] ?? ''),
    min: Number(d.min ?? d.q0 ?? 0),
    q1: Number(d.q1 ?? 0),
    median: Number(d.median ?? d.q2 ?? 0),
    q3: Number(d.q3 ?? 0),
    max: Number(d.max ?? d.q4 ?? 0),
  }));

  const containerH = height ?? dims.height;
  const plotW = dims.width - MARGIN.left - MARGIN.right;
  const plotH = containerH - MARGIN.top - MARGIN.bottom;

  // Y 스케일
  const allVals = stats.flatMap(s => [s.min, s.max]);
  const yMin = Math.min(...allVals);
  const yMax = Math.max(...allVals);
  const yRange = yMax - yMin || 1;
  const toY = (v: number) => plotH - ((v - yMin) / yRange) * plotH;

  // X 스케일 (카테고리)
  const step = plotW / (stats.length || 1);
  const boxW = step * BOX_WIDTH_RATIO;
  const toX = (i: number) => step * i + step / 2;

  // Y축 눈금
  const yTicks = Array.from({ length: 5 }, (_, i) => yMin + (yRange * i) / 4);

  return (
    <div ref={containerRef} style={{ width: '100%', height: containerH }}>
      {dims.width > 0 && (
        <svg width={dims.width} height={containerH}>
          <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
            {/* Y축 */}
            {yTicks.map(t => (
              <g key={t} transform={`translate(0,${toY(t)})`}>
                <line x1={0} x2={plotW} stroke="hsl(var(--border))" strokeDasharray="3 3" />
                <text x={-8} textAnchor="end" fontSize={10} fill="hsl(var(--muted-foreground))" dy="0.32em">
                  {t.toFixed(1)}
                </text>
              </g>
            ))}
            {/* 박스 플롯 */}
            {stats.map((s, i) => {
              const cx = toX(i);
              const x1 = cx - boxW / 2;
              const x2 = cx + boxW / 2;
              return (
                <g key={s.category}>
                  {/* 위 수염 (median → max) */}
                  <line x1={cx} y1={toY(s.q3)} x2={cx} y2={toY(s.max)}
                    stroke="hsl(var(--chart-1, 220 70% 50%))" strokeWidth={1.5} />
                  <line x1={x1 + boxW * 0.2} y1={toY(s.max)} x2={x2 - boxW * 0.2} y2={toY(s.max)}
                    stroke="hsl(var(--chart-1, 220 70% 50%))" strokeWidth={1.5} />
                  {/* 아래 수염 (min → q1) */}
                  <line x1={cx} y1={toY(s.q1)} x2={cx} y2={toY(s.min)}
                    stroke="hsl(var(--chart-1, 220 70% 50%))" strokeWidth={1.5} />
                  <line x1={x1 + boxW * 0.2} y1={toY(s.min)} x2={x2 - boxW * 0.2} y2={toY(s.min)}
                    stroke="hsl(var(--chart-1, 220 70% 50%))" strokeWidth={1.5} />
                  {/* IQR 박스 (q1 ~ q3) */}
                  <rect
                    x={x1} y={toY(s.q3)}
                    width={boxW} height={Math.max(toY(s.q1) - toY(s.q3), 1)}
                    fill="hsl(var(--chart-1, 220 70% 50%))" fillOpacity={0.25}
                    stroke="hsl(var(--chart-1, 220 70% 50%))" strokeWidth={1.5}
                  />
                  {/* 중앙값 선 */}
                  <line x1={x1} y1={toY(s.median)} x2={x2} y2={toY(s.median)}
                    stroke="hsl(var(--chart-1, 220 70% 50%))" strokeWidth={2.5} />
                  {/* X축 라벨 */}
                  <text x={cx} y={plotH + 20} textAnchor="middle" fontSize={11}
                    fill="hsl(var(--muted-foreground))">{s.category}</text>
                </g>
              );
            })}
          </g>
        </svg>
      )}
    </div>
  );
}
```

- [ ] **Step 2: 타입 확인**

```bash
cd apps/firehub-web && pnpm typecheck
```

Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/recharts/BoxPlotChartView.tsx
git commit -m "feat(web): BoxPlotChartView — 커스텀 SVG 박스 플롯 (min/q1/median/q3/max)"
```

---

## Task 5: HEATMAP (Nivo)

**Files:**
- Create: `apps/firehub-web/src/components/analytics/nivo/HeatmapChartView.tsx`

- [ ] **Step 1: 컴포넌트 작성**

데이터 형식: `[{ row: 'Mon', col: '00시', value: 42 }, ...]`
config.xAxis = 행 컬럼, config.yAxis[0] = 열 컬럼, config.valueColumn = 수치 컬럼

```tsx
// apps/firehub-web/src/components/analytics/nivo/HeatmapChartView.tsx
// 2차원 교차 데이터를 색상 농도로 표현. 시간대×요일 패턴 분석에 적합.
// @nivo/heatmap을 사용하며 CSS 변수로 다크모드를 동기화한다.
import { ResponsiveHeatMap } from '@nivo/heatmap';
import { useMemo } from 'react';
import type { ChartViewProps } from '../chart-view-props';

/** CSS 변수를 읽어 Nivo theme 객체를 생성 */
function useNivoTheme() {
  // CSS 변수 직접 읽기 — next-themes의 다크/라이트 전환에 따라 재계산
  const textColor = 'hsl(var(--foreground))';
  const gridColor = 'hsl(var(--border))';
  const tooltipBg = 'hsl(var(--popover))';
  return useMemo(() => ({
    text: { fontSize: 11, fill: textColor },
    axis: {
      ticks: { text: { fill: textColor, fontSize: 10 } },
      legend: { text: { fill: textColor } },
    },
    tooltip: {
      container: {
        background: tooltipBg,
        color: textColor,
        fontSize: 12,
        border: `1px solid ${gridColor}`,
        borderRadius: 6,
      },
    },
  }), []);
}

/** 평탄한 행 데이터 → Nivo HeatMap 형식 변환 */
function toNivoFormat(
  data: Record<string, unknown>[],
  rowKey: string,
  colKey: string,
  valueKey: string,
) {
  const rowMap = new Map<string, Map<string, number>>();
  data.forEach(d => {
    const row = String(d[rowKey] ?? '');
    const col = String(d[colKey] ?? '');
    const val = Number(d[valueKey] ?? 0);
    if (!rowMap.has(row)) rowMap.set(row, new Map());
    rowMap.get(row)!.set(col, val);
  });
  return Array.from(rowMap.entries()).map(([id, cols]) => ({
    id,
    data: Array.from(cols.entries()).map(([x, y]) => ({ x, y })),
  }));
}

export function HeatmapChartView({ data, config, height = 300 }: ChartViewProps) {
  const theme = useNivoTheme();
  const valueKey = config.valueColumn ?? config.yAxis[0] ?? 'value';
  const nivoData = useMemo(
    () => toNivoFormat(data, config.xAxis, config.yAxis[0] ?? '', valueKey),
    [data, config.xAxis, config.yAxis, valueKey],
  );

  if (nivoData.length === 0) {
    return (
      <div className="flex items-center justify-center h-full text-muted-foreground text-sm">
        데이터가 없습니다.
      </div>
    );
  }

  return (
    <div style={{ height: height ?? '100%' }}>
      <ResponsiveHeatMap
        data={nivoData}
        theme={theme}
        margin={{ top: 40, right: 60, bottom: 40, left: 80 }}
        colors={{ type: 'sequential', scheme: 'blues' }}
        axisTop={null}
        axisLeft={{ tickSize: 5, tickPadding: 5 }}
        axisBottom={{ tickSize: 5, tickPadding: 5 }}
        labelTextColor={{ from: 'color', modifiers: [['darker', 2]] }}
        animate={false}
      />
    </div>
  );
}
```

- [ ] **Step 2: 타입 확인**

```bash
cd apps/firehub-web && pnpm typecheck
```

Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/nivo/HeatmapChartView.tsx
git commit -m "feat(web): HeatmapChartView — @nivo/heatmap 기반 2차원 밀도 히트맵"
```

---

## Task 6: ChartRenderer + ChartTypeSelector — Batch 1 연결

**Files:**
- Modify: `apps/firehub-web/src/components/analytics/ChartRenderer.tsx`
- Modify: `apps/firehub-web/src/components/analytics/ChartTypeSelector.tsx`

- [ ] **Step 1: ChartRenderer에 import 추가**

`apps/firehub-web/src/components/analytics/ChartRenderer.tsx` 상단 import에 추가:

```typescript
import { HistogramChartView } from './recharts/HistogramChartView';
import { BoxPlotChartView } from './recharts/BoxPlotChartView';
import { HeatmapChartView } from './nivo/HeatmapChartView';
```

- [ ] **Step 2: switch-case에 Batch 1 추가**

`ChartRenderer.tsx` switch 블록 내 `case 'MAP':` 앞에 삽입:

```typescript
case 'HISTOGRAM':
  chart = <HistogramChartView data={data} config={config} height={fillParent ? undefined : height} />;
  break;
case 'BOXPLOT':
  chart = <BoxPlotChartView data={data} config={config} height={fillParent ? undefined : height} />;
  break;
case 'HEATMAP':
  chart = <HeatmapChartView data={data} config={config} height={fillParent ? undefined : height} />;
  break;
```

- [ ] **Step 3: ChartTypeSelector에 Batch 1 아이콘 추가**

`apps/firehub-web/src/components/analytics/ChartTypeSelector.tsx` 파일을 읽고 기존 패턴 파악 후, HISTOGRAM/BOXPLOT/HEATMAP 항목 추가. 기존 아이콘 배열에 추가:

```typescript
// Lucide 아이콘: BarChart2(histogram), BoxSelect(boxplot), Grid3X3(heatmap)
{ type: 'HISTOGRAM', label: '히스토그램', icon: BarChart2 },
{ type: 'BOXPLOT', label: '박스 플롯', icon: BoxSelect },
{ type: 'HEATMAP', label: '히트맵', icon: Grid3X3 },
```

(ChartTypeSelector.tsx 실제 구조 확인 후 기존 패턴에 맞게 삽입)

- [ ] **Step 4: 빌드 확인**

```bash
cd apps/firehub-web && pnpm build
```

Expected: 빌드 성공

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/ChartRenderer.tsx \
        apps/firehub-web/src/components/analytics/ChartTypeSelector.tsx
git commit -m "feat(web): ChartRenderer/Selector Batch 1 연결 — HISTOGRAM, BOXPLOT, HEATMAP"
```

---

## Task 7: AI 에이전트 업데이트 (전체 9종)

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/api-client/analytics-api.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/tools/analytics-tools.ts`
- Modify: `apps/firehub-ai-agent/src/agent/subagents/data-analyst/rules.md`
- Modify: `apps/firehub-ai-agent/src/agent/system-prompt.ts`

- [ ] **Step 1: analytics-api.ts ChartType 확장**

`apps/firehub-ai-agent/src/mcp/api-client/analytics-api.ts` 76번째 줄:

```typescript
export type ChartType =
  | 'BAR' | 'LINE' | 'PIE' | 'AREA' | 'SCATTER' | 'DONUT' | 'TABLE' | 'MAP'
  | 'HISTOGRAM' | 'BOXPLOT' | 'HEATMAP' | 'TREEMAP' | 'FUNNEL'
  | 'RADAR' | 'WATERFALL' | 'GAUGE' | 'CANDLESTICK';
```

- [ ] **Step 2: analytics-tools.ts Zod enum 확장**

`apps/firehub-ai-agent/src/mcp/tools/analytics-tools.ts` 내 `z.enum([...])` 두 곳 모두 업데이트:

```typescript
// show_chart 도구 schema (103번째 줄 근처)
chartType: z.enum([
  'BAR', 'LINE', 'PIE', 'AREA', 'SCATTER', 'DONUT', 'TABLE', 'MAP',
  'HISTOGRAM', 'BOXPLOT', 'HEATMAP', 'TREEMAP', 'FUNNEL',
  'RADAR', 'WATERFALL', 'GAUGE', 'CANDLESTICK',
]).describe('차트 타입'),

// create_chart 도구 schema (214번째 줄 근처) 및 chartTypeSchema (237번째 줄):
const chartTypeSchema = z.enum([
  'BAR', 'LINE', 'PIE', 'AREA', 'SCATTER', 'DONUT', 'TABLE', 'MAP',
  'HISTOGRAM', 'BOXPLOT', 'HEATMAP', 'TREEMAP', 'FUNNEL',
  'RADAR', 'WATERFALL', 'GAUGE', 'CANDLESTICK',
]);
```

- [ ] **Step 3: data-analyst/rules.md 차트 선택 기준 확장**

`apps/firehub-ai-agent/src/agent/subagents/data-analyst/rules.md` 섹션 3을 아래로 교체:

```markdown
## 3. 차트 타입 선택 기준

| 분석 목적 | 데이터 형태 | 권장 타입 |
|----------|-----------|---------|
| 시간 추이 | 날짜 + 수치 | `LINE` 또는 `AREA` |
| 카테고리 비교 | 문자열 + 수치 | `BAR` |
| 비율·구성 | 카테고리 + 비율 | `DONUT` (범주 5개 이하) |
| 두 수치 관계 | 수치 + 수치 | `SCATTER` |
| 지리 분포 | geom + 수치 | `MAP` |
| 순위 | 정렬된 카테고리 + 수치 | `BAR` (가로) |
| **수치 분포** | 단일 수치 컬럼, 행 다수 | `HISTOGRAM` |
| **이상치·IQR** | 카테고리 + min/q1/median/q3/max | `BOXPLOT` |
| **2차원 패턴** | 행 카테고리 × 열 카테고리 × 수치 | `HEATMAP` |
| **계층 비율** | name + size (계층) | `TREEMAP` |
| **전환율** | 단계명 + 감소 수치 | `FUNNEL` |
| **다차원 비교** | 카테고리 × 여러 지표 | `RADAR` |
| **누적 증감** | 카테고리 + 양수/음수 수치 | `WATERFALL` |
| **단일 KPI** | 단일 퍼센트/달성률 | `GAUGE` |
| **OHLC 시계열** | 날짜 + open/high/low/close | `CANDLESTICK` |

추가 기준:
- 범주가 **6개 이상**이면 상위 5개 + "기타"로 집계한다.
- 시계열 데이터가 **90일 이상**이면 `AREA`가 `LINE`보다 가독성이 좋다.
- BOXPLOT 사용 시: SQL에서 `PERCENTILE_CONT(0.25)`, `PERCENTILE_CONT(0.5)`, `PERCENTILE_CONT(0.75)` 로 사전 집계 필요.
- HEATMAP 사용 시: config에 `valueColumn`(색상 기준 컬럼명) 명시 필요.
- CANDLESTICK 사용 시: config에 `open`, `high`, `low`, `close` 컬럼명 명시 필요.
```

- [ ] **Step 4: system-prompt.ts show_chart 설명 업데이트**

`apps/firehub-ai-agent/src/agent/system-prompt.ts` 내 show_chart 도구 설명 중 chartType 열거 부분에 신규 9종 추가:

```
HISTOGRAM(분포), BOXPLOT(이상치), HEATMAP(패턴), TREEMAP(계층비율),
FUNNEL(전환율), RADAR(다차원비교), WATERFALL(증감), GAUGE(단일KPI), CANDLESTICK(OHLC)
```

- [ ] **Step 5: 에이전트 테스트 실행**

```bash
cd apps/firehub-ai-agent && pnpm test
```

Expected: 기존 테스트 모두 통과 (새 타입이 Zod enum에 추가됐으므로 invalid type 테스트 여전히 통과)

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-ai-agent/src/mcp/api-client/analytics-api.ts \
        apps/firehub-ai-agent/src/mcp/tools/analytics-tools.ts \
        apps/firehub-ai-agent/src/agent/subagents/data-analyst/rules.md
git commit -m "feat(ai-agent): ChartType 9종 추가 + data-analyst 차트 선택 기준 확장"
```

---

## Task 8: TREEMAP

**Files:**
- Create: `apps/firehub-web/src/components/analytics/recharts/TreemapChartView.tsx`

- [ ] **Step 1: 컴포넌트 작성**

```tsx
// apps/firehub-web/src/components/analytics/recharts/TreemapChartView.tsx
// 계층형 데이터를 크기 비율로 표현. config.xAxis = 라벨 컬럼, config.yAxis[0] = 크기 컬럼.
import { Treemap, ResponsiveContainer, Tooltip } from 'recharts';
import type { ChartViewProps } from '../chart-view-props';
import { TOOLTIP_CONTENT_STYLE } from '../chart-styles';

const COLORS = [
  'hsl(220 70% 50%)', 'hsl(160 60% 45%)', 'hsl(30 80% 55%)',
  'hsl(280 65% 60%)', 'hsl(340 75% 55%)', 'hsl(200 70% 50%)',
  'hsl(100 55% 45%)', 'hsl(50 85% 50%)',
];

// 커스텀 콘텐츠: 셀에 라벨 표시
const CustomContent = (props: any) => {
  const { x, y, width, height, name, depth } = props;
  if (depth === 0 || width < 40 || height < 20) return null;
  return (
    <g>
      <rect x={x} y={y} width={width} height={height}
        fill={COLORS[props.index % COLORS.length]} fillOpacity={0.85}
        stroke="hsl(var(--background))" strokeWidth={2} rx={3} />
      {width > 60 && height > 30 && (
        <text x={x + width / 2} y={y + height / 2}
          textAnchor="middle" dominantBaseline="middle"
          fontSize={Math.min(14, width / 6)} fill="#fff" fontWeight={500}>
          {name}
        </text>
      )}
    </g>
  );
};

export function TreemapChartView({ data, config, height = 300 }: ChartViewProps) {
  const treeData = data.map(d => ({
    name: String(d[config.xAxis] ?? ''),
    size: Number(d[config.yAxis[0]] ?? 0),
  }));

  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <Treemap
        data={treeData}
        dataKey="size"
        content={<CustomContent />}
        isAnimationActive={false}
      >
        <Tooltip
          contentStyle={TOOLTIP_CONTENT_STYLE}
          formatter={(v: number, _: string, props: any) => [v.toLocaleString(), props.payload?.name]}
        />
      </Treemap>
    </ResponsiveContainer>
  );
}
```

- [ ] **Step 2: 타입 확인**

```bash
cd apps/firehub-web && pnpm typecheck
```

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/recharts/TreemapChartView.tsx
git commit -m "feat(web): TreemapChartView — Recharts Treemap 계층 비율 차트"
```

---

## Task 9: FUNNEL

**Files:**
- Create: `apps/firehub-web/src/components/analytics/recharts/FunnelChartView.tsx`

- [ ] **Step 1: 컴포넌트 작성**

```tsx
// apps/firehub-web/src/components/analytics/recharts/FunnelChartView.tsx
// 단계별 감소 수치를 깔때기 형태로 표현. config.xAxis = 단계명, config.yAxis[0] = 수치.
import { FunnelChart, Funnel, LabelList, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import type { ChartViewProps } from '../chart-view-props';
import { TOOLTIP_CONTENT_STYLE } from '../chart-styles';

const COLORS = [
  'hsl(220 70% 50%)', 'hsl(220 65% 58%)', 'hsl(220 60% 65%)',
  'hsl(220 55% 72%)', 'hsl(220 50% 79%)', 'hsl(220 45% 85%)',
];

export function FunnelChartView({ data, config, height = 300 }: ChartViewProps) {
  const funnelData = data.map((d, i) => ({
    name: String(d[config.xAxis] ?? ''),
    value: Number(d[config.yAxis[0]] ?? 0),
    fill: COLORS[i % COLORS.length],
  }));

  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <FunnelChart>
        <Tooltip contentStyle={TOOLTIP_CONTENT_STYLE} />
        <Funnel dataKey="value" data={funnelData} isAnimationActive={false}>
          {funnelData.map((entry, i) => (
            <Cell key={i} fill={entry.fill} />
          ))}
          <LabelList
            position="center"
            content={({ x, y, width, height: h, value, name }: any) => (
              <text x={x + width / 2} y={y + h / 2} textAnchor="middle"
                dominantBaseline="middle" fontSize={12} fill="#fff" fontWeight={500}>
                {`${name}: ${Number(value).toLocaleString()}`}
              </text>
            )}
          />
        </Funnel>
      </FunnelChart>
    </ResponsiveContainer>
  );
}
```

- [ ] **Step 2: 타입 확인**

```bash
cd apps/firehub-web && pnpm typecheck
```

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/recharts/FunnelChartView.tsx
git commit -m "feat(web): FunnelChartView — Recharts FunnelChart 단계별 전환율 차트"
```

---

## Task 10: RADAR

**Files:**
- Create: `apps/firehub-web/src/components/analytics/recharts/RadarChartView.tsx`

- [ ] **Step 1: 컴포넌트 작성**

```tsx
// apps/firehub-web/src/components/analytics/recharts/RadarChartView.tsx
// 카테고리별 여러 지표를 방사형으로 비교. config.xAxis = 카테고리(라벨), config.yAxis = 수치 컬럼들.
import {
  RadarChart, Radar, PolarGrid, PolarAngleAxis, PolarRadiusAxis,
  Legend, Tooltip, ResponsiveContainer,
} from 'recharts';
import type { ChartViewProps } from '../chart-view-props';
import { TOOLTIP_CONTENT_STYLE } from '../chart-styles';

const COLORS = ['hsl(220 70% 50%)', 'hsl(160 60% 45%)', 'hsl(30 80% 55%)', 'hsl(280 65% 60%)'];

export function RadarChartView({ data, config, height = 300 }: ChartViewProps) {
  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <RadarChart data={data}>
        <PolarGrid stroke="hsl(var(--border))" />
        <PolarAngleAxis
          dataKey={config.xAxis}
          tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }}
        />
        <PolarRadiusAxis
          tick={{ fontSize: 10, fill: 'hsl(var(--muted-foreground))' }}
          axisLine={false}
        />
        <Tooltip contentStyle={TOOLTIP_CONTENT_STYLE} />
        {config.yAxis.map((key, i) => (
          <Radar
            key={key}
            name={key}
            dataKey={key}
            stroke={COLORS[i % COLORS.length]}
            fill={COLORS[i % COLORS.length]}
            fillOpacity={0.2}
          />
        ))}
        {config.yAxis.length > 1 && config.showLegend !== false && (
          <Legend wrapperStyle={{ fontSize: 11 }} />
        )}
      </RadarChart>
    </ResponsiveContainer>
  );
}
```

- [ ] **Step 2: 타입 확인**

```bash
cd apps/firehub-web && pnpm typecheck
```

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/recharts/RadarChartView.tsx
git commit -m "feat(web): RadarChartView — Recharts RadarChart 다차원 비교 차트"
```

---

## Task 11: WATERFALL

**Files:**
- Create: `apps/firehub-web/src/components/analytics/recharts/WaterfallChartView.tsx`

- [ ] **Step 1: 컴포넌트 작성**

양수는 녹색, 음수는 빨간색, 투명 base bar로 부동 효과 구현.

```tsx
// apps/firehub-web/src/components/analytics/recharts/WaterfallChartView.tsx
// 누적 증감을 폭포 형태로 표현. config.xAxis = 카테고리, config.yAxis[0] = 변화량(양수/음수 모두 허용).
// 투명한 base bar + 색상 bar 스택으로 floating bar를 구현한다.
import {
  ComposedChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, Cell, ReferenceLine,
} from 'recharts';
import type { ChartViewProps } from '../chart-view-props';
import { TOOLTIP_CONTENT_STYLE } from '../chart-styles';

interface WaterfallRow {
  name: string;
  base: number;       // 투명 spacer (0 ~ 이전 누적값)
  positive: number;   // 양수 변화량
  negative: number;   // 음수 변화량 (양수로 저장, base 조정)
  isTotal: boolean;
  value: number;      // 표시용 실제값
  cumulative: number;
}

function transformData(data: Record<string, unknown>[], xKey: string, yKey: string): WaterfallRow[] {
  let cumulative = 0;
  return data.map(d => {
    const name = String(d[xKey] ?? '');
    const value = Number(d[yKey] ?? 0);
    const prev = cumulative;
    cumulative += value;
    if (value >= 0) {
      return { name, base: prev, positive: value, negative: 0, isTotal: false, value, cumulative };
    } else {
      return { name, base: cumulative, positive: 0, negative: Math.abs(value), isTotal: false, value, cumulative };
    }
  });
}

export function WaterfallChartView({ data, config, height = 300 }: ChartViewProps) {
  const rows = transformData(data, config.xAxis, config.yAxis[0] ?? '');

  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <ComposedChart data={rows} barCategoryGap="30%">
        <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" vertical={false} />
        <XAxis dataKey="name" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} />
        <YAxis tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} />
        <ReferenceLine y={0} stroke="hsl(var(--border))" />
        <Tooltip
          contentStyle={TOOLTIP_CONTENT_STYLE}
          formatter={(_: unknown, __: string, props: any) => [
            props.payload?.value?.toLocaleString(), config.yAxis[0],
          ]}
        />
        {/* 투명 spacer */}
        <Bar dataKey="base" stackId="wf" fill="transparent" />
        {/* 양수 bar (녹색) */}
        <Bar dataKey="positive" stackId="wf" radius={[2, 2, 0, 0]}>
          {rows.map((_, i) => <Cell key={i} fill="hsl(142 72% 45%)" />)}
        </Bar>
        {/* 음수 bar (빨간색) */}
        <Bar dataKey="negative" stackId="wf" radius={[2, 2, 0, 0]}>
          {rows.map((_, i) => <Cell key={i} fill="hsl(0 72% 55%)" />)}
        </Bar>
      </ComposedChart>
    </ResponsiveContainer>
  );
}
```

- [ ] **Step 2: 타입 확인**

```bash
cd apps/firehub-web && pnpm typecheck
```

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/recharts/WaterfallChartView.tsx
git commit -m "feat(web): WaterfallChartView — Recharts ComposedChart 누적 증감 차트"
```

---

## Task 12: ChartRenderer + ChartTypeSelector — Batch 2 연결

**Files:**
- Modify: `apps/firehub-web/src/components/analytics/ChartRenderer.tsx`
- Modify: `apps/firehub-web/src/components/analytics/ChartTypeSelector.tsx`

- [ ] **Step 1: ChartRenderer import 추가**

```typescript
import { TreemapChartView } from './recharts/TreemapChartView';
import { FunnelChartView } from './recharts/FunnelChartView';
import { RadarChartView } from './recharts/RadarChartView';
import { WaterfallChartView } from './recharts/WaterfallChartView';
```

- [ ] **Step 2: switch-case에 Batch 2 추가**

```typescript
case 'TREEMAP':
  chart = <TreemapChartView data={data} config={config} height={fillParent ? undefined : height} />;
  break;
case 'FUNNEL':
  chart = <FunnelChartView data={data} config={config} height={fillParent ? undefined : height} />;
  break;
case 'RADAR':
  chart = <RadarChartView data={data} config={config} height={fillParent ? undefined : height} />;
  break;
case 'WATERFALL':
  chart = <WaterfallChartView data={data} config={config} height={fillParent ? undefined : height} />;
  break;
```

- [ ] **Step 3: ChartTypeSelector에 Batch 2 아이콘 추가**

```typescript
// Lucide 아이콘: LayoutGrid(treemap), Filter(funnel), Radar(radar), TrendingDown(waterfall)
{ type: 'TREEMAP', label: '트리맵', icon: LayoutGrid },
{ type: 'FUNNEL', label: '퍼널', icon: Filter },
{ type: 'RADAR', label: '레이더', icon: Radar },
{ type: 'WATERFALL', label: '폭포 차트', icon: TrendingDown },
```

- [ ] **Step 4: 빌드 확인**

```bash
cd apps/firehub-web && pnpm build
```

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/ChartRenderer.tsx \
        apps/firehub-web/src/components/analytics/ChartTypeSelector.tsx
git commit -m "feat(web): ChartRenderer/Selector Batch 2 연결 — TREEMAP, FUNNEL, RADAR, WATERFALL"
```

---

## Task 13: GAUGE

**Files:**
- Create: `apps/firehub-web/src/components/analytics/recharts/GaugeChartView.tsx`

- [ ] **Step 1: 컴포넌트 작성**

반원 PieChart + 커스텀 SVG 바늘로 게이지 구현.

```tsx
// apps/firehub-web/src/components/analytics/recharts/GaugeChartView.tsx
// 단일 수치를 반원 게이지로 표현. config.yAxis[0] = 값 컬럼, config.min/max/target = 범위 설정.
import { PieChart, Pie, Cell, ResponsiveContainer } from 'recharts';
import type { ChartViewProps } from '../chart-view-props';

export function GaugeChartView({ data, config, height = 280 }: ChartViewProps) {
  const value = Number(data[0]?.[config.yAxis[0]] ?? 0);
  const min = config.min ?? 0;
  const max = config.max ?? 100;
  const pct = Math.min(Math.max((value - min) / (max - min), 0), 1);

  // 반원 배경 + 값 호
  const backgroundData = [{ value: 100 }];
  const gaugeData = [
    { value: pct * 100 },
    { value: (1 - pct) * 100 },
  ];

  // 바늘 각도: 180° (왼쪽) → 0° (오른쪽)
  const needleAngleDeg = 180 - pct * 180;
  const needleAngleRad = (needleAngleDeg * Math.PI) / 180;

  // 반응형 크기 내 바늘 SVG는 PieChart의 customized prop 대신 컨테이너 외부에 절대 배치
  const containerH = height ?? 280;

  return (
    <div style={{ position: 'relative', height: containerH }}>
      <ResponsiveContainer width="100%" height={containerH}>
        <PieChart>
          {/* 배경 반원 */}
          <Pie
            data={backgroundData}
            cx="50%"
            cy="75%"
            startAngle={180}
            endAngle={0}
            innerRadius="55%"
            outerRadius="75%"
            dataKey="value"
            stroke="none"
          >
            <Cell fill="hsl(var(--muted))" />
          </Pie>
          {/* 값 반원 */}
          <Pie
            data={gaugeData}
            cx="50%"
            cy="75%"
            startAngle={180}
            endAngle={0}
            innerRadius="55%"
            outerRadius="75%"
            dataKey="value"
            stroke="none"
          >
            <Cell fill="hsl(var(--chart-1, 220 70% 50%))" />
            <Cell fill="transparent" />
          </Pie>
        </PieChart>
      </ResponsiveContainer>
      {/* 바늘 + 수치 오버레이 (SVG foreignObject 없이 div 사용) */}
      <svg
        style={{ position: 'absolute', top: 0, left: 0, width: '100%', height: containerH, pointerEvents: 'none' }}
        viewBox={`0 0 200 ${containerH}`}
        preserveAspectRatio="xMidYMid meet"
      >
        {/* 바늘 */}
        <line
          x1={100}
          y1={containerH * 0.75}
          x2={100 + 55 * Math.cos(needleAngleRad)}
          y2={containerH * 0.75 - 55 * Math.sin(needleAngleRad)}
          stroke="hsl(var(--foreground))"
          strokeWidth={2}
          strokeLinecap="round"
        />
        <circle cx={100} cy={containerH * 0.75} r={4} fill="hsl(var(--foreground))" />
        {/* 값 표시 */}
        <text
          x={100} y={containerH * 0.75 + 24}
          textAnchor="middle" fontSize={16} fontWeight={700}
          fill="hsl(var(--foreground))"
        >
          {value.toLocaleString()}
        </text>
        <text
          x={100} y={containerH * 0.75 + 38}
          textAnchor="middle" fontSize={10}
          fill="hsl(var(--muted-foreground))"
        >
          {min} ~ {max}
        </text>
      </svg>
    </div>
  );
}
```

- [ ] **Step 2: 타입 확인**

```bash
cd apps/firehub-web && pnpm typecheck
```

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/recharts/GaugeChartView.tsx
git commit -m "feat(web): GaugeChartView — 반원 PieChart + SVG 바늘 게이지"
```

---

## Task 14: CANDLESTICK

**Files:**
- Create: `apps/firehub-web/src/components/analytics/recharts/CandlestickChartView.tsx`

- [ ] **Step 1: 컴포넌트 작성**

config.xAxis = 날짜/카테고리, config.open/high/low/close = 각 컬럼명.

```tsx
// apps/firehub-web/src/components/analytics/recharts/CandlestickChartView.tsx
// OHLC 데이터를 캔들스틱으로 표시. open>close=빨간색, open<=close=녹색.
// ResizeObserver로 컨테이너 크기 감지 후 커스텀 SVG 렌더링.
import { useEffect, useRef, useState } from 'react';
import type { ChartViewProps } from '../chart-view-props';

const MARGIN = { top: 20, right: 20, bottom: 40, left: 60 };
const CANDLE_RATIO = 0.5;

export function CandlestickChartView({ data, config, height = 300 }: ChartViewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [dims, setDims] = useState({ width: 0, height: 0 });

  useEffect(() => {
    if (!containerRef.current) return;
    const obs = new ResizeObserver(entries => {
      const { width, height: h } = entries[0].contentRect;
      setDims({ width, height: h });
    });
    obs.observe(containerRef.current);
    return () => obs.disconnect();
  }, []);

  const openKey = config.open ?? 'open';
  const highKey = config.high ?? 'high';
  const lowKey = config.low ?? 'low';
  const closeKey = config.close ?? 'close';

  const candles = data.map(d => ({
    label: String(d[config.xAxis] ?? ''),
    open: Number(d[openKey] ?? 0),
    high: Number(d[highKey] ?? 0),
    low: Number(d[lowKey] ?? 0),
    close: Number(d[closeKey] ?? 0),
  }));

  const containerH = height ?? dims.height;
  const plotW = dims.width - MARGIN.left - MARGIN.right;
  const plotH = containerH - MARGIN.top - MARGIN.bottom;

  const allVals = candles.flatMap(c => [c.high, c.low]);
  const yMin = Math.min(...allVals);
  const yMax = Math.max(...allVals);
  const yRange = yMax - yMin || 1;
  const toY = (v: number) => plotH - ((v - yMin) / yRange) * plotH;

  const step = plotW / (candles.length || 1);
  const candleW = step * CANDLE_RATIO;
  const toX = (i: number) => step * i + step / 2;

  const yTicks = Array.from({ length: 5 }, (_, i) => yMin + (yRange * i) / 4);

  return (
    <div ref={containerRef} style={{ width: '100%', height: containerH }}>
      {dims.width > 0 && (
        <svg width={dims.width} height={containerH}>
          <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
            {/* Y축 그리드 + 눈금 */}
            {yTicks.map(t => (
              <g key={t} transform={`translate(0,${toY(t)})`}>
                <line x1={0} x2={plotW} stroke="hsl(var(--border))" strokeDasharray="3 3" />
                <text x={-8} textAnchor="end" fontSize={10} fill="hsl(var(--muted-foreground))" dy="0.32em">
                  {t.toFixed(2)}
                </text>
              </g>
            ))}
            {/* 캔들 */}
            {candles.map((c, i) => {
              const cx = toX(i);
              const bullish = c.close >= c.open;
              const color = bullish ? 'hsl(142 72% 45%)' : 'hsl(0 72% 55%)';
              const bodyTop = toY(Math.max(c.open, c.close));
              const bodyBottom = toY(Math.min(c.open, c.close));
              const bodyH = Math.max(bodyBottom - bodyTop, 1);
              return (
                <g key={i}>
                  {/* 위 꼬리 */}
                  <line x1={cx} y1={toY(c.high)} x2={cx} y2={bodyTop} stroke={color} strokeWidth={1.5} />
                  {/* 아래 꼬리 */}
                  <line x1={cx} y1={bodyBottom} x2={cx} y2={toY(c.low)} stroke={color} strokeWidth={1.5} />
                  {/* 몸통 */}
                  <rect
                    x={cx - candleW / 2} y={bodyTop}
                    width={candleW} height={bodyH}
                    fill={bullish ? color : color}
                    stroke={color}
                    strokeWidth={1}
                    fillOpacity={bullish ? 0.2 : 1}
                  />
                  {/* X 라벨 */}
                  <text x={cx} y={plotH + 20} textAnchor="middle" fontSize={10}
                    fill="hsl(var(--muted-foreground))">{c.label}</text>
                </g>
              );
            })}
          </g>
        </svg>
      )}
    </div>
  );
}
```

- [ ] **Step 2: 타입 확인**

```bash
cd apps/firehub-web && pnpm typecheck
```

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/recharts/CandlestickChartView.tsx
git commit -m "feat(web): CandlestickChartView — 커스텀 SVG OHLC 캔들스틱 차트"
```

---

## Task 15: ChartRenderer + ChartTypeSelector — Batch 3 연결

**Files:**
- Modify: `apps/firehub-web/src/components/analytics/ChartRenderer.tsx`
- Modify: `apps/firehub-web/src/components/analytics/ChartTypeSelector.tsx`

- [ ] **Step 1: ChartRenderer import 추가**

```typescript
import { GaugeChartView } from './recharts/GaugeChartView';
import { CandlestickChartView } from './recharts/CandlestickChartView';
```

- [ ] **Step 2: switch-case에 Batch 3 추가**

```typescript
case 'GAUGE':
  chart = <GaugeChartView data={data} config={config} height={fillParent ? undefined : height} />;
  break;
case 'CANDLESTICK':
  chart = <CandlestickChartView data={data} config={config} height={fillParent ? undefined : height} />;
  break;
```

- [ ] **Step 3: ChartTypeSelector에 Batch 3 아이콘 추가**

```typescript
// Lucide 아이콘: Gauge(gauge), CandlestickChart(candlestick)
{ type: 'GAUGE', label: '게이지', icon: Gauge },
{ type: 'CANDLESTICK', label: '캔들스틱', icon: CandlestickChart },
```

- [ ] **Step 4: 빌드 확인**

```bash
cd apps/firehub-web && pnpm build
```

Expected: 빌드 성공, 경고 없음

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/ChartRenderer.tsx \
        apps/firehub-web/src/components/analytics/ChartTypeSelector.tsx
git commit -m "feat(web): ChartRenderer/Selector Batch 3 연결 — GAUGE, CANDLESTICK"
```

---

## Task 16: ChartBuilderPage 자동 추천 로직 확장

**Files:**
- Modify: `apps/firehub-web/src/pages/analytics/ChartBuilderPage.tsx`

- [ ] **Step 1: 자동 추천 로직 읽기**

`apps/firehub-web/src/pages/analytics/ChartBuilderPage.tsx` 60~95번째 줄 자동 추천 함수 확인 후, 다음 조건을 기존 로직 앞에 삽입:

```typescript
// CANDLESTICK: open/high/low/close 컬럼이 모두 있을 때
const candlestickCols = ['open', 'high', 'low', 'close'];
if (candlestickCols.every(c => columns.map(col => col.toLowerCase()).includes(c))) {
  return 'CANDLESTICK';
}

// HEATMAP: 2개 문자열 컬럼 + 1개 숫자 컬럼
const strCols = columns.filter(c => typeof data[0]?.[c] === 'string');
const numCols = columns.filter(c => typeof data[0]?.[c] === 'number');
if (strCols.length >= 2 && numCols.length >= 1 && columns.length <= 4) {
  return 'HEATMAP';
}

// HISTOGRAM: 단일 수치 컬럼, 행 50개 이상
if (numCols.length === 1 && strCols.length === 0 && data.length >= 50) {
  return 'HISTOGRAM';
}
```

- [ ] **Step 2: 빌드 확인**

```bash
cd apps/firehub-web && pnpm build
```

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/pages/analytics/ChartBuilderPage.tsx
git commit -m "feat(web): ChartBuilderPage 자동 추천 — HISTOGRAM, HEATMAP, CANDLESTICK 감지"
```

---

## Task 17: E2E 테스트

**Files:**
- Create: `apps/firehub-web/e2e/pages/analytics/chart-types-new.spec.ts`

- [ ] **Step 1: 팩토리에 신규 차트 데이터 추가**

`apps/firehub-web/e2e/factories/analytics.ts` (또는 analytics factory 파일) 에 추가:

```typescript
import type { ChartType, ChartConfig } from '@/types/analytics';

export function makeChart(overrides: { chartType?: ChartType; config?: Partial<ChartConfig> } = {}) {
  return {
    id: 1,
    name: '테스트 차트',
    description: null,
    savedQueryId: 1,
    savedQueryName: '테스트 쿼리',
    chartType: overrides.chartType ?? 'BAR',
    config: {
      xAxis: 'category',
      yAxis: ['value'],
      ...overrides.config,
    } satisfies ChartConfig,
    isShared: false,
    createdByName: 'test',
    createdBy: 1,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}
```

- [ ] **Step 2: E2E 테스트 파일 작성**

```typescript
// apps/firehub-web/e2e/pages/analytics/chart-types-new.spec.ts
// 신규 9종 차트 타입이 ChartTypeSelector에 표시되고, 선택 시 ChartRenderer에 렌더링되는지 검증.
import { test, expect } from '../../fixtures/auth.fixture';

const NEW_CHART_TYPES = [
  { type: 'HISTOGRAM', label: '히스토그램' },
  { type: 'BOXPLOT', label: '박스 플롯' },
  { type: 'HEATMAP', label: '히트맵' },
  { type: 'TREEMAP', label: '트리맵' },
  { type: 'FUNNEL', label: '퍼널' },
  { type: 'RADAR', label: '레이더' },
  { type: 'WATERFALL', label: '폭포 차트' },
  { type: 'GAUGE', label: '게이지' },
  { type: 'CANDLESTICK', label: '캔들스틱' },
];

test.describe('신규 차트 타입 — ChartTypeSelector', () => {
  test.beforeEach(async ({ page }) => {
    // saved query mock
    await page.route('**/api/v1/saved-queries/**', route =>
      route.fulfill({
        status: 200,
        body: JSON.stringify({
          id: 1, name: '테스트 쿼리', sqlText: 'SELECT category, value FROM data',
          datasetId: 1, datasetName: 'ds', folder: null, isShared: false,
          createdByName: 'test', createdBy: 1, createdAt: '', updatedAt: '', chartCount: 0, description: null,
        }),
      })
    );
    // query execution mock
    await page.route('**/api/v1/analytics/query', route =>
      route.fulfill({
        status: 200,
        body: JSON.stringify({
          queryType: 'SELECT',
          columns: ['category', 'value'],
          rows: [
            { category: 'A', value: 10 },
            { category: 'B', value: 20 },
            { category: 'C', value: 15 },
          ],
          rowCount: 3,
        }),
      })
    );
  });

  for (const { type, label } of NEW_CHART_TYPES) {
    test(`${label}(${type}) 선택기 버튼이 존재한다`, async ({ page }) => {
      await page.goto('/analytics/charts/new?queryId=1');
      await expect(page.getByRole('button', { name: label })).toBeVisible();
    });

    test(`${label}(${type}) 선택 시 API payload에 chartType이 포함된다`, async ({ page }) => {
      let capturedPayload: { chartType?: string } = {};
      await page.route('**/api/v1/charts', route => {
        if (route.request().method() === 'POST') {
          capturedPayload = route.request().postDataJSON() as { chartType?: string };
          return route.fulfill({ status: 201, body: JSON.stringify({ id: 99 }) });
        }
        return route.continue();
      });

      await page.goto('/analytics/charts/new?queryId=1');
      await page.getByRole('button', { name: label }).click();
      await page.getByRole('button', { name: '저장' }).click();

      expect(capturedPayload.chartType).toBe(type);
    });
  }
});
```

- [ ] **Step 2: E2E 실행**

```bash
cd apps/firehub-web && pnpm test:e2e --project=chromium -- e2e/pages/analytics/chart-types-new.spec.ts
```

Expected: 18개 테스트 통과 (9 타입 × 2 케이스)

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/e2e/pages/analytics/chart-types-new.spec.ts \
        apps/firehub-web/e2e/factories/analytics.ts
git commit -m "test(web/e2e): 신규 차트 9종 — ChartTypeSelector 표시 + API payload 검증"
```

---

## 완료 기준

- [ ] `pnpm build` 성공 (web, ai-agent)
- [ ] `pnpm typecheck` 성공 (web, ai-agent)
- [ ] `pnpm test` 성공 (ai-agent Vitest)
- [ ] E2E 신규 테스트 18개 통과
- [ ] `ChartRenderer.tsx` switch-case에 17종 모두 처리
- [ ] `analytics-tools.ts` Zod enum에 17종 포함
