# 09. Form Patterns

폼 구성, 유효성 검사, 상태 표시에 관한 패턴을 정의한다.

**기술 스택**: React Hook Form + Zod (`@hookform/resolvers/zod`)

---

## A. Field Anatomy (필드 구조)

각 폼 필드는 Label → Description(선택) → Input → Error 순서로 구성된다.

```
┌─ Label (text-sm font-medium) ─────────────────┐
│  Description (text-[13px] text-muted, optional)│
├─ Input (h-9, px-3 py-2, text-sm) ─────────────┤
│  Error (text-[0.8rem] font-medium text-destructive) │
└────────────────────────────────────────────────┘
```

```tsx
function FormField({
  label,
  description,
  error,
  required,
  children,
}: {
  label: string;
  description?: string;
  error?: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-2">
      <label className="text-sm font-medium leading-none">
        {label}
        {required && <span className="text-destructive ml-1">*</span>}
      </label>
      {description && (
        <p className="text-[13px] text-muted-foreground">{description}</p>
      )}
      {children}
      {error && (
        <p className="text-[0.8rem] font-medium text-destructive">{error}</p>
      )}
    </div>
  );
}
```

---

## B. Spacing (간격 규칙)

| 위치 | 간격 | px | Tailwind |
|------|------|----|---------|
| Label → Input (필드 내부) | 8px | 8px | `space-y-2` |
| Input → Error | 6px | 6px | `mt-1.5` |
| 필드 → 필드 (기본) | 16px | 16px | `space-y-4` |
| 필드 → 필드 (컴팩트) | 12px | 12px | `space-y-3` |
| 섹션 → 섹션 | 32px | 32px | `space-y-8` |

```tsx
// 기본 폼 레이아웃
<form className="space-y-4">
  <FormField label="이름" required>...</FormField>
  <FormField label="설명">...</FormField>
</form>

// 섹션 구분이 있는 폼
<form className="space-y-8">
  <section className="space-y-4">
    <h3 className="text-sm font-semibold">기본 정보</h3>
    <FormField label="이름">...</FormField>
    <FormField label="설명">...</FormField>
  </section>
  <section className="space-y-4">
    <h3 className="text-sm font-semibold">고급 설정</h3>
    <FormField label="태그">...</FormField>
  </section>
</form>
```

---

## C. Input States (입력 상태)

Input 컴포넌트는 4가지 상태를 가진다.

| 상태 | 시각적 표현 | Tailwind |
|------|-----------|---------|
| Default | 회색 테두리 | `border-input` (oklch(0.922 0 0)) |
| Focus | Ring + 테두리 강조 | `focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]` |
| Error | 빨간 테두리 + Ring | `aria-invalid:border-destructive aria-invalid:ring-destructive/20` |
| Disabled | 50% 투명도, not-allowed 커서 | `disabled:opacity-50 disabled:pointer-events-none` |

```tsx
// shadcn Input 컴포넌트 (기본 적용됨)
<Input
  {...form.register("name")}
  aria-invalid={!!errors.name}    // Error 상태 활성화
  disabled={isSubmitting}         // Disabled 상태 활성화
/>
```

---

## D. Input Heights (입력 높이)

| 크기 | 높이 | 사용 맥락 |
|------|------|----------|
| Default | h-9 (36px) | 표준 폼 입력 |
| Small | h-8 (32px) | 컴팩트/인라인 폼 |

```tsx
// 기본 높이 (h-9)
<Input className="h-9" placeholder="데이터셋 이름" />

// 소형 (h-8) — 테이블 인라인 편집, 필터 등
<Input className="h-8 text-xs" placeholder="검색..." />
```

---

## E. Validation Timing (유효성 검사 시점)

| 필드 유형 | 에러 표시 시점 |
|----------|--------------|
| 텍스트 입력 | `onBlur` — 사용자가 필드를 벗어날 때 |
| Select / Radio | `onChange` — 값 변경 즉시 |
| Email / URL | `onBlur` + debounce |
| 폼 제출 | 즉시 — 첫 번째 에러 필드로 포커스 이동 |

```tsx
const form = useForm<FormData>({
  resolver: zodResolver(schema),
  mode: "onBlur",          // 기본: 필드 이탈 시 검사
  reValidateMode: "onChange", // 에러 표시 후: 변경 즉시 재검사
});
```

---

## F. Standard Form Structure (표준 폼 구조)

React Hook Form + Zod를 사용하는 표준 패턴.

