import { ArrowDown, ArrowUp, ArrowUpDown } from "lucide-react"
import * as React from "react"

import { cn } from "@/lib/utils"

function Table({ className, ...props }: React.ComponentProps<"table">) {
  // 가로 스크롤 인디케이터 상태 — 컨테이너 너비 변경/스크롤 시 갱신
  // 좁은 뷰포트(모바일)에서 테이블이 잘릴 때 사용자가 인지할 수 있도록
  // 우측 페이드 그라데이션 + 안내 힌트를 노출한다.
  const containerRef = React.useRef<HTMLDivElement>(null)
  const [overflow, setOverflow] = React.useState({
    canScrollLeft: false,
    canScrollRight: false,
  })

  // 스크롤/리사이즈에 따라 좌/우 그라데이션 표시 여부 갱신
  const updateOverflow = React.useCallback(() => {
    const el = containerRef.current
    if (!el) return
    const { scrollLeft, scrollWidth, clientWidth } = el
    setOverflow({
      canScrollLeft: scrollLeft > 0,
      canScrollRight: scrollLeft + clientWidth < scrollWidth - 1,
    })
  }, [])

  React.useEffect(() => {
    const el = containerRef.current
    if (!el) return
    updateOverflow()
    el.addEventListener("scroll", updateOverflow, { passive: true })
    const ro = new ResizeObserver(updateOverflow)
    ro.observe(el)
    if (el.firstElementChild) ro.observe(el.firstElementChild)
    return () => {
      el.removeEventListener("scroll", updateOverflow)
      ro.disconnect()
    }
  }, [updateOverflow])

  return (
    <div className="relative w-full">
      <div
        ref={containerRef}
        data-slot="table-container"
        className="relative w-full overflow-x-auto"
      >
        <table
          data-slot="table"
          className={cn("w-full caption-bottom text-sm", className)}
          {...props}
        />
      </div>
      {/* 좌측 페이드 — 스크롤이 시작된 후에만 표시 */}
      {overflow.canScrollLeft && (
        <div
          aria-hidden="true"
          data-slot="table-fade-left"
          className="pointer-events-none absolute inset-y-0 left-0 w-6 bg-gradient-to-r from-background to-transparent"
        />
      )}
      {/* 우측 페이드 + 힌트 — 잘린 컬럼이 있을 때만 표시 */}
      {overflow.canScrollRight && (
        <div
          aria-hidden="true"
          data-slot="table-fade-right"
          className="pointer-events-none absolute inset-y-0 right-0 w-8 bg-gradient-to-l from-background to-transparent"
        />
      )}
    </div>
  )
}

function TableHeader({ className, ...props }: React.ComponentProps<"thead">) {
  return (
    <thead
      data-slot="table-header"
      className={cn("[&_tr]:border-b", className)}
      {...props}
    />
  )
}

function TableBody({ className, ...props }: React.ComponentProps<"tbody">) {
  return (
    <tbody
      data-slot="table-body"
      className={cn("[&_tr:last-child]:border-0", className)}
      {...props}
    />
  )
}

function TableFooter({ className, ...props }: React.ComponentProps<"tfoot">) {
  return (
    <tfoot
      data-slot="table-footer"
      className={cn(
        "bg-muted/50 border-t font-medium [&>tr]:last:border-b-0",
        className
      )}
      {...props}
    />
  )
}

function TableRow({ className, ...props }: React.ComponentProps<"tr">) {
  return (
    <tr
      data-slot="table-row"
      className={cn(
        "hover:bg-muted/50 data-[state=selected]:bg-muted border-b transition-colors",
        className
      )}
      {...props}
    />
  )
}

function TableHead({ className, ...props }: React.ComponentProps<"th">) {
  return (
    <th
      data-slot="table-head"
      className={cn(
        "text-foreground h-10 px-2 text-left align-middle font-medium whitespace-nowrap [&:has([role=checkbox])]:pr-0 [&>[role=checkbox]]:translate-y-[2px]",
        className
      )}
      {...props}
    />
  )
}

