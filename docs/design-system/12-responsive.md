# 12. Responsive Design

Smart Fire Hub 디자인 시스템의 반응형 디자인 전략과 구현 규칙을 정의한다.

---

## 전략 개요

**Desktop-first** 전략을 채택한다. 이 앱의 주 사용 환경은 데스크톱 브라우저이며, 모바일 지원은 Sidebar drawer 패턴으로 제한된다.

- 기본 스타일은 데스크톱 기준으로 작성한다.
- 모바일 적응은 레이아웃 붕괴 방지 수준으로만 처리한다.
- 모바일 PWA(BL-10)는 백로그 항목으로, 현재 우선순위가 없다.

---

## Breakpoints

Tailwind v4 기본 breakpoint를 그대로 사용한다.

| Prefix | 최소 너비 | 주요 사용 목적 |
|--------|-----------|--------------|
| `sm:` | 640px | Dialog 최대 너비, footer flex 방향 전환 |
| `md:` | 768px | Grid column 수 증가 (2열 → 3열 → 4열) |
| `lg:` | 1024px | Sidebar 고정 레이아웃, 복잡한 그리드 활성화 |

> `xl:` (1280px), `2xl:` (1536px)은 현재 프로젝트에서 사용하지 않는다.

---

## Sidebar 반응형 동작

Sidebar는 breakpoint에 따라 완전히 다른 렌더링 방식을 취한다.

### 모바일 (`< lg`, 1024px 미만)

기본적으로 숨겨져 있으며, 햄버거 버튼으로 열고 닫는 drawer 패턴을 사용한다.

```
┌──────────────────────────────────┐
│  ☰  Smart Fire Hub              │  ← Header (h-14)
├──────────────────────────────────┤
│                                  │
│          Main Content            │
│          (전체 너비)              │
│                                  │
└──────────────────────────────────┘

[햄버거 클릭 시]
┌─────────┬────────────────────────┐
│ SIDEBAR │░░░░░ Overlay (z-40) ░░│
│ w-60    │░░░░░ bg-black/50   ░░░│
│ z-50    │░░░░░░░░░░░░░░░░░░░░░░░│
│ fixed   │░░░░░░░░░░░░░░░░░░░░░░░│
└─────────┴────────────────────────┘
```

```tsx
{/* 모바일 overlay */}
{isMobileOpen && (
  <div
    className="fixed inset-0 z-40 bg-black/50 lg:hidden"
    onClick={() => setMobileOpen(false)}
  />
)}

{/* Sidebar drawer */}
<aside
  className={cn(
    "fixed inset-y-0 left-0 z-50 w-60 bg-background border-r",
    "transition-transform duration-200",
    "lg:hidden",
    isMobileOpen ? "translate-x-0" : "-translate-x-full"
  )}
>
  {/* sidebar content */}
</aside>
```

### 데스크톱 expanded (`>= lg`, Sidebar 펼침)

```
┌──────────────┬───────────────────────────────┐
│  SIDEBAR     │  HEADER                       │
│  lg:static   │                               │
│  lg:w-60     ├───────────────────────────────┤
│  (240px)     │  MAIN                         │
│              │  flex-1 overflow-auto         │
└──────────────┴───────────────────────────────┘
```

```tsx
<aside className="hidden lg:flex lg:static lg:w-60 lg:flex-col border-r bg-background">
  {/* sidebar content */}
</aside>
```

### 데스크톱 collapsed (`>= lg`, Sidebar 접힘)

아이콘만 노출되며, hover 시 tooltip으로 메뉴 이름을 표시한다.

```
┌──────┬──────────────────────────────────────┐
│  S   │  HEADER                              │
│  I   │                                      │
│  D   ├──────────────────────────────────────┤
│  E   │  MAIN                                │
│  lg:w│                                      │
│  [52]│  (콘텐츠 영역이 더 넓어짐)             │
└──────┴──────────────────────────────────────┘
  lg:w-[52px]
```

```tsx
<aside
  className={cn(
    "hidden lg:flex lg:static lg:flex-col border-r bg-background transition-all duration-200",
    isCollapsed ? "lg:w-[52px]" : "lg:w-60"
  )}
>
  {/* collapsed 상태에서 nav item은 아이콘만, tooltip으로 label 표시 */}
</aside>
```

---

## 반응형 패턴 모음

### 1. 모바일에서 숨기기 / 데스크톱에서만 표시

```tsx
{/* 데스크톱에서만 표시 */}
<div className="hidden lg:flex">
  <SidebarToggleButton />
</div>

{/* 모바일에서만 표시 */}
<button className="lg:hidden" onClick={() => setMobileOpen(true)}>
  ☰
</button>
```

