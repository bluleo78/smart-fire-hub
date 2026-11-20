# WD-845 리뷰 후속 — 낮은 위젯 히트맵 가드·측정 뒤 검사 — 짧은 설계

## 의도
통합 PR #1076 리뷰(AI 팀장)의 후속 2건.
1. 히트맵이 이제 위젯 높이를 채우는데, 1행 위젯(본문 ≤ 60px)에서는 nivo 안쪽 높이가 60−(40+40) < 0 이 되어 칸이 뒤집혀 그려진다. 박스플롯·캔들처럼 그릴 높이가 위·아래 여백 합 이하면 그리지 않는다.
2. E2E 「아주 낮은 위젯에서는 음수 높이로 그리지 않는다」는 첫 렌더(측정 전, `dims.width = 0`)에 svg 가 원래 없어 `toHaveCount(0)` 가 바로 통과한다 — 가드를 지워도 통과한다. 측정이 끝난 뒤 검사하고, 히트맵 가드도 같은 방식으로 검사한다.

## 목표 / 비목표
- 목표: 낮은 위젯에서 히트맵·박스플롯·캔들이 그리지 않고, 시험이 측정 뒤에 그것을 검사해 가드를 지우면 실패한다.
- 비목표: 게이지 280px 고정, 박스플롯·캔들의 아주 좁은 폭 음수 폭(팀장이 별도 이슈로 만듦). 낮을 때 안내 문구를 새로 보이기(박스플롯·캔들과 같이 빈 칸).

## 접근 방안
- A(채택) — 히트맵을 `@nivo/core` 의 `ResponsiveWrapper` + `@nivo/heatmap` 의 `HeatMap` 으로 바꿔, 측정된 `height` 가 위·아래 여백 합(80) 이하면 `HeatMap` 을 그리지 않는다. 여백은 상수 하나(`MARGIN`)로 두고 `HeatMap` 의 margin 과 가드가 같이 쓴다. `@nivo/core` 는 이미 직접 의존성이다. 측정 완료 표시는 측정한 요소에 `data-chart-measured="true"` 속성 — 박스플롯·캔들은 측정 컨테이너(`dims.width > 0` 일 때), 히트맵은 `ResponsiveWrapper` 자식 루트에(받은 width > 0 일 때) 둔다.
- B — 히트맵에 박스플롯과 같은 ResizeObserver 를 직접 둠: 같은 측정 코드가 셋이 되고, nivo 가 이미 하는 측정을 한 번 더 한다.
- C — 시험만 일정 시간 기다린 뒤 검사(`waitForTimeout`): 측정이 늦으면 다시 거짓 통과하고 느리다.

## 설계
- `nivo/HeatmapChartView.tsx`: `ResponsiveHeatMap` → `ResponsiveWrapper` 의 자식 함수 `({ width, height: h })` 에서 `h > MARGIN.top + MARGIN.bottom` 일 때만 `<HeatMap width height …>`(기존 props 그대로). 자식 루트에 `data-chart-measured` — 받은 `width > 0` 이면 `"true"`, 아니면 속성 없음(래퍼가 측정 전에 0 으로 자식을 부를 수 있다). 고정 높이(`height` 숫자)도 같은 규칙(부모 div 가 그 높이라 측정값이 같다).
- `recharts/BoxPlotChartView.tsx`·`CandlestickChartView.tsx`: 측정 컨테이너에 `data-chart-measured` — `dims.width > 0` 이면 `"true"`, 아니면 속성 없음.
- E2E `dashboard-widget-fill-height.spec.ts`: 낮은 위젯 시험 대상에 HEATMAP 추가. 위젯 안 `[data-chart-measured="true"]` 가 보일 때까지 기다린 뒤 `[data-chart-drawing] svg` 0개를 확인. 구현자는 가드를 잠시 지워 시험이 실패하는 것을 확인한다(되돌린다).
- 큰 위젯 히트맵이 지금처럼 위젯을 채우는 기존 시험은 그대로 통과해야 한다.