function TableCell({ className, ...props }: React.ComponentProps<"td">) {
  return (
    <td
      data-slot="table-cell"
      className={cn(
        "p-2 align-middle whitespace-nowrap [&:has([role=checkbox])]:pr-0 [&>[role=checkbox]]:translate-y-[2px]",
        className
      )}
      {...props}
    />
  )
}

/**
 * 정렬 가능한 테이블 헤더 컴포넌트
 *
 * 사용법:
 *   <SortableHeader
 *     direction={sortKey === 'name' ? sortOrder : 'none'}
 *     onSort={() => toggleSort('name')}
 *   >
 *     이름
 *   </SortableHeader>
 *
 * 동작:
 * - 부모 <th>에 aria-sort 속성을 부착하여 스크린리더가 정렬 상태를 인지하도록 한다.
 * - direction='none' 일 때는 ArrowUpDown(중립), 'asc' 면 ArrowUp, 'desc' 면 ArrowDown 아이콘 표시.
 * - 헤더 영역 전체가 button 으로 동작하여 키보드(Enter/Space)로도 정렬 토글 가능.
 *
 * 왜:
 * - 이슈 #80: 테이블 컬럼 헤더 클릭 정렬 미지원 + aria-sort 부재로 시각·SR 사용자 모두 인지 불가.
 * - shadcn/ui 기본 Table 은 정렬을 제공하지 않으므로 헬퍼로 일관된 패턴을 제공한다.
 */
type SortDirection = "asc" | "desc" | "none"

interface SortableHeaderProps
  extends Omit<React.ComponentProps<"th">, "onClick"> {
  /** 현재 컬럼의 정렬 방향. 다른 컬럼이 정렬 중이면 'none'으로 전달. */
  direction: SortDirection
  /** 헤더 클릭/Enter/Space 시 호출. 보통 toggleSort(key) 형태. */
  onSort: () => void
  children: React.ReactNode
}

function SortableHeader({
  className,
  direction,
  onSort,
  children,
  ...props
}: SortableHeaderProps) {
  // aria-sort 표준값으로 매핑 (WAI-ARIA: ascending | descending | none | other)
  const ariaSort: "ascending" | "descending" | "none" =
    direction === "asc"
      ? "ascending"
      : direction === "desc"
      ? "descending"
      : "none"

  // 방향에 맞는 아이콘 — 중립일 땐 약하게(opacity-50) 표시하여 시각적 노이즈를 줄인다.
  const Icon =
    direction === "asc" ? ArrowUp : direction === "desc" ? ArrowDown : ArrowUpDown

  return (
    <th
      data-slot="table-head"
      aria-sort={ariaSort}
      className={cn(
        "text-foreground h-10 px-2 text-left align-middle font-medium whitespace-nowrap [&:has([role=checkbox])]:pr-0 [&>[role=checkbox]]:translate-y-[2px]",
        className
      )}
      {...props}
    >
      <button
        type="button"
        onClick={onSort}
        className={cn(
          "inline-flex items-center gap-1.5 -mx-2 px-2 py-1 rounded-sm",
          "hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40",
          "transition-colors text-foreground font-medium"
        )}
      >
        {children}
        <Icon
          className={cn(
            "h-3.5 w-3.5 shrink-0",
            direction === "none"
              ? "opacity-50 text-muted-foreground"
              : "text-foreground"
          )}
          aria-hidden="true"
        />
      </button>
    </th>
  )
}

function TableCaption({
  className,
  ...props
}: React.ComponentProps<"caption">) {
  return (
    <caption
      data-slot="table-caption"
      className={cn("text-muted-foreground mt-4 text-sm", className)}
      {...props}
    />
  )
}

export {
  SortableHeader,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
}
export type { SortDirection }