```tsx
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";

// 1. Zod 스키마 정의
const schema = z.object({
  name: z.string().min(1, "이름을 입력하세요"),
  description: z.string().optional(),
});

type FormData = z.infer<typeof schema>;

// 2. 폼 컴포넌트
function MyForm({ onSuccess }: { onSuccess: () => void }) {
  const form = useForm<FormData>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: "",
      description: "",
    },
  });

  const { errors, isSubmitting } = form.formState;

  async function onSubmit(data: FormData) {
    try {
      await createItem(data);
      toast.success("항목이 생성되었습니다");
      onSuccess();
    } catch (err) {
      toast.error(extractApiError(err));
    }
  }

  return (
    <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
      {/* 폼 수준 에러 (선택) */}
      {errors.root && (
        <p className="text-sm text-destructive">{errors.root.message}</p>
      )}

      <FormField
        label="이름"
        error={errors.name?.message}
        required
      >
        <Input
          {...form.register("name")}
          aria-invalid={!!errors.name}
          placeholder="이름을 입력하세요"
        />
      </FormField>

      <FormField
        label="설명"
        error={errors.description?.message}
      >
        <Textarea
          {...form.register("description")}
          aria-invalid={!!errors.description}
          placeholder="설명을 입력하세요 (선택)"
          rows={3}
        />
      </FormField>

      {/* 폼 액션 */}
      <div className="flex justify-end gap-2">
        <Button
          type="button"
          variant="outline"
          onClick={onCancel}
          disabled={isSubmitting}
        >
          취소
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
          저장
        </Button>
      </div>
    </form>
  );
}
```

---

## G. Error Display Pattern (에러 표시 패턴)

에러의 발생 위치에 따라 표시 방법이 다르다.

### 폼 수준 에러 (Form-level)

폼 전체에 영향을 미치는 에러 (예: 서버 충돌, 권한 없음)를 폼 상단에 표시한다.

```tsx
{errors.root && (
  <p className="text-sm text-destructive">{errors.root.message}</p>
)}
```

수동으로 설정하는 경우:

```tsx
form.setError("root", { message: "이미 존재하는 이름입니다" });
```

### 필드 수준 에러 (Field-level)

FormField 컴포넌트가 `error` prop을 받아 Input 아래에 자동으로 표시한다.

```tsx
<FormField label="이름" error={errors.name?.message}>
  <Input {...form.register("name")} aria-invalid={!!errors.name} />
</FormField>
```

### API 에러 (Toast)

비동기 에러는 토스트로 표시한다. `extractApiError`를 사용하여 서버 응답에서 메시지를 추출한다.

```tsx
catch (err) {
  toast.error(extractApiError(err));
}
```

---

## H. Select / Combobox 패턴

```tsx
// Select (shadcn)
<FormField label="타입" error={errors.type?.message} required>
  <Controller
    control={form.control}
    name="type"
    render={({ field }) => (
      <Select onValueChange={field.onChange} defaultValue={field.value}>
        <SelectTrigger aria-invalid={!!errors.type}>
          <SelectValue placeholder="타입을 선택하세요" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="csv">CSV</SelectItem>
          <SelectItem value="json">JSON</SelectItem>
          <SelectItem value="parquet">Parquet</SelectItem>
        </SelectContent>
      </Select>
    )}
  />
</FormField>
```

---

## I. 현재(As-Is) 패턴

프로젝트에서 실제로 사용 중인 폼 패턴.

```tsx
// datasets/new, pipelines/new 등에서 사용 중인 패턴
const form = useForm<FormData>({
  resolver: zodResolver(schema),
});

// 제출 핸들러 패턴
const onSubmit = form.handleSubmit(async (data) => {
  try {
    await mutateAsync(data);
    toast.success("생성되었습니다");
    navigate("..");
  } catch (err) {
    toast.error(extractApiError(err));
  }
});

// 버튼 disabled 처리
<Button type="submit" disabled={form.formState.isSubmitting}>
  {form.formState.isSubmitting && (
    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
  )}
  저장
</Button>
```

---

## J. 접근성 (Accessibility)

- `<label>`과 `<input>`은 반드시 연결되어야 한다 (`htmlFor` + `id` 또는 래핑).
- 필수 필드는 `required` 속성과 시각적 표시(`*`) 모두 제공한다.
- 에러 상태는 `aria-invalid="true"`로 스크린 리더에 전달한다.
- 에러 메시지는 `aria-describedby`로 Input과 연결하는 것을 권장한다.

```tsx
<Input
  id="name"
  {...form.register("name")}
  aria-invalid={!!errors.name}
  aria-describedby={errors.name ? "name-error" : undefined}
/>
{errors.name && (
  <p id="name-error" className="text-[0.8rem] font-medium text-destructive">
    {errors.name.message}
  </p>
)}
```
