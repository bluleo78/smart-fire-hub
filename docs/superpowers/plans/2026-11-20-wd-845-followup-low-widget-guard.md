# WD-845 리뷰 후속 — 낮은 위젯 히트맵 가드·측정 뒤 검사 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 낮은 위젯에서 히트맵도 뒤집혀 그리지 않고, 낮은 위젯 E2E 가 측정이 끝난 뒤 검사해 가드를 지우면 실패하게 한다.

**Architecture:** 히트맵을 `ResponsiveWrapper` + `HeatMap` 으로 바꿔 측정 높이가 여백 합 이하면 그리지 않는다. 박스플롯·캔들·히트맵의 측정 요소에 `data-chart-measured="true"` 를 달아 시험이 측정 완료를 기다린다.

**Tech Stack:** React 19, @nivo/core·@nivo/heatmap 0.99, recharts, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-20-wd-845-followup-low-widget-guard-design.md`

## Global Constraints

- 히트맵 여백 상수 `MARGIN = { top: 40, right: 60, bottom: 40, left: 80 }` 하나를 `HeatMap` 의 margin 과 가드(`h > MARGIN.top + MARGIN.bottom`)가 같이 쓴다. 나머지 HeatMap props(theme·colors·axis·labelTextColor·animate=false)는 지금 그대로.
- 측정 완료 표시: 속성 `data-chart-measured`, 값 `"true"`, 측정 폭이 0 보다 클 때만 달고 아니면 속성 없음. 박스플롯·캔들은 ResizeObserver 측정 컨테이너, 히트맵은 `ResponsiveWrapper` 자식 루트(부모를 채우는 div).
- 낮을 때는 빈 칸(안내 문구 없음) — 박스플롯·캔들과 같다.
- 게이지·좁은 폭 음수 폭은 범위 밖.
- 한국어 주석(무엇·왜, WD-845). 셸 명령 단순하게(한 줄 함수 정의·`${x:-{…}}`·긴 `&&` 체인 금지). `git add -A` 금지. 커밋 제목 `(WD-845)` 로 끝, 트레일러 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- 측정 전 첫 렌더: `data-chart-measured` 가 없어야 한다(있으면 시험이 다시 거짓 통과) — 시험이 가드 제거 시 실패하는지로 확인.
- 고정 높이 화면(차트 빌더·AI 인라인)의 히트맵: 지금처럼 그린다 — 기존 analytics E2E.
- 큰 위젯 히트맵: 그림 높이 ≈ 본문 높이 — 기존 fill-height 시험.
- 히트맵 매핑 오류·빈 데이터 안내: 지금 그대로 — 기존 히트맵 E2E.
- 리사이즈로 낮아졌다 커질 때: 다시 그린다(래퍼가 다시 측정) — 기존 동작, 별도 시험 없음.

---

### Task 1: 히트맵 낮은 높이 가드 + 측정 뒤 검사

**Files:**
- Modify: `apps/firehub-web/src/components/analytics/nivo/HeatmapChartView.tsx`, `apps/firehub-web/src/components/analytics/recharts/BoxPlotChartView.tsx`, `apps/firehub-web/src/components/analytics/recharts/CandlestickChartView.tsx`
- Test: `apps/firehub-web/e2e/pages/analytics/dashboard-widget-fill-height.spec.ts`

**Interfaces:** 없음(`ChartViewProps` 그대로).

- [ ] **Step 1: 시험 고치기** — 「아주 낮은 위젯에서는 음수 높이로 그리지 않는다」 대상에 HEATMAP 추가(BOXPLOT·CANDLESTICK·HEATMAP). 본문 높이 ≤ 60 확인 뒤, 위젯 안 `[data-chart-measured="true"]` 가 하나 붙을 때까지 기다리고(`toHaveCount(1)` — 빈 div 라 visible 이 아닐 수 있다) 그 다음 `[data-chart-drawing] svg` 0개.
- [ ] **Step 2:** `pnpm --filter firehub-web exec playwright test e2e/pages/analytics/dashboard-widget-fill-height.spec.ts` — 세 낮은 위젯 시험 FAIL(속성이 아직 없음).
- [ ] **Step 3:** 구현(Global Constraints 대로). 히트맵은 `import { ResponsiveWrapper } from '@nivo/core'`, `import { HeatMap } from '@nivo/heatmap'`, `ChartDrawingArea` 감싸기 유지.
- [ ] **Step 4:** 새 시험 PASS. 그 다음 **가드가 진짜 검사되는지**: 히트맵 가드(`h > …` 조건)와 박스플롯 `canDraw` 의 높이 조건을 잠시 지우고 같은 spec 을 돌려 해당 낮은 위젯 시험이 FAIL 하는지 확인 → 되돌림(보고서에 결과). `pnpm --filter firehub-web exec playwright test e2e/pages/analytics` PASS, `pnpm --filter firehub-web typecheck`·`lint` PASS.
- [ ] **Step 5: 커밋** `fix(web): 낮은 대시보드 위젯에서 히트맵도 그릴 높이가 여백보다 작으면 그리지 않고, 낮은 위젯 시험이 측정 뒤에 검사한다 (WD-845)`
