# 03. Spacing & Layout

Smart Fire Hub 디자인 시스템의 간격(spacing)과 레이아웃(layout) 규칙을 정의한다.

---

## Spacing System

### Base Unit

**4px** — Tailwind v4 기본 spacing 단위인 `--spacing: 0.25rem`을 그대로 따른다. 모든 간격 값은 이 단위의 배수여야 한다.

### 승인된 Spacing Scale

| Token | Tailwind class | px | 사용 맥락 |
|-------|----------------|----|-----------|
| space-1 | `gap-1` / `p-1` | 4px | Icon inner padding, chip gap |
| space-1.5 | `gap-1.5` | 6px | Icon-text tight spacing |
| space-2 | `gap-2` / `p-2` | 8px | Compact component padding, table cells |
| space-3 | `gap-3` / `p-3` | 12px | Badge padding, toolbar item spacing |
| space-4 | `gap-4` / `p-4` | 16px | **표준 컴포넌트 padding**, card grid gap, form field 간격 |
| space-6 | `gap-6` / `p-6` | 24px | **Card body padding**, page padding, section 간격 |
| space-8 | `gap-8` / `p-8` | 32px | Section separator, form group gap |

> **주의**: `space-5` (20px)는 프로젝트에서 사용하지 않는다. 새로 도입하지 말 것.

### 컴포넌트별 Spacing 기준

| 컴포넌트 | 적용 규칙 |
|----------|-----------|
| Card (CardContent / Header / Footer) | `p-6` (shadcn 기본) |
| Card — compact 변형 | `p-4` |
| Table cell | `p-2` |
| Table header | `h-10 px-2` |
| Sidebar header | `h-14` |
| Sidebar nav item | `px-3 py-1.5` |
| Dialog | `p-6`, 내부 요소 간격 `gap-4` |
| Form — 필드 간 | `space-y-4` |
| Form — label + input 간 | `space-y-2` |
| Page section 간 | `space-y-6` |

---

## Layout Skeleton

### AppLayout 구조

`apps/firehub-web/src/layouts/AppLayout.tsx` 기준 전체 레이아웃.

```
┌───────────────────────────────────────────────────────┐
│  SIDEBAR              │  HEADER  h-14 (56px)          │
│  lg:w-60 (240px)      │  sticky top-0 z-30            │
│                       │  border-b bg-background       │
│  collapsed:           ├───────────────────────────────┤
│  lg:w-[52px] (52px)   │  MAIN                         │
│                       │  flex-1 p-6 overflow-auto     │
│  Mobile (< lg):       │                               │
│  fixed w-60 z-50      │  (선택) AI Panel              │
│  overlay z-40         │  w-80 (320px) border-l        │
│                       │  mode: side / float / full    │
└───────────────────────┴───────────────────────────────┘
```

**Sidebar 너비 요약**:
- 데스크톱 expanded: `lg:w-60` = 240px
- 데스크톱 collapsed: `lg:w-[52px]` = 52px
- 모바일: `fixed w-60 z-50`, 뒤에 `fixed inset-0 z-40 bg-black/50` overlay

**Header**:
- 높이: `h-14` = 56px
- 위치: `sticky top-0 z-30`
- 스타일: `border-b bg-background`

**Main content area**:
- `flex-1 p-6 overflow-auto`
- page-level 섹션 간격: `space-y-6`

**AI Panel** (선택적 우측 패널):
- 너비: `w-80` = 320px
- 위치: `border-l`
- 3가지 모드: `side` (인라인), `float` (플로팅), `full` (전체 너비)

### TSX Skeleton

