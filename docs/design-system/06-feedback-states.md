# 06. Feedback States

로딩, 빈 상태, 에러, 토스트 등 사용자 액션에 대한 피드백 UI 패턴을 정의한다.

---

## A. Loading States (로딩 상태)

### 1. Page-level Skeleton (페이지 수준 스켈레톤)

페이지 전체 데이터가 로드되기 전에 레이아웃 구조를 미리 보여준다.

```tsx
function PageSkeleton() {
  return (
    <div className="space-y-4">
      <Skeleton className="h-8 w-64" />   {/* 페이지 타이틀 */}
      <Skeleton className="h-96 w-full" /> {/* 콘텐츠 블록 */}
    </div>
  );
}
```

### 2. Table Skeleton (테이블 스켈레톤)

테이블 데이터 로드 중에 행 구조를 유지한다.

```tsx
function TableSkeleton({ rows = 10, columns = 4 }: { rows?: number; columns?: number }) {
  return (
    <>
      {Array.from({ length: rows }).map((_, i) => (
        <TableRow key={i}>
          {Array.from({ length: columns }).map((_, j) => (
            <TableCell key={j}>
              <Skeleton className="h-10 w-full" />
            </TableCell>
          ))}
        </TableRow>
      ))}
    </>
  );
}
```

### 3. Inline Spinner (인라인 스피너)

버튼 또는 인라인 요소 내부에서 처리 중임을 나타낸다.

```tsx
<Button disabled={isLoading}>
  {isLoading && <Loader2 className="h-4 w-4 animate-spin" />}
  저장
</Button>
```

### 4. Dashboard Widget Spinner (대시보드 위젯 스피너)

카드 내부 중앙에 표시하는 로딩 인디케이터.

```tsx
function WidgetLoading() {
  return (
    <div className="flex items-center justify-center h-full">
      <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
    </div>
  );
}
```

### 5. Full-page Loading (전체 페이지 로딩)

React Suspense fallback으로 사용하는 전체 화면 스피너.

```tsx
function FullPageLoading() {
  return (
    <div className="flex items-center justify-center min-h-screen">
      <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
    </div>
  );
}

// 사용 예시
<Suspense fallback={<FullPageLoading />}>
  <PageComponent />
</Suspense>
```

---

### Skeleton 크기 규칙

| 맥락 | Height | Width | 클래스 |
|------|--------|-------|--------|
| 페이지 타이틀 | h-8 | w-64 | `<Skeleton className="h-8 w-64" />` |
| 콘텐츠 블록 | h-96 | w-full | `<Skeleton className="h-96 w-full" />` |
| 테이블 행 | h-10 | w-full | `<Skeleton className="h-10 w-full" />` |
| 카드 통계값 | h-8 | w-24 | `<Skeleton className="h-8 w-24" />` |
| 아바타 | h-8 w-8 | rounded-full | `<Skeleton className="h-8 w-8 rounded-full" />` |

---

## B. Empty States (빈 상태)

### 테이블 빈 상태

```tsx
function TableEmpty({ colSpan, message = "데이터가 없습니다" }: { colSpan: number; message?: string }) {
  return (
    <TableRow>
      <TableCell colSpan={colSpan} className="text-center py-10 text-muted-foreground">
        {message}
      </TableCell>
    </TableRow>
  );
}

// 사용 예시
<TableEmpty colSpan={5} message="데이터셋이 없습니다" />
```

### 일반 빈 상태 (아이콘 + 메시지 + 액션)

빈 상태에는 항상 사용자가 취할 수 있는 다음 행동을 제공한다.

```tsx
function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  description?: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-16 text-center">
      <Icon className="h-10 w-10 text-muted-foreground" />
      <div className="space-y-1">
        <p className="text-sm font-medium">{title}</p>
        {description && (
          <p className="text-xs text-muted-foreground">{description}</p>
        )}
      </div>
      {action}
    </div>
  );
}

// 사용 예시
<EmptyState
  icon={DatabaseIcon}
  title="데이터셋이 없습니다"
  description="새 데이터셋을 만들어 시작하세요"
  action={
    <Button size="sm" onClick={onCreate}>
      새로 만들기
    </Button>
  }
/>
```

