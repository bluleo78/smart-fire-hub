# 13. Migration Backlog — Phase D-2 작업 목록

> Phase D-1에서 수립한 디자인 가이드라인을 기존 코드에 적용하기 위한 작업 목록.
> 우선순위(P0~P3)별로 정리하며, 각 항목에 Before/After 예시를 포함한다.

---

## 우선순위 기준

| 등급 | 의미 | 기준 |
|------|------|------|
| **P0** | 즉시 필요 | 새 기능 개발의 전제 조건. 이후 작업이 이 결과에 의존 |
| **P1** | 높음 | 다크 모드 또는 디자인 일관성에 직접 영향. 시각적 깨짐 유발 |
| **P2** | 보통 | 개선 사항이지만 기능에는 영향 없음 |
| **P3** | 낮음 | 코드 품질 개선. 사용자에게 보이지 않는 변경 |

---

## ~~P0: 시맨틱 Status 토큰 생성~~ ✅ 완료

> **완료일**: 2026-04-08
> `index.css`에 `--success`, `--warning`, `--info` + `-foreground`, `-subtle` 변수가 Light/Dark 모두 정의됨.
> `@theme inline`에 `--color-success`, `--color-warning`, `--color-info` 매핑 완료.
> 추가로 `--caution`, `--ai-accent`, `--pipeline`, `--dataset`, `--dashboard-accent` 도메인 토큰도 정의됨.
> 데이터 타입 시각화 토큰(`--dtype-text`, `--dtype-number` 등 7종)도 추가됨.

---

## ~~P0: Badge variant 확장~~ ✅ 완료

> **완료일**: 2026-04-08
> `badge.tsx`에 `success`, `warning`, `info` variant 추가 완료 (총 9개 variant: default, secondary, destructive, outline, ghost, link, success, warning, info).
> `DatasetListPage`, `DatasetDetailPage`에서 `variant="success"` 실제 사용 확인.

---

## ~~P1: 하드코딩 색상 마이그레이션~~ ✅ 대부분 완료

> **완료일**: 2026-04-08 (감사 기준)
> 원래 43건 하드코딩 Tailwind 색상 중 대부분이 시맨틱 토큰으로 교체 완료.
> `bg-green-*`, `bg-red-*`, `bg-amber-*`, `bg-blue-*`, `border-*-*`, `text-gray-*` 패턴이 모두 제거됨.
>
> **잔여 항목 (의도적 사용, 교체 불필요)**:
> - `DatasetListPage.tsx`, `DatasetDetailPage.tsx` — `fill-yellow-400 text-yellow-400` (즐겨찾기 별): 브랜드 색상
> - `schema-explorer-utils.ts` — ~~하드코딩 데이터 타입 색상 7종~~ → `--dtype-*` 시맨틱 토큰으로 교체 완료 (2026-04-08)
>
> **검증**: `bg-(green|red|amber|blue)-\d+` grep 결과 0건, `border-*-\d+` 0건 확인.

---

## ~~P1: Typography 통일~~ ✅ 대부분 완료

> **완료일**: 2026-04-08 (감사 기준)
> 22+ 페이지가 `text-[28px] leading-[36px] font-semibold tracking-tight` 패턴으로 통일됨.
> `text-2xl font-bold` 패턴은 완전히 제거됨 (통계 숫자는 `text-2xl font-bold tabular-nums`로 별도 처리).
>
> **잔여 항목 (의도적 예외)**:
> - `DashboardEditorPage.tsx` — `text-lg font-semibold`: 툴바 내 제목으로 heading-page가 아님
> - `ReportViewerPage.tsx`, `ExecutionDetailPage.tsx` — `text-sm`/`text-lg`: 임베디드 헤더, 전체 화면 heading이 아님
>
> **`text-[10px]` 사용**: ~30개 인스턴스가 남아있음. AI 패널, 스키마 탐색기, 프레시니스 바, 테마 셀렉터 등 밀도 높은 UI에서 사용 중. 디자인 가이드라인의 최소 14px 권장과 충돌하나, 보조 메타데이터 표시 용도로 현재 유지. P2에서 재검토.

---

## P2: Hex/RGB 하드코딩 마이그레이션 (인라인 스타일)

### 작업 내용
Tailwind 클래스가 아닌 **인라인 스타일의 hex/rgb 하드코딩**을 CSS 변수로 교체한다.
P1에서 Tailwind 클래스 하드코딩은 완료되었으나, React Flow/CodeMirror/Mapbox 등 인라인 스타일 영역이 잔존한다.

### 영향 범위

| 영역 | 파일 | 건수 | 교체 가능 | 비고 |
|------|------|------|----------|------|
| **DAG 노드** | `StepNode.tsx` | ~36건 | **가능** (CSS var) | 스텝 타입 color/bgHeader (hex 16건), 실행 상태 bg/color/border (rgb 15건), 오버레이 (rgba 5건). 다크모드 미지원 |
| **지도 팝업** | `FeaturePopup.tsx` | ~12건 | 부분 가능 | Mapbox 팝업 CSS — Shadow DOM 외부, 다크모드 미지원 |
| **차트 palette** | `Line/Bar/Area/Pie/ScatterChartView.tsx` | ~40건 | 검토 필요 | Recharts hex palette. `getComputedStyle`로 CSS var → hex 변환 가능 |
| **코드 에디터** | `ScriptEditor.tsx` | 2건 | 가능 | CodeMirror 현재 줄 하이라이트 rgba |
| **지도 레이어** | `GeoJsonLayer.tsx` | 4건 | **불가** | Mapbox GL paint 스펙은 CSS 변수 미지원 |
| **테마 프리뷰** | `UserNav.tsx` | 3건 | 불필요 | 테마 셀렉터 미리보기 도트 (의도적) |