```tsx
// AppLayout 구조 예시
export function AppLayout() {
  return (
    <div className="flex h-screen overflow-hidden">
      {/* Sidebar */}
      <aside className="hidden lg:flex lg:w-60 lg:flex-col border-r bg-background">
        <div className="h-14 flex items-center px-4 border-b">
          {/* Logo / brand */}
        </div>
        <nav className="flex-1 overflow-y-auto px-3 py-2 space-y-1">
          {/* Nav items: px-3 py-1.5 */}
        </nav>
      </aside>

      {/* Main */}
      <div className="flex flex-col flex-1 overflow-hidden">
        {/* Header */}
        <header className="h-14 sticky top-0 z-30 border-b bg-background flex items-center px-6">
          {/* Breadcrumb, user menu */}
        </header>

        {/* Content + optional AI Panel */}
        <div className="flex flex-1 overflow-hidden">
          <main className="flex-1 p-6 overflow-auto">
            {/* Page content */}
          </main>

          {/* AI Panel (optional) */}
          <aside className="w-80 border-l bg-background overflow-y-auto">
            {/* AI chat panel */}
          </aside>
        </div>
      </div>
    </div>
  )
}
```

---

## Grid Patterns

프로젝트에서 사용하는 5가지 그리드 패턴.

### 패턴 1 — Stat Cards (통계 카드 행)

대시보드 상단의 KPI 카드 4개 행에 사용.

```
┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
│ Stat 1 │ │ Stat 2 │ │ Stat 3 │ │ Stat 4 │
└────────┘ └────────┘ └────────┘ └────────┘
  lg:grid-cols-4
  md:grid-cols-2
  (sm: 1열)
```

```tsx
<div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
  <StatCard title="총 데이터셋" value={42} />
  <StatCard title="활성 파이프라인" value={7} />
  <StatCard title="오늘 처리량" value="1.2GB" />
  <StatCard title="오류" value={0} />
</div>
```

### 패턴 2 — Two-Column Content (2열 콘텐츠)

차트 + 테이블, 또는 두 카드를 나란히 배치할 때 사용.

```
┌──────────────────┐ ┌──────────────────┐
│   Left content   │ │  Right content   │
│   (chart, etc.)  │ │  (table, etc.)   │
└──────────────────┘ └──────────────────┘
  md:grid-cols-2
  (sm: 1열)
```

```tsx
<div className="grid gap-4 md:grid-cols-2">
  <Card>
    <CardHeader><CardTitle>차트</CardTitle></CardHeader>
    <CardContent>{/* chart */}</CardContent>
  </Card>
  <Card>
    <CardHeader><CardTitle>상위 항목</CardTitle></CardHeader>
    <CardContent>{/* table */}</CardContent>
  </Card>
</div>
```

### 패턴 3 — Detail Info Grid (상세 정보 그리드)

엔티티 상세 페이지의 메타데이터 필드 나열에 사용. 필드 수에 따라 3열 또는 4열 선택.

```
┌──────┐ ┌──────┐ ┌──────┐
│ 생성일 │ │ 소유자 │ │ 상태  │
└──────┘ └──────┘ └──────┘
┌──────┐ ┌──────┐ ┌──────┐
│ 크기  │ │ 행 수 │ │ 태그  │
└──────┘ └──────┘ └──────┘
  grid-cols-2 md:grid-cols-3
```

```tsx
{/* 3열 변형 */}
<div className="grid grid-cols-2 md:grid-cols-3 gap-4">
  <InfoField label="생성일" value={dataset.createdAt} />
  <InfoField label="소유자" value={dataset.owner} />
  <InfoField label="상태" value={dataset.status} />
  <InfoField label="크기" value={dataset.size} />
  <InfoField label="행 수" value={dataset.rowCount} />
  <InfoField label="태그" value={dataset.tags.join(", ")} />
</div>

{/* 4열 변형 */}
<div className="grid grid-cols-2 md:grid-cols-4 gap-4">
  <InfoField label="생성일" value={dataset.createdAt} />
  <InfoField label="수정일" value={dataset.updatedAt} />
  <InfoField label="소유자" value={dataset.owner} />
  <InfoField label="상태" value={dataset.status} />
</div>
```

### 패턴 4 — Chart Builder (좌측 고정 패널 + 우측 가변 영역)

Analytics 페이지의 설정 패널 + 차트 영역처럼 좌측 패널이 고정 너비일 때 사용.

