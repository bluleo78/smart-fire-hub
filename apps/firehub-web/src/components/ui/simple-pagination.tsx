import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from './button';

interface SimplePaginationProps {
  page: number;
  totalPages: number;
  onPageChange: (page: number) => void;
  totalElements?: number;
  pageSize?: number;
}

export function SimplePagination({
  page,
  totalPages,
  onPageChange,
  totalElements,
  pageSize,
}: SimplePaginationProps) {
  if (totalPages <= 1) return null;

  const showCount = totalElements !== undefined && pageSize !== undefined;

  if (showCount) {
    const start = page * pageSize + 1;
    const end = Math.min((page + 1) * pageSize, totalElements);

    return (
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          총 {totalElements}건 중 {start}-{end}
        </p>
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => onPageChange(Math.max(0, page - 1))}
            disabled={page === 0}
          >
            <ChevronLeft className="h-4 w-4" />
            이전
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => onPageChange(Math.min(totalPages - 1, page + 1))}
            disabled={page >= totalPages - 1}
          >
            다음
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex items-center justify-center gap-2">
      <Button
        variant="outline"
        size="sm"
        onClick={() => onPageChange(Math.max(0, page - 1))}
        disabled={page === 0}
      >
        <ChevronLeft className="h-4 w-4" />
      </Button>
      <span className="text-sm">
        {page + 1} / {totalPages}
      </span>
      <Button
        variant="outline"
        size="sm"
        onClick={() => onPageChange(Math.min(totalPages - 1, page + 1))}
        disabled={page >= totalPages - 1}
      >
        <ChevronRight className="h-4 w-4" />
      </Button>
    </div>
  );
}
