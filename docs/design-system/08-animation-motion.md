# 08. Animation & Motion

UI 전반의 애니메이션과 전환 효과에 관한 패턴을 정의한다. 일관된 타이밍과 목적 있는 모션은 인터페이스의 품질감을 높이고 사용자의 맥락 유지를 돕는다.

---

## A. 현재(As-Is) 패턴

프로젝트 코드베이스에서 실제로 사용 중인 애니메이션 패턴.

### 1. `transition-colors` — 색상 전환

테이블 행 hover, 버튼, 네비게이션 아이템에 가장 광범위하게 사용된다 (약 30개 인스턴스).

```tsx
// 테이블 행 hover
<TableRow className="transition-colors hover:bg-muted/50 cursor-pointer" />

// 네비게이션 아이템
<NavItem className="transition-colors hover:bg-accent hover:text-accent-foreground" />

// 버튼 (shadcn/ui 기본 포함)
<Button className="transition-colors" />
```

### 2. `transition-opacity` — 투명도 전환

`group-hover` 패턴과 함께 사용하여 hover 시 액션 버튼을 표시/숨김 처리한다.

```tsx
// 테이블 행 액션 버튼 표시
<TableRow className="group">
  <TableCell>
    <div className="opacity-0 group-hover:opacity-100 transition-opacity">
      <Button size="icon" variant="ghost">
        <MoreHorizontal className="h-4 w-4" />
      </Button>
    </div>
  </TableCell>
</TableRow>
```

### 3. `transition-all duration-200` — 사이드바 / AI 패널 전환

사이드바 펼침/접힘, AI 패널 크기 조정에 사용된다.

```tsx
<aside className="transition-all duration-200" style={{ width: isCollapsed ? 60 : 240 }} />
```

### 4. `animate-spin` — 로딩 스피너

Loader2 아이콘과 함께 로딩 상태를 나타낸다.

```tsx
<Loader2 className="h-4 w-4 animate-spin" />
```

### 5. `animate-pulse` — 도구 실행 인디케이터

MessageBubble의 도구 실행 중 상태 표시에 사용된다.

```tsx
<span className="inline-block h-2 w-2 rounded-full bg-primary animate-pulse" />
```

### 6. `animate-in` / `animate-out` — shadcn 컴포넌트 진입/퇴장

Dialog, Popover, Tooltip 등 shadcn/ui 컴포넌트에 기본 적용된다.

```tsx
// shadcn Dialog 내부 (자동 적용)
"animate-in fade-in-0 zoom-in-95"
"animate-out fade-out-0 zoom-out-95"
```

### 7. `transition-[width,transform] duration-200` — 사이드바 너비 변경

사이드바의 너비 전환에 특정 속성만 명시적으로 지정한다.

```tsx
<aside className="transition-[width,transform] duration-200 ease-in-out" />
```

### 8. `transition-all duration-300` — 프로그레스 바

프로그레스 바 채움 애니메이션에 사용된다.

```tsx
<div
  className="h-full bg-primary transition-all duration-300 ease-out"
  style={{ width: `${progress}%` }}
/>
```

---

## B. 권장 타이밍 스케일 (To-Be)

상호작용 유형별 권장 duration, easing, 대상 속성을 정의한다.

| 상호작용 | Duration | Easing | 대상 속성 | Tailwind |
|----------|----------|--------|-----------|---------|
| 테이블 행 hover | 100ms | ease-out | background-color | `transition-colors` |
| 버튼 hover | 150ms | ease-out | background-color, box-shadow | `transition-colors` |
| Input focus | 150ms | ease-out | border-color, box-shadow | `transition-[border-color,box-shadow]` |
| 액션 표시/숨김 | 150ms | ease-out | opacity | `transition-opacity` |
| Dropdown 열림 | 200ms | ease-out | opacity, transform | shadcn 기본값 |
| Dropdown 닫힘 | 150ms | ease-in | opacity, transform | shadcn 기본값 |
| 사이드바 토글 | 200ms | ease-in-out | width, transform | `transition-[width,transform] duration-200` |
| Modal 진입 | 300ms | ease-out | opacity, transform | shadcn animate-in |
| Modal 퇴장 | 200ms | ease-in | opacity, transform | shadcn animate-out |
| Skeleton shimmer | 1500ms | linear (무한) | background-position | `animate-pulse` |
| 로딩 스피너 | continuous | linear | transform (rotate) | `animate-spin` |

### 타이밍 원칙

- **빠른 반응** (100–150ms): hover, focus 같이 즉각적인 피드백이 필요한 상호작용
- **자연스러운 전환** (200ms): 패널 열림/닫힘, 컴포넌트 진입
- **명확한 변화** (300ms): 모달 진입, 페이지 전환처럼 큰 컨텍스트 변화
- **지속 애니메이션**: 스피너(continuous), 스켈레톤(1500ms 루프)

---

## C. GPU 가속 규칙

**애니메이션 가능한 속성 (Compositor 레이어)**:
- `opacity` — 레이아웃/페인트 없음
- `transform` (translate, scale, rotate) — 레이아웃/페인트 없음

**애니메이션 금지 속성 (Layout/Paint 유발)**:
| 속성 | 이유 |
|------|------|
| `width`, `height` | Layout 재계산 |
| `margin`, `padding` | Layout 재계산 |
| `top`, `left` | Layout 재계산 (`transform: translate` 사용) |
| `font-size` | Layout 재계산 |
| `background-color` | Paint (단, GPU 비용이 낮아 색상 전환은 허용) |

**예외**: 사이드바의 `transition-[width]`는 레이아웃 애니메이션이지만, 발생 빈도가 매우 낮고(사용자가 명시적으로 토글할 때만) 대안이 없으므로 허용한다.

```tsx
// 권장: transform 사용
<div className="transition-transform duration-200 translate-x-0 data-[collapsed]:translate-x-full" />

// 비권장: top/left 애니메이션
<div className="transition-[top] duration-200" style={{ top: isOpen ? 0 : -100 }} />
```

---

## D. Reduced Motion (모션 감소)

`prefers-reduced-motion` 미디어 쿼리를 통해 전정 장애가 있는 사용자를 위해 모든 애니메이션을 비활성화한다.

```css
/* globals.css */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
```

Tailwind를 사용하는 경우 조건부 클래스로 적용할 수 있다.

```tsx
// motion-reduce: 유틸리티 클래스 사용
<Loader2 className="animate-spin motion-reduce:animate-none" />

<div className="transition-all duration-200 motion-reduce:transition-none" />
```

---

## E. 패턴 적용 요약

```tsx
// 테이블 행 — hover 색상 전환
<TableRow className="transition-colors hover:bg-muted/50" />

// 버튼 내 스피너
<Button disabled={isPending}>
  {isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
  저장
</Button>

// hover 시 액션 표시
<div className="group relative">
  <span>{content}</span>
  <div className="absolute right-2 top-1/2 -translate-y-1/2 opacity-0 transition-opacity group-hover:opacity-100">
    <ActionButtons />
  </div>
</div>

// 사이드바 너비 전환
<aside
  className="transition-[width,transform] duration-200 ease-in-out overflow-hidden"
  style={{ width: isCollapsed ? 60 : 240 }}
/>
```
