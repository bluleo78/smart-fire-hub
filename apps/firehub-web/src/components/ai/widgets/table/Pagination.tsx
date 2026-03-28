import { ChevronLeft, ChevronRight } from 'lucide-react';
import { cn } from '../../../../lib/utils';

interface PaginationProps {
  currentPage: number; // 0-based
  totalPages: number;
  totalItems: number;
  pageSize: number;
  onPageChange: (page: number) => void;
}

function buildPageNumbers(current: number, total: number): (number | '...')[] {
  if (total <= 5) {
    return Array.from({ length: total }, (_, i) => i);
  }

  const pages: (number | '...')[] = [];

  pages.push(0);

  if (current > 2) {
    pages.push('...');
  }

  const start = Math.max(1, current - 1);
  const end = Math.min(total - 2, current + 1);
  for (let i = start; i <= end; i++) {
    pages.push(i);
  }

  if (current < total - 3) {
    pages.push('...');
  }

  pages.push(total - 1);

  return pages;
}

export function Pagination({
  currentPage,
  totalPages,
  totalItems,
  pageSize,
  onPageChange,
}: PaginationProps) {
  const displayStart = totalItems === 0 ? 0 : currentPage * pageSize + 1;
  const displayEnd = Math.min((currentPage + 1) * pageSize, totalItems);

  const pageNumbers = buildPageNumbers(currentPage, totalPages);

  return (
    <div className="flex items-center justify-between border-t border-border bg-muted/20 px-3 py-1.5">
      <span className="text-xs text-muted-foreground">
        {totalItems === 0
          ? '결과 없음'
          : `${totalItems.toLocaleString('ko-KR')}건 중 ${displayStart.toLocaleString('ko-KR')}–${displayEnd.toLocaleString('ko-KR')}건 표시`}
      </span>

      {totalPages > 1 && (
        <div className="flex items-center gap-0.5">
          <button
            type="button"
            disabled={currentPage === 0}
            onClick={() => onPageChange(currentPage - 1)}
            className="flex h-6 w-6 items-center justify-center rounded text-xs text-muted-foreground hover:bg-muted disabled:opacity-30"
            aria-label="이전 페이지"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
          </button>

          {pageNumbers.map((p, idx) =>
            p === '...' ? (
              <span key={`ellipsis-${idx}`} className="flex h-6 w-5 items-center justify-center text-xs text-muted-foreground">
                …
              </span>
            ) : (
              <button
                key={p}
                type="button"
                onClick={() => onPageChange(p)}
                className={cn(
                  'flex h-6 w-6 items-center justify-center rounded text-xs',
                  p === currentPage
                    ? 'bg-primary text-primary-foreground'
                    : 'text-muted-foreground hover:bg-muted',
                )}
              >
                {p + 1}
              </button>
            ),
          )}

          <button
            type="button"
            disabled={currentPage >= totalPages - 1}
            onClick={() => onPageChange(currentPage + 1)}
            className="flex h-6 w-6 items-center justify-center rounded text-xs text-muted-foreground hover:bg-muted disabled:opacity-30"
            aria-label="다음 페이지"
          >
            <ChevronRight className="h-3.5 w-3.5" />
          </button>
        </div>
      )}
    </div>
  );
}
