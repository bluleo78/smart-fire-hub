import { ArrowDown, ArrowUp, ArrowUpDown, ChevronLeft, ChevronRight } from "lucide-react"
import * as React from "react"

import { useHorizontalOverflow } from "@/hooks/useHorizontalOverflow"
import { cn } from "@/lib/utils"

function Table({ className, ...props }: React.ComponentProps<"table">) {
  // 가로 스크롤 인디케이터 상태 — 컨테이너 너비 변경/스크롤 시 갱신
  // 좁은 뷰포트(모바일)에서 테이블이 잘릴 때 사용자가 인지할 수 있도록
  // 우측 페이드 그라데이션 + 안내 힌트를 노출한다. 계산은 데이터셋 상세 탭 바와 공유하는 훅에 있다.
  const [container, setContainer] = React.useState<HTMLDivElement | null>(null)
  const overflow = useHorizontalOverflow(container)

  // 표 면은 카드 단계(bg-card) — 목록 화면처럼 표가 바탕(--background) 위에 바로 놓여도 행 구분선(--divider)·머리 띠(--muted)가
  // 설계한 카드 위 단계로 읽히게 한다(스크롤 페이드도 card 색).
  // WD-778: 카드(Card, data-slot="card") 안의 표는 칠하지 않는다(in-data-[slot=card]:bg-transparent). 다크 --card 는 반투명
  // (흰 4.5%)이라 카드 위에 또 칠하면 9% 남짓으로 겹쳐 표만 밝은 사각형으로 떠 보인다 — 카드 면이 이미 표 면 역할을 한다.
  // 이 변형은 `:where([data-slot=card]) &` 로 찍혀 특이도가 bg-card 와 같고(0,1,0) 변형 유틸이라 뒤에 오므로 카드 안에서는 이긴다.
  // rounded-[inherit]: 바깥 「rounded-md border」 상자의 모서리 밖으로 면이 삐져나오지 않게(WD-755).
  return (
    <div className="relative w-full rounded-[inherit] bg-card in-data-[slot=card]:bg-transparent">
      <div
        ref={setContainer}
        data-slot="table-container"
        // 이 표 자신의 행에 실패·빈 상태 행(table-empty.tsx 의 table-visible-content)이 있을 때만 크기 컨테이너로 켠다 (WD-958 리뷰 후속) —
        // 늘 켜면 내용에 맞춰 줄어드는 부모에서 표가 접히고 셀 안 TruncatedText 의 100cqw 가 바깥 wrapper 대신 이 상자로 풀린다. 근거는 TableVisibleWidthCell JSDoc.
        // 주의: 실패·빈 상태 행이 보일 때는 inline-size 컨테이너가 켜져 표 상자의 내재 폭이 0 이 된다 — inline-block·w-fit·폭 없는 flex/grid 부모 안의 표는
        // 그 행이 보일 때만 접힐 수 있으니 그런 자리에는 폭을 준다.
        className="relative w-full overflow-x-auto has-[>table>tbody>tr>td>[data-slot=table-visible-content]]:[container-type:inline-size]"
      >
        <table
          data-slot="table"
          className={cn("w-full caption-bottom text-sm", className)}
          {...props}
        />
      </div>
      {/* 좌측 페이드 + 힌트 — 스크롤이 시작된 후에만 표시.
          이슈 #505: 배경색(거의 흰색)에서 투명으로 가는 그라데이션만으로는
          명도 차이가 거의 없어 육안 식별이 사실상 불가능했다. 페이드 폭을 넓히고
          테두리·그림자가 있는 원형 배지 안에 chevron 아이콘을 얹어 배경색과
          무관하게 식별 가능한 신호를 추가한다.
          WD-755: 라이트 --background 는 카드보다 어두운 페이지 바탕이라, 표가 놓이는 카드 면(card)으로 덮는다.
          다크 card 는 반투명(흰 4.5%)이라 뒤 내용을 가리지 못하므로 불투명한 background 로 둔다(카드와 1.04:1 남짓). */}
      {overflow.canScrollLeft && (
        <>
          <div
            aria-hidden="true"
            data-slot="table-fade-left"
            className="pointer-events-none absolute inset-y-0 left-0 w-10 bg-gradient-to-r from-card via-card/80 to-transparent dark:from-background dark:via-background/80"
          />
          <div
            aria-hidden="true"
            data-slot="table-scroll-hint-left"
            className="pointer-events-none absolute left-1.5 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded-full border bg-card shadow-sm dark:bg-background"
          >
            <ChevronLeft className="h-3.5 w-3.5 text-foreground" />
          </div>
        </>
      )}
      {/* 우측 페이드 + 힌트 — 잘린 컬럼이 있을 때만 표시 (이슈 #505·WD-755 참고) */}
      {overflow.canScrollRight && (
        <>
          <div
            aria-hidden="true"
            data-slot="table-fade-right"
            className="pointer-events-none absolute inset-y-0 right-0 w-10 bg-gradient-to-l from-card via-card/80 to-transparent dark:from-background dark:via-background/80"
          />
          <div
            aria-hidden="true"
            data-slot="table-scroll-hint-right"
            className="pointer-events-none absolute right-1.5 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded-full border bg-card shadow-sm dark:bg-background"
          >
            <ChevronRight className="h-3.5 w-3.5 text-foreground" />
          </div>
        </>
      )}
    </div>
  )
}

