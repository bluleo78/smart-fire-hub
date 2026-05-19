# 지리적 히트맵(좌표 밀도) MAP 차트 레이어 — 설계

- Issue: #119
- Date: 2026-05-19
- Status: Draft (approval pending)

## 1. 배경 / 목표

Analytics 모듈의 `MAP` 차트는 현재 GEOMETRY 좌표를 점/폴리곤으로 표시하는 수준이다. 본 작업은 **좌표 밀도를 지도 위에서 색상 그라데이션으로 표현하는 지리적 히트맵**을 동일한 MAP 차트의 하위 모드로 추가한다.

사용자 시나리오:

- ASOS 관측소 좌표와 화재 발생 좌표를 결합하여 "화재 발생 밀도가 높은 지역" 가시화.
- 강원 읍면동 데이터 위에 신고 건수·인구 등 수치 컬럼을 가중치로 시각화.

## 2. 노출 방식 — MAP의 sub-mode 토글

`ChartType`에 신규 타입을 추가하지 않고 **MAP 차트 내부의 "표시 모드 토글"** 로 노출한다.

- 공간 컬럼·렌더러·페이지·팝업 컨텍스트가 공유돼 사용자 학습 비용이 낮음.
- 차트 빌더의 ChartType 아이콘 개수 유지.
- 기존 저장된 MAP 차트는 `mapDisplayMode` 미지정 = `'points'`로 해석되어 하위 호환.

## 3. 데이터 모델 / API

### 3.1 `ChartConfig` 확장 (`apps/firehub-web/src/types/analytics.ts`)

추가 필드 2개:

```ts
mapDisplayMode?: 'points' | 'heatmap'; // MAP 전용, 기본 'points'
weightColumn?: string;                  // MAP+heatmap 전용, numeric, optional
```

의미 한정:

- `colorByColumn` → `points` 모드 전용 (히트맵에서는 무시).
- `weightColumn` → `heatmap` 모드 전용 (points에서는 무시).
- 모드 전환 시 반대 모드의 필드는 `undefined`로 초기화하여 저장 payload 깨끗하게 유지.

### 3.2 Backend

변경 없음. 현재 MAP은 클라이언트 측에서 SELECT 결과를 GeoJSON으로 변환하므로, 가중치 컬럼은 SELECT에 포함되기만 하면 된다. 차트 저장/로드 JSON에 신규 필드 2개가 자동으로 합류한다.

## 4. 렌더링 / 컴포넌트 구조

### 4.1 `MapChartView` 분기 (`apps/firehub-web/src/components/analytics/MapChartView.tsx`)

```text
if mapDisplayMode === 'heatmap':
    mount <HeatmapLayer map data weightProperty={weightColumn}/>
    skip FeaturePopup
else:
    기존 <GeoJsonLayer/> + <FeaturePopup/> 경로 그대로
```

points 모드는 기존 코드 경로 그대로 — 회귀 영향 0.

### 4.2 신규 `HeatmapLayer` (`apps/firehub-web/src/components/map/HeatmapLayer.tsx`)

- Props: `map: maplibregl.Map`, `data: FeatureCollection<Point>`, `weightProperty?: string`.
- mount:
  1. `map.addSource('heatmap-src', { type:'geojson', data })`
  2. `map.addLayer({ id:'heatmap-lyr', type:'heatmap', source:'heatmap-src', paint })`
  3. `heatmap-weight`: `weightProperty` 있으면 `['to-number', ['get', weightProperty], 0]`, 없으면 `1`.
  4. `heatmap-radius`, `heatmap-intensity`, `heatmap-opacity`: 줌 단계별 `interpolate` (maplibre 표준 패턴).
  5. `heatmap-color`: 투명 → 청 → 녹 → 황 → 적 스텝. 다크/라이트 팔레트 2종 준비 후 `useTheme` 구독.
- update (`data` / `weightProperty` 변경): source `setData` 또는 layer `setPaintProperty`로 in-place 갱신, 불필요한 add/remove 회피.
- unmount: `removeLayer` → `removeSource` 순서.
- 테마 전환 race: #258 패턴 — `style.load` 이벤트 핸들러에서 layer/source 재등록.

### 4.3 `toFeatureCollection` 재사용 (`apps/firehub-web/src/lib/geo-utils.ts`)

