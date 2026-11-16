import * as React from "react"

import { cn } from "../../lib/utils"

// 빈 interface 는 lint(no-empty-object-type) 위반이라 같은 뜻의 타입 별칭으로 둔다(WD-755 에서 스테이징되며 드러남).
export type TextareaProps = React.TextareaHTMLAttributes<HTMLTextAreaElement>

const Textarea = React.forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ className, ...props }, ref) => {
    // WD-755: 채움은 Input 과 같은 규칙(라이트 bg-transparent, 다크 dark:bg-input/30) — bg-background 는 이제 카드(흰)보다
    // 한 단계 어두운 페이지 바탕이라, 카드 위에서 옆의 Input 과 달리 회색 칸으로 보였다.
    return (
      <textarea
        className={cn(
          "flex min-h-[80px] w-full rounded-md border border-input bg-transparent dark:bg-input/30 px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50",
          className
        )}
        ref={ref}
        {...props}
      />
    )
  }
)
Textarea.displayName = "Textarea"

export { Textarea }
