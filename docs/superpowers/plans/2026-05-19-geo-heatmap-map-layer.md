# 지리적 히트맵 MAP 차트 레이어 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Analytics의 MAP 차트에 sub-mode 토글로 maplibre native heatmap 레이어(좌표 밀도 그라데이션)를 추가한다.

**Architecture:** `ChartConfig`에 `mapDisplayMode` / `weightColumn` 필드를 추가해 외부 ChartType은 그대로 유지. `MapChartView`가 모드에 따라 기존 `GeoJsonLayer`(points) 또는 신규 `HeatmapLayer`(maplibre native heatmap layer)를 분기 마운트. `AxisConfigPanel`에 표시 모드 `Tabs`를 추가하고 모드별로 컬럼 필드를 노출.

**Tech Stack:** React 19 + TypeScript, maplibre-gl, shadcn/ui Tabs, TanStack Query, Playwright E2E.

**Spec:** `docs/superpowers/specs/2026-05-19-geo-heatmap-map-layer-design.md`

---

## File Structure

수정:

- `apps/firehub-web/src/types/analytics.ts` — `ChartConfig`에 `mapDisplayMode`, `weightColumn` 추가.
- `apps/firehub-web/src/components/analytics/MapChartView.tsx` — 모드 분기, 행수 경고 배지.
- `apps/firehub-web/src/components/analytics/AxisConfigPanel.tsx` — 표시 모드 Tabs + heatmap 필드.

신규:

- `apps/firehub-web/src/components/map/HeatmapLayer.tsx` — maplibre heatmap layer 마운트/언마운트/업데이트.
- `apps/firehub-web/e2e/pages/analytics/chart-builder-map-heatmap.spec.ts` — sub-mode E2E.

`apps/firehub-web/src/lib/geo-utils.ts` 은 변경 불필요 — `toFeatureCollection` 이 이미 geometry 컬럼을 제외한 모든 행 값을 `feature.properties` 에 포함시키므로 weight 컬럼 값이 자동 보존된다.

---

## Task 1: ChartConfig 타입 확장

**Files:**
- Modify: `apps/firehub-web/src/types/analytics.ts` (around line 115, MAP 관련 필드 인접)

- [ ] **Step 1: 타입 필드 추가**

`apps/firehub-web/src/types/analytics.ts` 의 `ChartConfig` 인터페이스 내 `spatialColumn` / `colorByColumn` 바로 아래에 추가.

```ts
  spatialColumn?: string;    // MAP 차트: GEOMETRY 컬럼명 (필수)
  colorByColumn?: string;    // MAP 차트: 색상 기준 컬럼 (선택, points 모드 전용)

  // MAP 차트 표시 모드 — 'points' (기존 점/폴리곤) vs 'heatmap' (좌표 밀도). 기본 'points' (#119)
  mapDisplayMode?: 'points' | 'heatmap';
  // MAP+heatmap 전용 가중치 컬럼 (numeric, 선택). 미지정 시 균등 가중치 1. (#119)
  weightColumn?: string;
```

- [ ] **Step 2: 타입 컴파일 검증**

Run: `pnpm --filter @smart-fire-hub/firehub-web typecheck`
Expected: PASS (기존 코드가 신규 옵셔널 필드를 참조하지 않으므로 회귀 없음).

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/types/analytics.ts
git commit -m "feat(analytics): ChartConfig에 mapDisplayMode/weightColumn 필드 추가 (refs #119)"
```

---

## Task 2: HeatmapLayer 컴포넌트 — 테스트 우선

**Files:**
- Create: `apps/firehub-web/src/components/map/HeatmapLayer.tsx`
- Create: `apps/firehub-web/src/components/map/HeatmapLayer.test.tsx`

이 컴포넌트는 maplibre `Map` 인스턴스를 props 로 받아 명령형 API(`addSource`/`addLayer`)를 호출한다. Vitest + RTL 환경에서 maplibre 전체를 모킹하는 비용이 크므로, 테스트는 **`Map` 인스턴스를 직접 모킹(jest.fn 스파이)** 하여 호출 시퀀스를 검증한다.

- [ ] **Step 1: 실패하는 테스트 작성**

Create `apps/firehub-web/src/components/map/HeatmapLayer.test.tsx`:

```tsx
/**
 * HeatmapLayer 컴포넌트 단위 테스트
 * maplibre Map 인스턴스를 모킹하여 source/layer mount-update-unmount 라이프사이클 검증.
 */
