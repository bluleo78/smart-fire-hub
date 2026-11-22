import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react';
import type { ReactNode } from 'react';

import { ARIA_DISABLED_CLASS } from '../PendingButton';
import { Button } from './button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './select';

/**
 * 방향 버튼(처음·이전·다음·마지막) 하나.
 * disabled 로 잠그면 이동 뒤 포커스가 실린 버튼이 blur 돼 body 로 떨어지므로(WD-932) aria-disabled 로 잠근다.
 * 잠긴 상태의 클릭(보조 기술·강제 클릭 포함)은 onActivate 를 부르지 않는다.
 */
function NavButton({
  label,
  disabled,
  onActivate,
  children,
}: {
  label: string;
  disabled: boolean;
  onActivate: () => void;
  children: ReactNode;
}) {
  return (
    <Button
      variant="outline"
      size="sm"
      onClick={() => {
        if (!disabled) onActivate();
      }}
      aria-disabled={disabled || undefined}
      className={ARIA_DISABLED_CLASS}
      aria-label={label}
    >
      {children}
    </Button>
  );
}

interface SimplePaginationProps {
  page: number;
  totalPages: number;
  onPageChange: (page: number) => void;
  totalElements?: number;
  pageSize?: number;
  /** 페이지 사이즈 변경 콜백. 있으면 사이즈 selector 표시. */
  onPageSizeChange?: (size: number) => void;
  /** 페이지 사이즈 옵션 (기본 10/20/50/100) */
  pageSizeOptions?: number[];
}

/**
 * 페이지 번호 버튼에 표시할 항목 계산.
 * - 현재 페이지 ±2 표시
 * - 양 끝과 거리가 멀면 "..." 생략 기호 삽입
 * - 페이지 인덱스는 0-based 내부값, 화면 표기는 1-based
 */
function computePageItems(current: number, total: number): (number | 'ellipsis-l' | 'ellipsis-r')[] {
  // 페이지가 7개 이하면 전부 표시
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i);
  }

  const items: (number | 'ellipsis-l' | 'ellipsis-r')[] = [];
  const left = Math.max(1, current - 2);
  const right = Math.min(total - 2, current + 2);

  // 항상 첫 페이지 표시
  items.push(0);

  // 좌측 ellipsis
  if (left > 1) {
    items.push('ellipsis-l');
  }

  // 중간 페이지
  for (let i = left; i <= right; i++) {
    items.push(i);
  }

  // 우측 ellipsis
  if (right < total - 2) {
    items.push('ellipsis-r');
  }

  // 항상 마지막 페이지 표시
  items.push(total - 1);

  return items;
}

/**
 * 공통 페이지네이션 컴포넌트.
 * - 처음/이전/페이지번호/다음/마지막 버튼 제공
 * - 페이지 번호: 현재 ±2 + 양 끝 + "..." 생략 패턴
 * - onPageSizeChange 전달 시 페이지 사이즈 selector 함께 표시
 * - totalPages <= 1 이고 사이즈 selector 도 없으면 렌더링 생략
 */
export function SimplePagination({
  page,
  totalPages,
  onPageChange,
  totalElements,
  pageSize,
  onPageSizeChange,
  pageSizeOptions = [10, 20, 50, 100],
}: SimplePaginationProps) {
  // 보여줄 게 아무것도 없으면 숨김 (단, 사이즈 selector 가 있으면 한 페이지여도 표시)
  if (totalPages <= 1 && !onPageSizeChange) return null;

  const showCount = totalElements !== undefined && pageSize !== undefined;
  const showSizeSelector = onPageSizeChange !== undefined && pageSize !== undefined;
  const items = totalPages > 0 ? computePageItems(page, totalPages) : [];
  const isFirst = page === 0;
  const isLast = page >= totalPages - 1;

  // 좌측 정보 영역: "총 N건 중 a-b" + 페이지 사이즈 selector
  const leftInfo = (
    <div className="flex items-center gap-3">
      {showCount && (
        <p className="text-sm text-muted-foreground">
          총 {totalElements}건
          {/* 범위 밖 페이지(낡은 북마크 ?page=999)면 「9991-15」처럼 깨진 범위가 나오므로 범위를 숨긴다(WD-146) */}
          {totalElements! > 0 && page * pageSize! < totalElements! && (
            <>
              {' '}중 {page * pageSize! + 1}-
              {Math.min((page + 1) * pageSize!, totalElements!)}
            </>
          )}
        </p>
      )}
      {showSizeSelector && (
        <div className="flex items-center gap-2">
          <span className="text-sm text-muted-foreground">페이지당</span>
          <Select
            value={String(pageSize)}
            onValueChange={(v) => onPageSizeChange!(Number(v))}
          >
            <SelectTrigger size="sm" className="w-[80px]" aria-label="페이지 사이즈">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {pageSizeOptions.map((opt) => (
                <SelectItem key={opt} value={String(opt)}>
                  {opt}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}
    </div>
  );

  // 페이지가 1개 이하면 좌측 정보만 표시 (사이즈 selector 노출 목적)
  if (totalPages <= 1) {
    return <div className="flex items-center justify-between">{leftInfo}<div /></div>;
  }

  // 페이지 네비게이션 버튼들 (aria 명시를 위해 <nav> 사용)
  // 버튼 폭 합계가 표시 페이지 수에 비례해 커지므로 320px 같은 좁은 뷰포트에서 가로로 넘친다
  // (WCAG SC 1.4.10 Reflow). max-sm 한정으로만 줄바꿈시켜 sm 이상 데스크톱 한 줄 표현은 그대로 둔다.
  const nav = (
    <nav
      className="flex items-center gap-1 max-sm:flex-wrap max-sm:justify-center"
      aria-label="페이지네이션"
    >
      <NavButton label="처음 페이지" disabled={isFirst} onActivate={() => onPageChange(0)}>
        <ChevronsLeft className="h-4 w-4" />
      </NavButton>
      <NavButton label="이전 페이지" disabled={isFirst} onActivate={() => onPageChange(Math.max(0, page - 1))}>
        <ChevronLeft className="h-4 w-4" />
      </NavButton>
      {items.map((item, idx) => {
        if (item === 'ellipsis-l' || item === 'ellipsis-r') {
          return (
            <span
              key={`${item}-${idx}`}
              className="px-2 text-sm text-muted-foreground select-none"
              aria-hidden="true"
            >
              ...
            </span>
          );
        }
        const active = item === page;
        return (
          <Button
            key={item}
            variant={active ? 'default' : 'outline'}
            size="sm"
            onClick={() => onPageChange(item)}
            aria-label={`${item + 1} 페이지`}
            aria-current={active ? 'page' : undefined}
            className="min-w-[36px]"
          >
            {item + 1}
          </Button>
        );
      })}
      <NavButton label="다음 페이지" disabled={isLast} onActivate={() => onPageChange(Math.min(totalPages - 1, page + 1))}>
        <ChevronRight className="h-4 w-4" />
      </NavButton>
      <NavButton label="마지막 페이지" disabled={isLast} onActivate={() => onPageChange(totalPages - 1)}>
        <ChevronsRight className="h-4 w-4" />
      </NavButton>
    </nav>
  );

  // 좌측 정보 + 우측 네비게이션
  if (showCount || showSizeSelector) {
    return (
      <div className="flex flex-wrap items-center justify-between gap-3">
        {leftInfo}
        {nav}
      </div>
    );
  }

  // 카운트/사이즈 정보 없이 네비게이션만 (중앙 정렬)
  return <div className="flex items-center justify-center">{nav}</div>;
}
