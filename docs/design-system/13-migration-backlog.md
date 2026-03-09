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

## P0: 시맨틱 Status 토큰 생성

### 작업 내용
`index.css`에 `--success`, `--warning`, `--info` CSS 변수를 Light/Dark 모두 추가한다.

### 영향 범위
- **파일**: `apps/firehub-web/src/index.css` (1개 파일)
- **Tailwind 매핑**: `@theme inline` 블록에 `--color-success`, `--color-warning`, `--color-info` 추가

### Before / After

```css
/* Before: index.css — 시맨틱 Status 토큰 없음 */
:root {
  --destructive: oklch(0.577 0.245 27.325);
  /* success, warning, info 없음 */
}

/* After: 시맨틱 Status 토큰 추가 */
:root {
  --destructive: oklch(0.577 0.245 27.325);
  --success: oklch(0.523 0.165 149.5);
  --success-foreground: oklch(0.985 0 0);
  --success-subtle: oklch(0.95 0.05 149.5);
  --warning: oklch(0.84 0.16 84);
  --warning-foreground: oklch(0.2 0 0);
  --warning-subtle: oklch(0.97 0.04 84);
  --info: oklch(0.6 0.15 240);
  --info-foreground: oklch(0.985 0 0);
  --info-subtle: oklch(0.95 0.04 240);
}

.dark {
  --success: oklch(0.65 0.15 149.5);
  --success-foreground: oklch(0.985 0 0);
  --success-subtle: oklch(0.2 0.04 149.5);
  --warning: oklch(0.76 0.14 84);
  --warning-foreground: oklch(0.985 0 0);
  --warning-subtle: oklch(0.2 0.04 84);
  --info: oklch(0.7 0.13 240);
  --info-foreground: oklch(0.985 0 0);
  --info-subtle: oklch(0.2 0.04 240);
}
```

---

## P0: Badge variant 확장

### 작업 내용
`badge.tsx`에 `success`, `warning`, `info` variant를 추가한다.

### 영향 범위
- **파일**: `apps/firehub-web/src/components/ui/badge.tsx` (1개 파일)

### Before / After

```tsx
/* Before: badge.tsx — 4개 variant */
const badgeVariants = cva("...", {
  variants: {
    variant: {
      default: "...",
      secondary: "...",
      destructive: "...",
      outline: "...",
    },
  },
});

/* After: 7개 variant */
const badgeVariants = cva("...", {
  variants: {
    variant: {
      default: "...",
      secondary: "...",
      destructive: "...",
      outline: "...",
      success: "bg-success/10 text-success border-success/20",
      warning: "bg-warning/10 text-warning border-warning/20",
      info: "bg-info/10 text-info border-info/20",
    },
  },
});
```

---

## P1: 하드코딩 색상 마이그레이션

### 작업 내용
코드베이스의 43건 하드코딩 Tailwind 색상을 시맨틱 토큰으로 교체한다.

### 영향 범위
- **파일**: ~15개 파일
- **인스턴스**: 43건 (Green 12, Red 7, Amber/Yellow 13, Blue 7, Gray 4)

### 카테고리별 교체 규칙

**Green (success) → `--success` 계열**

| 파일 | 라인 | Before | After |
|------|------|--------|-------|
| DatasetListPage.tsx | 298 | `bg-green-100 text-green-800` | `<Badge variant="success">` |
| DatasetDetailPage.tsx | 165 | `bg-green-100 text-green-800` | `<Badge variant="success">` |
| ImportProgressView.tsx | 57 | `bg-green-100 text-green-600` | `bg-success-subtle text-success` |
| ImportProgressView.tsx | 149 | `border-green-200 bg-green-50` | `border-success/20 bg-success-subtle` |
| ImportProgressView.tsx | 150,159 | `text-green-700` | `text-success` |
| ImportValidationSection.tsx | 53,55 | `text-green-600` | `text-success` |
| SqlQueryEditor.tsx | 172 | `bg-green-50 text-green-700` | `bg-success-subtle text-success` |
| LinkedPipelineStatus.tsx | 23 | `bg-green-400` | `bg-success` |
| MessageBubble.tsx | 108 | `text-green-600` | `text-success` |

**Red (error/destructive) → `--destructive` 계열**

| 파일 | 라인 | Before | After |
|------|------|--------|-------|
| DatasetListPage.tsx | 303 | `bg-red-100 text-red-800` | `<Badge variant="destructive">` |
| DatasetDetailPage.tsx | 170 | `bg-red-100 text-red-800` | `<Badge variant="destructive">` |
| ImportProgressView.tsx | 59 | `bg-red-100 text-red-600` | `bg-destructive/10 text-destructive` |
| ImportProgressView.tsx | 169 | `border-red-200 bg-red-50` | `border-destructive/20 bg-destructive/5` |
| ImportProgressView.tsx | 170,175 | `text-red-700/600` | `text-destructive` |

**Amber/Yellow (warning) → `--warning` 계열**

