import { cva, type VariantProps } from "class-variance-authority"
import { Tabs as TabsPrimitive } from "radix-ui"
import * as React from "react"

import { cn } from "@/lib/utils"

function Tabs({
  className,
  orientation = "horizontal",
  ...props
}: React.ComponentProps<typeof TabsPrimitive.Root>) {
  return (
    <TabsPrimitive.Root
      data-slot="tabs"
      data-orientation={orientation}
      orientation={orientation}
      className={cn(
        "group/tabs flex gap-2 data-[orientation=horizontal]:flex-col",
        className
      )}
      {...props}
    />
  )
}

const tabsListVariants = cva(
  // max-sm에서만 줄바꿈(#345 Reflow/SC 1.4.10) — 320px 폭에서 탭이 한 줄에 밀려 페이지가 가로 스크롤되는 것을 막는다.
  // 높이는 고정 h-9 대신 max-sm 한정으로 auto로 풀어 두 번째 줄이 잘리지 않게 한다. sm 이상 데스크톱 표현은 그대로다.
  "rounded-lg p-[3px] group-data-[orientation=horizontal]/tabs:h-9 max-sm:group-data-[orientation=horizontal]/tabs:h-auto max-sm:flex-wrap data-[variant=line]:rounded-none group/tabs-list text-muted-foreground inline-flex w-fit items-center justify-center group-data-[orientation=vertical]/tabs:h-fit group-data-[orientation=vertical]/tabs:flex-col",
  {
    variants: {
      variant: {
        default: "bg-muted",
        line: "gap-1 bg-transparent",
      },
    },
    defaultVariants: {
      variant: "line",
    },
  }
)

function TabsList({
  className,
  variant = "line",
  ...props
}: React.ComponentProps<typeof TabsPrimitive.List> &
  VariantProps<typeof tabsListVariants>) {
  return (
    <TabsPrimitive.List
      data-slot="tabs-list"
      data-variant={variant}
      className={cn(tabsListVariants({ variant }), className)}
      {...props}
    />
  )
}

function TabsTrigger({
  className,
  ...props
}: React.ComponentProps<typeof TabsPrimitive.Trigger>) {
  return (
    <TabsPrimitive.Trigger
      data-slot="tabs-trigger"
      className={cn(
        "focus-visible:border-ring focus-visible:ring-ring focus-visible:outline-ring text-foreground/60 hover:text-foreground dark:text-muted-foreground dark:hover:text-foreground relative inline-flex h-[calc(100%-1px)] min-w-[72px] flex-1 items-center justify-center gap-1.5 rounded-md border border-transparent px-3 py-1 text-sm font-medium whitespace-nowrap transition-all group-data-[orientation=vertical]/tabs:w-full group-data-[orientation=vertical]/tabs:justify-start focus-visible:ring-[3px] focus-visible:outline-1 disabled:pointer-events-none disabled:opacity-50 group-data-[variant=default]/tabs-list:data-[state=active]:shadow-sm group-data-[variant=line]/tabs-list:data-[state=active]:shadow-none [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
        "group-data-[variant=line]/tabs-list:bg-transparent group-data-[variant=line]/tabs-list:data-[state=active]:bg-transparent dark:group-data-[variant=line]/tabs-list:data-[state=active]:border-transparent dark:group-data-[variant=line]/tabs-list:data-[state=active]:bg-transparent",
        "data-[state=active]:bg-background dark:data-[state=active]:text-foreground dark:data-[state=active]:border-input dark:data-[state=active]:bg-input/30 data-[state=active]:text-foreground",
        "after:bg-foreground after:absolute after:opacity-0 after:transition-opacity group-data-[orientation=horizontal]/tabs:after:inset-x-0 group-data-[orientation=horizontal]/tabs:after:bottom-[-5px] group-data-[orientation=horizontal]/tabs:after:h-0.5 group-data-[orientation=vertical]/tabs:after:inset-y-0 group-data-[orientation=vertical]/tabs:after:-right-1 group-data-[orientation=vertical]/tabs:after:w-0.5 group-data-[variant=line]/tabs-list:data-[state=active]:after:opacity-100",
        className
      )}
      {...props}
    />
  )
}

function TabsContent({
  className,
  ...props
}: React.ComponentProps<typeof TabsPrimitive.Content>) {
  return (
    <TabsPrimitive.Content
      data-slot="tabs-content"
      // 탭 패널은 Radix 가 tabIndex=0 을 줘 Tab 으로 닿으므로, 키보드 포커스가 보이게 링을 그린다(WD-514, WCAG 2.4.7).
      // 링은 불투명 ring 토큰 — 알파(/50)는 대비 2.0:1 로 SC 1.4.11 위반이라 알파 게이트가 막는다(WD-668)
      className={cn(
        "flex-1 rounded-md outline-none focus-visible:ring-[3px] focus-visible:ring-ring",
        className
      )}
      {...props}
    />
  )
}

/**
 * 내용 첫머리가 입력·선택 같은 컨트롤인 탭 패널(WD-514) — 패널 자체는 Tab 순서에서 뺀다(APG 탭 패턴).
 * 탭에서 Tab 을 누르면 이전처럼 바로 첫 컨트롤로 가고, 보이지 않는 Tab 정지가 생기지 않는다.
 * 필터형 탭(같은 목록을 거르는 탭)은 활성 탭 값 하나의 이 패널로 목록을 감싸, 선택된 탭의 aria-controls 가
 * 실제 role=tabpanel 을 가리키게 한다(axe aria-valid-attr-value).
 */
function TabsControlsContent(props: Omit<React.ComponentProps<typeof TabsPrimitive.Content>, "tabIndex">) {
  return <TabsContent tabIndex={-1} {...props} />
}

// shadcn 생성 파일 — tabsListVariants 재노출은 의도된 것(lint-staged 가 ui 무시 규칙을 못 적용해 훅이 막는 것을 회피)
// eslint-disable-next-line react-refresh/only-export-components
export { Tabs, TabsContent, TabsControlsContent, TabsList, tabsListVariants, TabsTrigger }