**원칙**: 빈 상태는 항상 생산적인 액션(예: "새로 만들기" 버튼)을 포함해야 한다.

---

## C. Error States (에러 상태)

### 1. Inline Error (인라인 에러)

폼 필드 에러 또는 필드 레벨 유효성 검사 실패 시 사용.

```tsx
<p className="text-sm text-destructive">{error}</p>
```

### 2. Toast Error (토스트 에러)

API 호출 실패 등 비동기 에러에 사용. `extractApiError`를 통해 서버 에러 메시지를 추출한다.

```tsx
try {
  await createDataset(data);
  toast.success("데이터셋이 생성되었습니다");
} catch (err) {
  toast.error(extractApiError(err));
}
```

### 3. Error Boundary (에러 바운더리)

대시보드 위젯 단위로 적용하여 한 위젯의 에러가 전체 페이지에 영향을 주지 않도록 격리한다.

```tsx
function WidgetErrorBoundary({ children }: { children: React.ReactNode }) {
  return (
    <ErrorBoundary
      fallback={({ error, reset }) => (
        <div className="flex flex-col items-center justify-center gap-2 p-4 text-center">
          <p className="text-sm text-destructive">위젯을 불러오지 못했습니다</p>
          <p className="text-xs text-muted-foreground">{error.message}</p>
          <Button variant="outline" size="sm" onClick={reset}>
            다시 시도
          </Button>
        </div>
      )}
    >
      {children}
    </ErrorBoundary>
  );
}
```

### 4. Full Page Error (전체 페이지 에러)

라우트 수준의 에러 또는 복구 불가한 에러에 사용.

```tsx
function ErrorPage({ error, reset }: { error: Error; reset: () => void }) {
  return (
    <div className="flex min-h-screen items-center justify-center">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle className="text-destructive">오류가 발생했습니다</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <p className="text-sm text-muted-foreground">{error.message}</p>
          <Button onClick={reset}>다시 시도</Button>
        </CardContent>
      </Card>
    </div>
  );
}
```

---

## D. Toast Usage Rules (Sonner)

| 함수 | 사용 시점 | 예시 |
|------|----------|------|
| `toast.success()` | Mutation 성공 (생성, 수정, 삭제) | `toast.success("데이터셋이 생성되었습니다")` |
| `toast.error()` | API 실패, 유효성 검사 에러 | `toast.error(extractApiError(err))` |
| `toast.info()` | 정보성 알림 | `toast.info("클립보드에 복사되었습니다")` |
| `toast.warning()` | 비차단 경고 | 드물게 사용 |

### 현재(As-Is) 패턴

프로젝트 전반에서 일관되게 사용되는 패턴:

```tsx
// API 에러 추출 + 토스트 표시
catch (err) {
  toast.error(extractApiError(err));
}

// 성공 토스트
toast.success("데이터셋이 삭제되었습니다");

// 정보 토스트
toast.info("클립보드에 복사되었습니다");
```

### 토스트 설정 (App root)

```tsx
// App.tsx
import { Toaster } from "sonner";

<Toaster position="bottom-right" richColors />
```

---

## E. Inline Banner

**목적**: 맥락 안에서 지속되는 상태를 알린다. 토스트(일시적·전역)와 인라인 에러(필드 단위) 사이를 메운다.