| 파일 | 라인 | Before | After |
|------|------|--------|-------|
| ImportModeSelector.tsx | 51 | `border-amber-300 bg-amber-50 text-amber-800` | `border-warning/30 bg-warning-subtle text-warning` |
| ImportMappingTable.tsx | 42 | `border-amber-300 bg-amber-50 text-amber-800` | `border-warning/30 bg-warning-subtle text-warning` |
| ColumnDialog.tsx | 126-127 | `bg-amber-50 border-amber-200 text-amber-800` | `bg-warning-subtle border-warning/20 text-warning` |
| DatasetMapTab.tsx | 66 | `border-yellow-200 bg-yellow-50 text-yellow-800` | `border-warning/20 bg-warning-subtle text-warning` |
| WebhookTriggerForm.tsx | 57-59 | `bg-amber-50 border-amber-200 text-amber-600/800` | `bg-warning-subtle border-warning/20 text-warning` |
| ApiTriggerForm.tsx | 50-52 | `bg-amber-50 border-amber-200 text-amber-600/800` | `bg-warning-subtle border-warning/20 text-warning` |
| DatasetColumnsTab.tsx | 126 | `text-amber-600` | `text-warning` |
| ImportValidationSection.tsx | 64,66 | `text-amber-600` | `text-warning` |

**Blue (info/active) → `--info` 계열**

| 파일 | 라인 | Before | After |
|------|------|--------|-------|
| ImportProgressView.tsx | 58 | `bg-blue-100 text-blue-600` | `bg-info-subtle text-info` |
| ImportProgressView.tsx | 90 | `bg-blue-500` | `bg-info` |
| ImportProgressView.tsx | 114 | `text-blue-500` | `text-info` |
| QueryEditorPage.tsx | 201 | `text-blue-500` | `text-info` |

**Gray → `text-muted-foreground` / `bg-muted`**

| 파일 | 라인 | Before | After |
|------|------|--------|-------|
| StepNode.tsx | 169,218 | `text-gray-400` | `text-muted-foreground` |
| EditorHeader.tsx | 93 | `text-gray-500` | `text-muted-foreground` |
| ExecutionStepPanel.tsx | 145-168 | `bg-gray-900/500/700/300/400` | 시맨틱 status 색상으로 교체 |

**교체하지 않는 항목** (의도적 사용):
- `DatasetListPage.tsx:286-287` — `fill-yellow-400 text-yellow-400` (즐겨찾기 별): 브랜드 색상, 시맨틱 토큰 불필요
- `ColumnStats.tsx` — 차트 바 색상: chart 토큰(`--chart-1~5`) 사용 검토 필요
- `ApiCallPreview.tsx` — JSON 구문 강조 색상: 코드 하이라이팅 전용, 별도 토큰 세트 권장

### 검증 방법
- 빌드 + 타입체크 통과
- 각 페이지 Light/Dark 모드 스크린샷 비교
- 하드코딩 색상 grep 결과가 의도적 사용 항목만 남음

---

## P1: Typography 통일

### 작업 내용
As-Is → To-Be 타이포그래피 매핑을 기존 코드에 적용한다.

### 영향 범위
- **파일**: ~30개 페이지 파일
- **주요 변경**: 페이지 타이틀 `text-2xl font-bold` → `text-[28px] leading-[36px] font-semibold tracking-tight`

### 주요 매핑

| As-Is | To-Be | 영향 범위 |
|-------|-------|----------|
| `text-2xl font-bold` (h1) | `text-[28px] leading-[36px] font-semibold tracking-tight` | 22+ 페이지 |
| `text-lg font-semibold` (dialog) | `text-xl leading-7 font-semibold` | ~15 다이얼로그 |
| `text-base font-semibold` (section) | `text-2xl leading-8 font-semibold tracking-tight` | ~10 섹션 |
| `text-sm font-medium` (card label) | `text-sm leading-5 font-medium` (유지) | ~50 인스턴스 |
| `text-sm` (body) | `text-sm leading-6` (line-height 추가) | ~200 인스턴스 |

### 검증 방법
- 빌드 + 타입체크 통과
- 주요 페이지 Before/After 스크린샷 비교 (최소 5페이지)

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

## P3: index.css 중복 제거

### 작업 내용
`index.css` 라인 119-120, 123-124의 중복 `@apply` 규칙 정리.

### 영향 범위
- **파일**: `apps/firehub-web/src/index.css` (1개 파일)

### Before / After

```css
/* Before: 중복 */
@layer base {
  * {
    @apply border-border outline-ring/50;
    @apply border-border outline-ring/50;  /* 중복 */
  }
  body {
    @apply bg-background text-foreground;
    @apply bg-background text-foreground;  /* 중복 */
  }
}

/* After: 정리 */
@layer base {
  * {
    @apply border-border outline-ring/50;
  }
  body {
    @apply bg-background text-foreground;
  }
}
```

---

## P3: 다크 모드 앱 레이어 검증

### 작업 내용
각 페이지를 다크 모드에서 확인하고, 하드코딩 색상으로 인한 깨짐을 수정한다.

### 영향 범위
- 하드코딩 색상 43건이 다크 모드에서 시각적 문제를 일으킬 수 있음
- P1 (하드코딩 색상 마이그레이션) 완료 후 잔여 이슈 처리

### 검증 방법
- 모든 페이지 다크 모드 스크린샷 캡처 (Playwright)
- 대비 비율 WCAG AA 기준 충족 확인
- 문제 발견 시 수정 후 재캡처

---

## 작업 순서 요약

```
P0 (전제조건) → P1 (높은 영향) → P2 (개선) → P3 (품질)

P0: 시맨틱 토큰 생성 → Badge variant 확장
    ↓
P1: 하드코딩 색상 마이그레이션 (P0 토큰 사용)
    Typography 통일 (병렬 가능)
    ↓
P2: Geist 폰트 도입 검토
    접근성 개선 (병렬 가능)
    ↓
P3: index.css 중복 제거
    다크 모드 검증 (P1 완료 후)
```

**예상 영향 파일**: ~35개 파일
**예상 변경 인스턴스**: ~300+