import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { HeatmapLayer } from './HeatmapLayer';

interface MockMap {
  addSource: ReturnType<typeof vi.fn>;
  addLayer: ReturnType<typeof vi.fn>;
  removeLayer: ReturnType<typeof vi.fn>;
  removeSource: ReturnType<typeof vi.fn>;
  getLayer: ReturnType<typeof vi.fn>;
  getSource: ReturnType<typeof vi.fn>;
  setPaintProperty: ReturnType<typeof vi.fn>;
  on: ReturnType<typeof vi.fn>;
  off: ReturnType<typeof vi.fn>;
  isStyleLoaded: ReturnType<typeof vi.fn>;
}

function createMockMap(): MockMap {
  const sources = new Map<string, unknown>();
  const layers = new Map<string, unknown>();
  return {
    addSource: vi.fn((id: string, src: unknown) => { sources.set(id, src); }),
    addLayer: vi.fn((lyr: { id: string }) => { layers.set(lyr.id, lyr); }),
    removeLayer: vi.fn((id: string) => { layers.delete(id); }),
    removeSource: vi.fn((id: string) => { sources.delete(id); }),
    getLayer: vi.fn((id: string) => layers.get(id)),
    getSource: vi.fn((id: string) => sources.get(id)),
    setPaintProperty: vi.fn(),
    on: vi.fn(),
    off: vi.fn(),
    isStyleLoaded: vi.fn(() => true),
  };
}

const emptyFc = { type: 'FeatureCollection' as const, features: [] };

describe('HeatmapLayer', () => {
  it('마운트 시 source 와 heatmap layer 를 추가한다', () => {
    const map = createMockMap();
    render(<HeatmapLayer map={map as never} data={emptyFc} />);

    expect(map.addSource).toHaveBeenCalledWith('heatmap-src', expect.objectContaining({
      type: 'geojson',
    }));
    expect(map.addLayer).toHaveBeenCalledWith(expect.objectContaining({
      id: 'heatmap-lyr',
      type: 'heatmap',
      source: 'heatmap-src',
    }));
  });

  it('weightProperty 미지정 시 heatmap-weight 가 상수 1 이다', () => {
    const map = createMockMap();
    render(<HeatmapLayer map={map as never} data={emptyFc} />);

    const layerArg = map.addLayer.mock.calls[0][0] as {
      paint: Record<string, unknown>;
    };
    expect(layerArg.paint['heatmap-weight']).toBe(1);
  });

  it('weightProperty 지정 시 heatmap-weight 가 to-number expression 이다', () => {
    const map = createMockMap();
    render(<HeatmapLayer map={map as never} data={emptyFc} weightProperty="cnt" />);

    const layerArg = map.addLayer.mock.calls[0][0] as {
      paint: Record<string, unknown>;
    };
    expect(layerArg.paint['heatmap-weight']).toEqual([
      'to-number',
      ['get', 'cnt'],
      0,
    ]);
  });

  it('언마운트 시 layer 와 source 를 제거한다', () => {
    const map = createMockMap();
    const { unmount } = render(<HeatmapLayer map={map as never} data={emptyFc} />);
    unmount();

    expect(map.removeLayer).toHaveBeenCalledWith('heatmap-lyr');
    expect(map.removeSource).toHaveBeenCalledWith('heatmap-src');
  });
});
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-web exec vitest run src/components/map/HeatmapLayer.test.tsx`
Expected: FAIL with "Cannot find module './HeatmapLayer'" 또는 import 에러.

- [ ] **Step 3: HeatmapLayer 구현**

Create `apps/firehub-web/src/components/map/HeatmapLayer.tsx`:

```tsx
import type maplibregl from 'maplibre-gl';
import { useEffect } from 'react';