```
┌──────────────┐ ┌────────────────────────────┐
│  설정 패널    │ │      차트 / 결과 영역       │
│  280px (고정) │ │      flex-1 (가변)          │
│              │ │                            │
└──────────────┘ └────────────────────────────┘
  lg:grid-cols-[280px_1fr]
  (< lg: 1열, 설정 패널이 위로)
```

```tsx
<div className="grid grid-cols-1 lg:grid-cols-[280px_1fr] gap-4">
  {/* 좌측: 설정 패널 */}
  <Card className="h-fit">
    <CardHeader><CardTitle>차트 설정</CardTitle></CardHeader>
    <CardContent className="space-y-4">
      {/* 필드 선택, 집계 방식 등 */}
    </CardContent>
  </Card>

  {/* 우측: 차트 + 결과 */}
  <div className="space-y-4">
    <Card>
      <CardContent className="p-6">{/* chart */}</CardContent>
    </Card>
  </div>
</div>
```

### 패턴 5 — Dashboard (react-grid-layout 동적 그리드)

사용자가 위젯을 드래그해 배치하는 대시보드 전용. Tailwind grid가 아닌 `react-grid-layout`을 사용.

```
┌─────────────────────────────────────────┐
│  [Widget A: cols 6] [Widget B: cols 6]  │  lg: 12열
├─────────────────────────────────────────┤
│  [Widget C: cols 4] [Widget D: cols 8]  │
└─────────────────────────────────────────┘
  breakpoints: { lg: 1200, md: 996, sm: 768 }
  cols:        { lg: 12,   md: 8,   sm: 4  }
```

```tsx
import { Responsive, WidthProvider } from "react-grid-layout"

const ResponsiveGridLayout = WidthProvider(Responsive)

<ResponsiveGridLayout
  breakpoints={{ lg: 1200, md: 996, sm: 768 }}
  cols={{ lg: 12, md: 8, sm: 4 }}
  rowHeight={60}
  layouts={layouts}
  onLayoutChange={handleLayoutChange}
>
  {widgets.map((widget) => (
    <div key={widget.id}>
      <DashboardWidget widget={widget} />
    </div>
  ))}
</ResponsiveGridLayout>
```

---

## Content Area 최대 너비

페이지 유형에 따라 콘텐츠 영역의 최대 너비를 다르게 적용한다. 모든 경우 `mx-auto`로 가운데 정렬.

| 페이지 유형 | 클래스 | 최대 너비 |
|------------|--------|-----------|
| Forms / Settings | `max-w-2xl mx-auto` | 672px |
| Detail pages | `max-w-4xl mx-auto` | 896px |
| Dashboard | `max-w-6xl mx-auto` | 1152px |
| Tables / Maps | `w-full` | 제한 없음 |

```tsx
{/* Form / Settings 페이지 */}
<div className="max-w-2xl mx-auto space-y-6">
  <h1 className="text-2xl font-bold">설정</h1>
  <form className="space-y-4">{/* ... */}</form>
</div>

{/* Detail 페이지 */}
<div className="max-w-4xl mx-auto space-y-6">
  <DetailHeader />
  <DetailBody />
</div>

{/* Dashboard */}
<div className="max-w-6xl mx-auto space-y-6">
  <DashboardHeader />
  <StatCards />
  <DashboardGrid />
</div>

{/* Table / Map — 전체 너비 */}
<div className="w-full space-y-4">
  <DataTable />
</div>
```

---

## 현재(As-Is) vs 권장(To-Be)

| 항목 | 현재(As-Is) | 권장(To-Be) |
|------|------------|------------|
| 임의 간격 값 | `p-5`, `gap-5` 등 비표준 값 산발 사용 | 승인된 scale 값만 사용 (`p-4`, `p-6` 등) |
| Card padding | 일부 Card에서 `p-4`와 `p-6` 혼용 | shadcn CardContent 기본 `p-6` 유지, compact 변형만 `p-4` |
| 콘텐츠 최대 너비 | 페이지마다 `max-w-*` 값이 다름 | 위 표의 4가지 패턴으로 표준화 |
| 그리드 패턴 | 페이지마다 임의 grid 작성 | 위 5가지 패턴 재사용 |