| 요소 | 값 |
|------|-----|
| 컨테이너 | `flex items-start gap-3 rounded-md border p-3 text-sm` |
| 보더 | `border-{variant}` **실색** (알파 금지 — 아래 참조) |
| 배경 | `bg-{variant}-subtle` |
| 아이콘 | Lucide, 감싸는 `<span>`에 `[&>svg]:h-4 [&>svg]:w-4 mt-0.5 shrink-0 text-{variant}`, `aria-hidden="true"` (호출부는 크기 클래스를 주지 않는다 — 어떤 `ReactNode` 아이콘이든 동일하게 동작) |
| 제목(선택) | `font-medium`, 본문과 **별개 노드** |
| 본문 | `text-foreground` (무채색 — 채도는 아이콘·보더에만) |
| 액션(선택) | 우측 정렬, `size="sm"`, 최대 2개 |
| 접근성 | `role="status"` + `aria-live="polite"` |

### variant

| variant | 용도 |
|---------|------|
| `warning` | 주의를 요하는 중간 상태 (되돌릴 수 없는 작업, 일회성 노출) |
| `info` | 정보성 안내 (표시 건수 제한, 은퇴 상태) |
| `success` | 완료 상태의 지속적 표시 |
| `caution` | `warning`보다 강한 주의 |

### 사용 예

```tsx
<InlineBanner icon={<AlertTriangle />} title="이 토큰은 다시 볼 수 없습니다">
  안전한 곳에 복사하여 저장하세요.
</InlineBanner>

<InlineBanner
  variant="info"
  icon={<Archive />}
  actions={<Button size="sm" onClick={restore}>복귀</Button>}
>
  은퇴한 온톨로지입니다. 신규 바인딩은 할 수 없고, 기존 적재 데이터는 그대로 조회됩니다.
</InlineBanner>
```

### 규칙

- **보더에 알파를 쓰지 않는다.** `*-subtle` 틴트는 페이지 배경과 1.05:1 수준이라 사실상 보이지 않으므로, 배너의 경계를 만드는 것은 보더뿐이다. `border-warning/20`은 1.32:1로 SC 1.4.11(3:1)에 미달한다. 실색은 라이트 5.31:1 / 다크 9.28:1로 통과한다. 회귀 가드: `src/styles/inline-banner-gate.test.ts`에 테스트 2개가 있다.
  1. `pages/` 하위에서 **같은 줄에** `-subtle` 배경과 알파 보더가 함께 있으면 배너 관용구로 판정한다. 단독으로 쓰인 `-subtle` 틴트(예: 단계 상태 칩)는 걸리지 않는다. 예외: `ai-insights/components/SectionPreview.tsx` (배너가 아니라 AI 리포트 섹션 미리보기의 스켈레톤 플레이스홀더).
  2. `pages/` 하위에서 알파 보더(`border-{variant}/숫자`)는 `-subtle` 배경 동반 여부와 무관하게 **그 자체로** 판정한다 — 경계를 만드는 데 알파를 쓰는 것 자체가 금지 대상이다. 예외: 위 `SectionPreview.tsx`와 `settings/components/ChannelStatusBadge.tsx` (Badge 컴포넌트에 알파 보더를 쓴 것이지 배너가 아님).
- **문구에 `⚠` 같은 글리프를 넣지 않는다** — 아이콘과 중복 안내가 된다.
- **`role="alert"`(assertive)를 쓰지 않는다** — 배너는 오류가 아니라 지속 상태이며, assertive는 스크린리더의 현재 발화를 끊는다.
- 액션 버튼에 `variant="destructive"` 실색을 쓰지 않는다 — 배너 톤과 층위가 충돌한다. 파괴적 액션은 `outline` 버튼 + `DeleteConfirmDialog` 경유.

---

## 패턴 선택 가이드

| 상황 | 권장 패턴 |
|----------|----------|
| 폼 필드 유효성 | Inline error (`text-destructive`) |
| API 호출 실패 | `toast.error()` |
| 맥락 안의 지속되는 상태·경고 | InlineBanner (`warning` / `info`) |
| 위젯 렌더링 실패 | Error Boundary |
| 페이지 로드 실패 | Full page error |
| 데이터 없음 | Empty state (아이콘 + 메시지 + 액션) |
| 데이터 로딩 중 | Skeleton 또는 Spinner (맥락에 따라) |