### 2. Stacking Grid — 모바일 1열 → 데스크톱 다열

```tsx
{/* 1 → 2 → 4열 */}
<div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
  <StatCard />
  <StatCard />
  <StatCard />
  <StatCard />
</div>

{/* 1 → 2열 */}
<div className="grid gap-4 md:grid-cols-2">
  <ChartCard />
  <TableCard />
</div>

{/* 2 → 3열 (Detail info) */}
<div className="grid grid-cols-2 md:grid-cols-3 gap-4">
  <InfoField />
  <InfoField />
  <InfoField />
</div>
```

### 3. Dialog Footer — 모바일 수직 → 데스크톱 수평

shadcn Dialog의 `DialogFooter`에서 모바일과 데스크톱의 버튼 배열 방향이 다르다.

```tsx
{/* 모바일: 수직 (cancel이 아래), 데스크톱: 수평 오른쪽 정렬 */}
<DialogFooter className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
  <Button variant="outline" onClick={onClose}>취소</Button>
  <Button type="submit">저장</Button>
</DialogFooter>
```

```
모바일 (< sm)          데스크톱 (>= sm)
┌─────────────┐        ┌──────────────────────────┐
│    저장      │        │              취소   저장  │
├─────────────┤        └──────────────────────────┘
│    취소      │
└─────────────┘
flex-col-reverse       sm:flex-row sm:justify-end
```

### 4. Input 텍스트 크기 조정

```tsx
<Input className="md:text-sm" placeholder="검색..." />
```

### 5. Chart Builder — 모바일 수직 → 데스크톱 좌우 분할

```tsx
<div className="grid grid-cols-1 lg:grid-cols-[280px_1fr] gap-4">
  <SettingsPanel />   {/* lg 미만: 위 / lg 이상: 좌측 280px */}
  <ChartArea />       {/* lg 미만: 아래 / lg 이상: 우측 flex-1 */}
</div>
```

---

## 현재 반응형 사용 현황

> 현재 프로젝트에는 약 **13개 파일**에 걸쳐 **35개 내외**의 반응형 utility 클래스가 사용된다. 대부분 `lg:`와 `md:`이며, `sm:`은 Dialog footer에 한정된다.

| Prefix | 사용 빈도 | 주요 패턴 |
|--------|-----------|-----------|
| `lg:` | 높음 | `hidden lg:flex`, `lg:w-60`, `lg:grid-cols-*`, `lg:static` |
| `md:` | 중간 | `md:grid-cols-2`, `md:grid-cols-3`, `md:grid-cols-4`, `md:text-sm` |
| `sm:` | 낮음 | `sm:flex-row`, `sm:justify-end` (Dialog footer 한정) |

---

## 현재(As-Is) vs 권장(To-Be)

| 항목 | 현재(As-Is) | 권장(To-Be) |
|------|------------|------------|
| 전략 | Desktop-first (비공식) | Desktop-first 명시, 모바일 최소 지원 범위 문서화 |
| 모바일 레이아웃 | Sidebar drawer 외 미처리 | Sidebar drawer 유지, 나머지는 grid stacking으로 붕괴 방지 |
| `xl:` / `2xl:` | 미사용 | 필요 시 `max-w-6xl`로 콘텐츠 너비 제어, breakpoint 추가 지양 |
| 반응형 테스트 | 없음 | 최소 `md` / `lg` 두 breakpoint 기준으로 스크린샷 회귀 테스트 추가 (BL-10 이후) |
| Dashboard grid | react-grid-layout 자체 breakpoint | 현행 유지 (`lg: 1200, md: 996, sm: 768`, `cols: 12/8/4`) |
| 모바일 PWA | 백로그 (BL-10) | 우선순위 확정 후 별도 반응형 가이드 작성 |

---

## 주의 사항

1. **`xl:` / `2xl:` breakpoint 신규 도입 금지**: 현재 프로젝트에서 사용하지 않는다. 넓은 화면이 필요하면 `max-w-6xl`로 콘텐츠 너비를 제한한다.
2. **모바일 전용 UI 신규 설계 금지**: Mobile PWA(BL-10)가 확정되기 전까지 모바일 전용 컴포넌트를 추가하지 않는다.
3. **react-grid-layout breakpoint 변경 금지**: Dashboard의 `breakpoints`와 `cols`는 기존 사용자 레이아웃 저장 데이터와 결합되어 있으므로 임의 변경하지 않는다.
4. **Sidebar collapsed 너비 `lg:w-[52px]`**: 이 값은 임의 값(arbitrary value)으로, Tailwind scale에 없는 값이다. 변경 시 Sidebar 내 아이콘 정렬 전체에 영향을 준다.