기존 함수가 행 전체를 `feature.properties`에 보존하면 추가 변경 불필요. 미보존이면 weight 컬럼만이라도 properties에 포함시키도록 미세 수정.

## 5. 차트 빌더 UI

### 5.1 `ChartTypeSelector`

변경 없음. MAP 단일 버튼.

### 5.2 `AxisConfigPanel` (`apps/firehub-web/src/components/analytics/AxisConfigPanel.tsx`)

MAP 차트 선택 시 패널 상단에 추가:

- **표시 모드 토글**: shadcn `Tabs` 또는 `ToggleGroup` — `[점 | 히트맵]`, 기본 `점`.
- 토글 변경 시 onChange:
  ```ts
  onChange({
    ...config,
    mapDisplayMode: next,
    weightColumn: next === 'points' ? undefined : config.weightColumn,
    colorByColumn: next === 'heatmap' ? undefined : config.colorByColumn,
  });
  ```

모드별 필드 노출:

| 필드 | points | heatmap |
| --- | --- | --- |
| `spatialColumn` (GEOMETRY) | ✔ | ✔ |
| `colorByColumn` | ✔ | — |
| `weightColumn` (numeric only, optional) | — | ✔ |

`weightColumn` 셀렉트 옵션은 numeric 컬럼으로 필터링하고, 첫 옵션 `(없음 — 균등 가중치)` 제공. `Tooltip`: "값이 클수록 해당 위치의 밀도 기여가 커집니다".

### 5.3 `ChartBuilderPage` / `DashboardEditorPage` / `InlineChartWidget`

신규 필드 2개가 `ChartConfig`에 합류하므로 자동 호환. 변경 불필요.

## 6. 성능 가드

상수:

```ts
const HEATMAP_ROW_WARN = 50_000;
```

`heatmap` 모드에서 `data.length > HEATMAP_ROW_WARN` 시:

- 렌더링은 그대로 진행 (maplibre native heatmap은 GPU 가속).
- `MapChartView` 우상단에 비차단 inline 경고 배지: `"데이터 N행 — 렌더링 성능 저하 가능"`.
- `points` 모드는 영향 없음. 더 강한 다운샘플링(서버측 grid 집계)은 별도 이슈로 분리.

## 7. 테스트

### 7.1 E2E 신규 spec — `apps/firehub-web/e2e/pages/analytics-chart-map-heatmap.spec.ts`

Factories: weight 컬럼 포함 mock 데이터 추가.

케이스:

1. MAP 선택 → 표시 모드 토글 `[점|히트맵]` 노출, 기본 `점`.
2. `히트맵` 선택 → `colorByColumn` 셀렉트 사라지고 `weightColumn` 셀렉트 노출.
3. spatialColumn + weightColumn 설정 후 저장 → POST payload에 `mapDisplayMode:'heatmap', weightColumn:'<col>'` 포함 (`route.request().postDataJSON()` 캡처).
4. 저장된 차트 재진입 → 모드/필드 복원.
5. 행 50,001건 mock → 경고 배지 노출.
6. (회귀) points 모드 기존 spec 통과.

`@smoke` 미부착 — 신규 sub-mode이며 핵심 happy-path는 기존 MAP smoke가 커버.

### 7.2 단위

`HeatmapLayer`의 maplibre 모킹은 비용 대비 가치 낮음 → 생략. E2E에서 캔버스 존재 + `map.getLayer('heatmap-lyr')` 존재 여부로 검증.

## 8. 산출물 / 변경 파일

수정:

- `apps/firehub-web/src/types/analytics.ts`
- `apps/firehub-web/src/components/analytics/MapChartView.tsx`
- `apps/firehub-web/src/components/analytics/AxisConfigPanel.tsx`
- `apps/firehub-web/src/lib/geo-utils.ts` (필요 시)

신규:

- `apps/firehub-web/src/components/map/HeatmapLayer.tsx`
- `apps/firehub-web/e2e/pages/analytics-chart-map-heatmap.spec.ts`

## 9. Out of Scope

- 클러스터링(점 군집화) — 별도 이슈.
- 코로플레스(폴리곤 등급 채색) — 별도 이슈.
- 서버측 grid 집계 / 다운샘플링 — 별도 이슈 (#119 Risk 항목 참고).
