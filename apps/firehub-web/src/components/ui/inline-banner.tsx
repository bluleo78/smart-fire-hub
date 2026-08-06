import { cva, type VariantProps } from "class-variance-authority"
import * as React from "react"

import { cn } from "@/lib/utils"

// 맥락 안에서 상태를 알리는 인라인 배너. 토스트(일시적 알림)와 인라인 에러(필드 단위)의 사이를 메운다.
// 보더는 알파가 아니라 실색을 쓴다 — `*-subtle` 틴트가 페이지 배경과 1.05:1 수준이라 사실상 보이지
// 않으므로, 배너의 경계를 만드는 것은 보더뿐이고 그것이 SC 1.4.11(3:1)을 넘어야 한다.
const inlineBannerVariants = cva(
  "flex items-start gap-3 rounded-md border p-3 text-sm",
  {
    variants: {
      variant: {
        warning: "border-warning bg-warning-subtle",
        info: "border-info bg-info-subtle",
        success: "border-success bg-success-subtle",
        caution: "border-caution bg-caution-subtle",
      },
    },
    defaultVariants: {
      variant: "warning",
    },
  }
)

// 아이콘 색을 variant와 맞춘다. 본문 텍스트는 무채색(text-foreground)으로 두고 채도는
// 아이콘과 보더에만 집중시킨다 — 무채색 기반 디자인 철학과의 타협점.
const iconToneByVariant: Record<NonNullable<VariantProps<typeof inlineBannerVariants>["variant"]>, string> = {
  warning: "text-warning",
  info: "text-info",
  success: "text-success",
  caution: "text-caution",
}

function InlineBanner({
  className,
  variant = "warning",
  icon,
  title,
  actions,
  children,
  ...props
}: React.ComponentProps<"div"> &
  VariantProps<typeof inlineBannerVariants> & {
    icon?: React.ReactNode
    title?: React.ReactNode
    actions?: React.ReactNode
  }) {
  return (
    // role=status + aria-live=polite: 배너는 오류가 아니라 지속되는 상태 안내다.
    // role=alert(assertive)는 스크린리더의 현재 발화를 끊으므로 쓰지 않는다.
    <div
      data-slot="inline-banner"
      data-variant={variant}
      role="status"
      aria-live="polite"
      className={cn(inlineBannerVariants({ variant }), className)}
      {...props}
    >
      {/* 아이콘은 장식이다 — 의미는 텍스트가 전달한다(SC 1.4.1 색 단독 전달 금지). */}
      {icon ? (
        <span
          aria-hidden="true"
          className={cn("mt-0.5 shrink-0 [&>svg]:h-4 [&>svg]:w-4", iconToneByVariant[variant ?? "warning"])}
        >
          {icon}
        </span>
      ) : null}
      <div className="min-w-0 flex-1 text-foreground">
        {/* title은 반드시 본문과 별개 노드로 유지한다 — 기존 E2E가 이 노드를 텍스트로 집는다. */}
        {title ? <p className="font-medium">{title}</p> : null}
        {children ? <div className={cn(title && "mt-1")}>{children}</div> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </div>
  )
}

// inlineBannerVariants는 export하지 않는다 — 파일 내부(VariantProps 타입 추출, cn() 호출)에서만
// 쓰이고 외부 소비처가 없다. export하면 컴포넌트+비컴포넌트 동시 export가 되어
// react-refresh/only-export-components에 걸린다 — pre-commit의 lint-staged가
// eslint.config.js의 globalIgnores(['src/components/ui'])를 신규 스테이징 파일에는
// 적용하지 않는 저장소 차원 결함과 맞물려 커밋이 막히기 때문.
export { InlineBanner }