import type { GeoJsonFeatureCollection } from '@/types/dataset';

/**
 * maplibre native heatmap layer 마운트 컴포넌트.
 *
 * - source id: 'heatmap-src', layer id: 'heatmap-lyr' (MapView 당 1개 가정).
 * - weightProperty 가 있으면 ['to-number', ['get', col], 0] 으로 가중치 컬럼 값을
 *   숫자로 강제하여 NULL/문자열 행은 0 으로 처리.
 * - 데이터/가중치 변경 시 source.setData 또는 setPaintProperty 로 in-place 갱신해
 *   불필요한 add/remove 를 피한다.
 * - 언마운트 시 layer → source 순서로 제거.
 */
interface HeatmapLayerProps {
  map: maplibregl.Map;
  data: GeoJsonFeatureCollection;
  weightProperty?: string;
}

const SOURCE_ID = 'heatmap-src';
const LAYER_ID = 'heatmap-lyr';

function buildWeightExpression(weightProperty?: string): unknown {
  if (!weightProperty) return 1;
  return ['to-number', ['get', weightProperty], 0];
}

function buildPaint(weightProperty?: string): Record<string, unknown> {
  return {
    'heatmap-weight': buildWeightExpression(weightProperty),
    // 줌이 깊어질수록 intensity 증가
    'heatmap-intensity': [
      'interpolate', ['linear'], ['zoom'],
      0, 1,
      15, 3,
    ],
    // 0 (투명) → 청 → 녹 → 황 → 적
    'heatmap-color': [
      'interpolate', ['linear'], ['heatmap-density'],
      0, 'rgba(33,102,172,0)',
      0.2, 'rgba(103,169,207,0.6)',
      0.4, 'rgba(209,229,240,0.7)',
      0.6, 'rgba(253,219,199,0.8)',
      0.8, 'rgba(239,138,98,0.9)',
      1, 'rgba(178,24,43,1)',
    ],
    // 줌이 깊어질수록 픽셀 반경 확대
    'heatmap-radius': [
      'interpolate', ['linear'], ['zoom'],
      0, 4,
      15, 30,
    ],
    'heatmap-opacity': 0.85,
  };
}