// WD-755: 본문 행(TableRow)은 옅은 행 구분선(row-divider)이고, 머리와 본문 사이 선만 외곽 단계(border-border)로 둔다.
// WD-1121: 카드 안 머리 띠는 배경 3단(§1-7)의 한 단계 위라 알파 없는 bg-muted 로 칠한다(푸터와 짝).
// 칠하지 않으면 본문과 같은 면이라 선 하나로만 갈라지며, 머리 hover(muted/50)는 같은 띠 위라 따로 끄지 않는다.
function TableHeader({ className, ...props }: React.ComponentProps<"thead">) {
  return (
    <thead
      data-slot="table-header"
      className={cn("bg-muted [&_tr]:border-b [&_tr]:border-border", className)}
      {...props}
    />
  )
}

// WD-755: 마지막 행의 아래 선은 row-divider 가 스스로 뺀다(행 왼쪽 표식 border-l-* 은 남는다).
function TableBody({ className, ...props }: React.ComponentProps<"tbody">) {
  return (
    <tbody
      data-slot="table-body"
      className={className}
      {...props}
    />
  )
}

// WD-755: 푸터(합계 행)는 본문과 갈라지는 영역 띠라 알파 없는 단색 bg-muted — 구조 띠에 /50 을 쓰지 않는다(§1-7).
// 푸터 행은 클릭 대상이 아니라 TableRow 의 hover(muted/50)가 띠 위에서 보이지 않아도 잃는 상태가 없다.
function TableFooter({ className, ...props }: React.ComponentProps<"tfoot">) {
  return (
    <tfoot
      data-slot="table-footer"
      className={cn(
        "bg-muted border-t font-medium [&>tr]:last:border-b-0",
        className
      )}
      {...props}
    />
  )
}

// WD-755: 행 아래 선은 row-divider(design-tokens §1-7)
function TableRow({ className, ...props }: React.ComponentProps<"tr">) {
  return (
    <tr
      data-slot="table-row"
      className={cn(
        "hover:bg-muted/50 data-[state=selected]:bg-muted row-divider transition-colors",
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
          // WD-880: 최소 클릭 대상 32px — py-1(높이 28) 대신 min-h-8 로 상자만 키운다(글자·아이콘 크기는 그대로, th 높이 h-10 안에 든다)
          "inline-flex min-h-8 items-center gap-1.5 -mx-2 px-2 rounded-sm",
          "hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
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