### 교체 우선순위

1. **StepNode.tsx (높음)**: ~36건, CSS 변수 `var(--*)` 사용 가능. 다크모드에서 시각적 깨짐 유발. 기존 시맨틱 토큰(`--success`, `--info`, `--destructive`, `--muted-foreground`)으로 대부분 매핑 가능.
2. **FeaturePopup.tsx (보통)**: Mapbox 팝업 다크모드 지원 시 함께 처리.
3. **차트 palette (보통)**: CSS 변수 기반 팔레트 훅 도입 시 처리.
4. **ScriptEditor.tsx (낮음)**: CodeMirror 테마 정리 시 함께 처리.

---

## P2: Geist 폰트 도입 검토

### 작업 내용
시스템 폰트 스택에서 Geist Sans/Mono로 전환 검토.

### 트레이드오프

| 항목 | 시스템 폰트 (현재) | Geist 도입 |
|------|-------------------|------------|
| 번들 크기 | 0 KB | ~100-200 KB (woff2) |
| 로드 속도 | 즉시 | FOIT/FOUT 가능 |
| 디자인 일관성 | OS별 다름 | 모든 환경 동일 |
| 코드 폰트 | OS별 다름 | Geist Mono 통일 |

### 결정 기준
- 디자인 일관성이 번들 크기보다 중요하면 도입
- Geist Mono만 우선 도입하는 것도 좋은 절충안 (코드/SQL 영역만)

---

## P2: 접근성 개선

### 작업 내용
WCAG 2.2 AA 기준에 맞게 ARIA 속성을 추가하고 포커스 관리를 개선한다.

### 영향 범위
- **파일**: ~20개 파일
- **현재 상태**: aria/role/sr-only 사용 ~25건 (100+ 컴포넌트 대비 부족)

### 주요 작업

| 항목 | Before | After | 파일 수 |
|------|--------|-------|---------|
| Icon-only 버튼 aria-label | `<Button size="icon"><Pencil /></Button>` | `<Button size="icon" aria-label="편집"><Pencil /></Button>` | ~15 |
| 데이터 테이블 aria-label | `<Table>` | `<Table aria-label="데이터셋 목록">` | ~8 |
| 정렬 컬럼 aria-sort | `<TableHead>이름</TableHead>` | `<TableHead aria-sort="ascending">이름</TableHead>` | ~5 |
| 차트 텍스트 대안 | `<ChartContainer>` | `<div role="img" aria-label="..."><ChartContainer>` | ~5 |
| 라이브 리전 | 없음 | `<div aria-live="polite">` 로딩/결과 수 | ~5 |

---

## ~~P3: index.css 중복 제거~~ ✅ 완료

> **완료일**: 2026-04-08
> `@layer base` 블록의 중복 `@apply` 규칙이 정리됨. 현재 각 규칙이 1회만 선언되어 있음.

---

## P3: 다크 모드 전수 검증

### 작업 내용
각 페이지를 다크 모드에서 확인하고, 잔여 하드코딩 색상으로 인한 깨짐을 수정한다.

### 영향 범위
- P1 Tailwind 하드코딩 교체 완료로 대부분 해소
- **잔여 위험 영역**: StepNode.tsx(DAG 노드 ~36건 hex/rgb), FeaturePopup.tsx(지도 팝업 ~12건 hex) — P2 교체 후 재검증
- ScriptEditor.tsx(CodeMirror 하이라이트 2건) — 경미

### 검증 방법
- 모든 페이지 다크 모드 스크린샷 캡처 (Playwright)
- 대비 비율 WCAG AA 기준 충족 확인
- 특히 파이프라인 DAG 캔버스, 지도 팝업, 코드 에디터 집중 검증
- 문제 발견 시 수정 후 재캡처

---

## 작업 순서 요약

```
P0 (전제조건) → ✅ 완료
P1 (높은 영향) → ✅ 대부분 완료
    ↓
P2: Hex/RGB 인라인 스타일 마이그레이션 (StepNode ~36건 우선)
    Geist 폰트 도입 검토
    접근성 개선 (병렬 가능)
    text-[10px] 사용 재검토
    ↓
P3: 다크 모드 전수 검증 (P2 hex/rgb 교체 후)
```

**완료 현황** (2026-04-08 기준):
- P0: ✅ 시맨틱 Status 토큰 + Badge variant 확장 완료
- P1: ✅ 하드코딩 Tailwind 색상 마이그레이션 + Typography 통일 완료
- P2: ⬜ Hex/RGB 인라인 스타일 (~95건), Geist 폰트, 접근성, text-[10px] 재검토
- P3: ✅ index.css 중복 제거 완료 / ⬜ 다크 모드 전수 검증