export function HeatmapLayer({ map, data, weightProperty }: HeatmapLayerProps) {
  // mount: source + layer 추가, unmount: 제거
  useEffect(() => {
    const ensureMounted = () => {
      if (!map.getSource(SOURCE_ID)) {
        map.addSource(SOURCE_ID, { type: 'geojson', data });
      }
      if (!map.getLayer(LAYER_ID)) {
        map.addLayer({
          id: LAYER_ID,
          type: 'heatmap',
          source: SOURCE_ID,
          paint: buildPaint(weightProperty),
        });
      }
    };

    ensureMounted();
    // 테마/스타일 재로드 시 layer 가 초기화되므로 재마운트 (#258 패턴)
    map.on('styledata', ensureMounted);

    return () => {
      map.off('styledata', ensureMounted);
      if (map.getLayer(LAYER_ID)) map.removeLayer(LAYER_ID);
      if (map.getSource(SOURCE_ID)) map.removeSource(SOURCE_ID);
    };
    // mount/unmount 1회 — data/weightProperty 변경은 별도 effect 에서 in-place 갱신
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map]);

  // data 변경 시 source 만 갱신
  useEffect(() => {
    const src = map.getSource(SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
    if (src && typeof src.setData === 'function') {
      src.setData(data as never);
    }
  }, [map, data]);

  // weightProperty 변경 시 paint 만 갱신
  useEffect(() => {
    if (!map.getLayer(LAYER_ID)) return;
    map.setPaintProperty(LAYER_ID, 'heatmap-weight', buildWeightExpression(weightProperty));
  }, [map, weightProperty]);

  return null;
}
```

- [ ] **Step 4: 테스트 재실행 — 통과 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-web exec vitest run src/components/map/HeatmapLayer.test.tsx`
Expected: PASS — 4 tests.

- [ ] **Step 5: 타입체크 + lint**

Run: `pnpm --filter @smart-fire-hub/firehub-web typecheck && pnpm --filter @smart-fire-hub/firehub-web lint`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-web/src/components/map/HeatmapLayer.tsx apps/firehub-web/src/components/map/HeatmapLayer.test.tsx
git commit -m "feat(map): maplibre native heatmap layer 컴포넌트 추가 (refs #119)"
```

---

## Task 3: MapChartView 분기 + 행수 경고 배지

**Files:**
- Modify: `apps/firehub-web/src/components/analytics/MapChartView.tsx`

- [ ] **Step 1: import 와 상수 추가**

파일 상단 import 블록에 `HeatmapLayer` 추가:

```tsx
import { HeatmapLayer } from '@/components/map/HeatmapLayer';
```

파일 상단(컴포넌트 외부)에 상수 추가:

```tsx
// heatmap 모드에서 행 수가 이 값을 초과하면 inline 경고 배지 노출. 렌더링은 시도. (#119 Risk)
const HEATMAP_ROW_WARN = 50_000;
```

- [ ] **Step 2: 모드 분기 + 경고 배지 적용**

`MapChartView` 컴포넌트의 return 블록(현재 `return ( <div ... > <MapView .../> {mapInstance && ( <> <GeoJsonLayer .../> <FeaturePopup .../> </> )} </div> )` 부분)을 다음으로 교체.

```tsx
  const isHeatmap = config.mapDisplayMode === 'heatmap';
  const showRowWarning = isHeatmap && data.length > HEATMAP_ROW_WARN;

  return (
    <div className="relative rounded-lg overflow-hidden" style={{ height: height ?? '100%' }}>
      <MapView className="w-full h-full" onMapReady={setMapInstance} />
      {mapInstance && (
        isHeatmap ? (
          <HeatmapLayer
            map={mapInstance}
            data={featureCollection}
            weightProperty={config.weightColumn}
          />
        ) : (
          <>
            <GeoJsonLayer
              map={mapInstance}
              data={featureCollection}
              onFeatureClick={setSelectedFeature}
            />
            <FeaturePopup
              map={mapInstance}
              feature={selectedFeature}
              onClose={() => setSelectedFeature(null)}
            />
          </>
        )
      )}
      {showRowWarning && (
        <div
          className="absolute top-2 right-2 z-10 rounded-md bg-yellow-100/90 text-yellow-900 px-2 py-1 text-xs shadow"
          role="status"
          aria-live="polite"
          data-testid="heatmap-row-warning"
        >
          데이터 {data.length.toLocaleString()}행 — 렌더링 성능 저하 가능
        </div>
      )}
    </div>
  );
```

- [ ] **Step 3: 타입체크**

Run: `pnpm --filter @smart-fire-hub/firehub-web typecheck`
Expected: PASS.

- [ ] **Step 4: 기존 MAP E2E 회귀 실행**

Run: `pnpm --filter @smart-fire-hub/firehub-web exec playwright test e2e/pages/analytics/chart-builder-map.spec.ts --project=chromium`
Expected: PASS (points 모드 회귀 없음 — `mapDisplayMode` 미지정 = `'points'`).

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/MapChartView.tsx
git commit -m "feat(analytics): MapChartView heatmap sub-mode 분기 + 행수 경고 (refs #119)"
```

---

## Task 4: AxisConfigPanel — 표시 모드 토글 + heatmap 필드

**Files:**
- Modify: `apps/firehub-web/src/components/analytics/AxisConfigPanel.tsx`

shadcn `Tabs` 컴포넌트(`apps/firehub-web/src/components/ui/tabs.tsx`)가 이미 존재하므로 신규 의존성 없음.

- [ ] **Step 1: import 에 Tabs 추가**

파일 상단 import 블록에 추가:

```tsx
import { Tabs, TabsList, TabsTrigger } from '../ui/tabs';
```

- [ ] **Step 2: MAP 분기 블록 교체**

`if (isMap)` 블록(현재 30~82행) 전체를 다음으로 교체:

```tsx
  if (isMap) {
    const mode = config.mapDisplayMode ?? 'points';
    return (
      <div className="space-y-4">
        {/* 표시 모드 — 점 / 히트맵 토글 (#119) */}
        <div className="space-y-1.5">
          <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
            표시 모드
          </Label>
          <Tabs
            value={mode}
            onValueChange={(next) =>
              update({
                mapDisplayMode: next as 'points' | 'heatmap',
                // 모드 전환 시 반대 모드 전용 필드 초기화
                weightColumn: next === 'points' ? undefined : config.weightColumn,
                colorByColumn: next === 'heatmap' ? undefined : config.colorByColumn,
              })
            }
          >
            <TabsList className="h-8">
              <TabsTrigger value="points" className="text-xs">점</TabsTrigger>
              <TabsTrigger value="heatmap" className="text-xs">히트맵</TabsTrigger>
            </TabsList>
          </Tabs>
        </div>

        {/* 공간 컬럼 (필수, 공통) */}
        <div className="space-y-1.5">
          <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
            공간 컬럼
          </Label>
          <Select
            value={config.spatialColumn || NO_COLUMN}
            onValueChange={(v) => update({ spatialColumn: v === NO_COLUMN ? undefined : v })}
          >
            <SelectTrigger className="h-8 text-sm">
              <SelectValue placeholder="컬럼 선택" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NO_COLUMN}>선택 안 함</SelectItem>
              {columns.map((col) => (
                <SelectItem key={col} value={col}>
                  {col}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {/* points 모드: 색상 기준 */}
        {mode === 'points' && (
          <div className="space-y-1.5">
            <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
              색상 기준 (선택사항)
            </Label>
            <Select
              value={config.colorByColumn || NO_COLUMN}
              onValueChange={(v) => update({ colorByColumn: v === NO_COLUMN ? undefined : v })}
            >
              <SelectTrigger className="h-8 text-sm">
                <SelectValue placeholder="선택 안 함" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={NO_COLUMN}>선택 안 함</SelectItem>
                {columns
                  .filter((col) => col !== config.spatialColumn)
                  .map((col) => (
                    <SelectItem key={col} value={col}>
                      {col}
                    </SelectItem>
                  ))}
              </SelectContent>
            </Select>
          </div>
        )}

        {/* heatmap 모드: 가중치 컬럼 */}
        {mode === 'heatmap' && (
          <div className="space-y-1.5">
            <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
              가중치 컬럼 (선택사항)
            </Label>
            <Select
              value={config.weightColumn || NO_COLUMN}
              onValueChange={(v) => update({ weightColumn: v === NO_COLUMN ? undefined : v })}
            >
              <SelectTrigger className="h-8 text-sm" aria-label="가중치 컬럼">
                <SelectValue placeholder="없음 — 균등 가중치" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={NO_COLUMN}>없음 — 균등 가중치</SelectItem>
                {columns
                  .filter((col) => col !== config.spatialColumn)
                  .map((col) => (
                    <SelectItem key={col} value={col}>
                      {col}
                    </SelectItem>
                  ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              값이 클수록 해당 위치의 밀도 기여가 커집니다.
            </p>
          </div>
        )}
      </div>
    );
  }
```

> 참고: 가중치 컬럼은 사양상 numeric only 이지만 `columns: string[]` 으로 타입 정보가 없어 클라이언트에서 필터링이 불가능하다. 사용자가 비수치 컬럼을 고르더라도 `to-number` expression 의 fallback `0` 으로 안전. (UX 보조로 spatial 자기 자신만 제외)

- [ ] **Step 3: 타입체크 + lint**

Run: `pnpm --filter @smart-fire-hub/firehub-web typecheck && pnpm --filter @smart-fire-hub/firehub-web lint`
Expected: PASS.

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/components/analytics/AxisConfigPanel.tsx
git commit -m "feat(analytics): AxisConfigPanel MAP 표시 모드 토글 + 가중치 컬럼 (refs #119)"
```

---

## Task 5: E2E 테스트 — 신규 spec

**Files:**
- Create: `apps/firehub-web/e2e/pages/analytics/chart-builder-map-heatmap.spec.ts`

기존 `chart-builder-map.spec.ts` 의 fixture 패턴 재사용. payload 캡처는 `route.request().postDataJSON()`.

- [ ] **Step 1: spec 파일 작성**

```ts
/**
 * 차트 빌더 — MAP 히트맵 sub-mode E2E (#119)
 *
 * - 표시 모드 토글 노출/기본값
 * - heatmap 선택 시 colorByColumn → weightColumn 필드 스왑
 * - 저장 payload 에 mapDisplayMode/weightColumn 포함
 * - 50k 행 초과 시 경고 배지 노출
 */
import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const baseGeoResult = createQueryResult({
  columns: ['id', 'name', 'cnt', 'geom'],
  rows: [
    { id: 1, name: 'A', cnt: 3, geom: { type: 'Point', coordinates: [127.0, 37.5] } },
    { id: 2, name: 'B', cnt: 7, geom: { type: 'Point', coordinates: [128.0, 37.6] } },
  ],
  totalRows: 2,
});

test.describe('차트 빌더 — MAP 히트맵 sub-mode', () => {
  test('표시 모드 토글이 노출되고 기본은 점', async ({ authenticatedPage: page }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', baseGeoResult);

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await page.getByRole('button', { name: '지도' }).click();

    await expect(page.getByRole('tab', { name: '점' })).toHaveAttribute('data-state', 'active');
    await expect(page.getByRole('tab', { name: '히트맵' })).toBeVisible();
  });

  test('히트맵 선택 시 colorByColumn 이 가중치 컬럼 셀렉트로 교체된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', baseGeoResult);

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await page.getByRole('button', { name: '지도' }).click();

    await expect(page.getByText('색상 기준 (선택사항)')).toBeVisible();
    await page.getByRole('tab', { name: '히트맵' }).click();

    await expect(page.getByText('색상 기준 (선택사항)')).toHaveCount(0);
    await expect(page.getByText('가중치 컬럼 (선택사항)')).toBeVisible();
  });

  test('저장 payload 에 mapDisplayMode 와 weightColumn 이 포함된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', baseGeoResult);

    let captured: Record<string, unknown> | undefined;
    await page.route('**/api/v1/analytics/charts', (route) => {
      if (route.request().method() === 'POST') {
        captured = route.request().postDataJSON() as Record<string, unknown>;
        return route.fulfill({
          status: 200,
          body: JSON.stringify({ id: 99, name: 'h', chartType: 'MAP', config: captured.config }),
        });
      }
      return route.continue();
    });

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await page.getByRole('button', { name: '지도' }).click();

    // 공간 컬럼 선택
    await page.getByRole('combobox', { name: /공간 컬럼|컬럼 선택/i }).first().click();
    await page.getByRole('option', { name: 'geom' }).click();

    // 히트맵 모드 진입 + 가중치 cnt 선택
    await page.getByRole('tab', { name: '히트맵' }).click();
    await page.getByRole('combobox', { name: '가중치 컬럼' }).click();
    await page.getByRole('option', { name: 'cnt' }).click();

    await page.getByLabel(/이름/).fill('히트맵 차트');
    await page.getByRole('button', { name: /저장/ }).click();

    await expect.poll(() => captured).toBeDefined();
    const config = (captured as { config: Record<string, unknown> }).config;
    expect(config).toMatchObject({
      spatialColumn: 'geom',
      mapDisplayMode: 'heatmap',
      weightColumn: 'cnt',
    });
    // colorByColumn 은 heatmap 모드에서 제거되어 있어야 한다
    expect(config.colorByColumn).toBeUndefined();
  });

  test('행 수가 5만을 초과하면 경고 배지가 노출된다', async ({ authenticatedPage: page }) => {
    const bigRows = Array.from({ length: 50_001 }, (_, i) => ({
      id: i,
      name: `p${i}`,
      cnt: 1,
      geom: { type: 'Point', coordinates: [127 + (i % 100) * 0.01, 37 + (i % 100) * 0.01] },
    }));
    const bigResult = createQueryResult({
      columns: ['id', 'name', 'cnt', 'geom'],
      rows: bigRows,
      totalRows: bigRows.length,
    });

    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', bigResult);

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await page.getByRole('button', { name: '지도' }).click();

    await page.getByRole('combobox', { name: /공간 컬럼|컬럼 선택/i }).first().click();
    await page.getByRole('option', { name: 'geom' }).click();
    await page.getByRole('tab', { name: '히트맵' }).click();

    await expect(page.getByTestId('heatmap-row-warning')).toBeVisible();
    await expect(page.getByTestId('heatmap-row-warning')).toContainText('50,001');
  });
});
```

- [ ] **Step 2: tsconfig.e2e 타입체크**

Run: `pnpm --filter @smart-fire-hub/firehub-web exec tsc -p tsconfig.e2e.json --noEmit`
Expected: PASS.

- [ ] **Step 3: 신규 spec 실행**

Run: `pnpm --filter @smart-fire-hub/firehub-web exec playwright test e2e/pages/analytics/chart-builder-map-heatmap.spec.ts --project=chromium`
Expected: PASS — 4 tests.

> 만약 셀렉터 충돌이 발생하면(`공간 컬럼` Select 의 trigger 가 placeholder 텍스트만 가짐), `setupNewChartBuilderMocks` 에서 노출된 selector 패턴 또는 가까운 `getByText('공간 컬럼').locator('..')` 컨텍스트로 좁히는 미세 조정만 가하고 검증 자체는 유지한다.

- [ ] **Step 4: 회귀 — 기존 MAP spec 통과**

Run: `pnpm --filter @smart-fire-hub/firehub-web exec playwright test e2e/pages/analytics/chart-builder-map.spec.ts --project=chromium`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/e2e/pages/analytics/chart-builder-map-heatmap.spec.ts
git commit -m "test(analytics): MAP 히트맵 sub-mode E2E 추가 (refs #119)"
```

---

## Task 6: 통합 확인 + 최종 커밋

- [ ] **Step 1: 전체 타입체크 + lint**

Run: `pnpm --filter @smart-fire-hub/firehub-web typecheck && pnpm --filter @smart-fire-hub/firehub-web lint`
Expected: PASS.

- [ ] **Step 2: 전체 단위 테스트**

Run: `pnpm --filter @smart-fire-hub/firehub-web exec vitest run`
Expected: PASS (신규 4 + 기존 전부).

- [ ] **Step 3: 분석 도메인 전체 E2E**

Run: `pnpm --filter @smart-fire-hub/firehub-web exec playwright test e2e/pages/analytics --project=chromium`
Expected: PASS.

- [ ] **Step 4: 수동 검증(개발 서버)**

Run: `pnpm dev` (root)
브라우저에서 `http://localhost:5173/analytics/charts/new` 진입 → 저장 쿼리 1 선택 → 쿼리 실행 → 지도 → 표시 모드 `히트맵` 클릭 → 가중치 컬럼 선택. 지도 위에 색상 그라데이션이 표시되는지 확인. 콘솔 에러 없을 것.

- [ ] **Step 5: 이슈 라벨 / 코멘트(선택)**

해당 작업은 PR 머지 단계에서 처리. 본 plan 범위 외.

---

## Out of Scope (별도 이슈)

- 클러스터링(점 군집화).
- 코로플레스(폴리곤 등급 채색).
- 서버측 grid 집계 / 다운샘플링.

이 항목들은 본 plan 의 어떤 task 도 포함하지 않는다.
