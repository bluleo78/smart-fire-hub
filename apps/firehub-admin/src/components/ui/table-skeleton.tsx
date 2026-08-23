// 출처: apps/firehub-web/src/components/ui/table-skeleton.tsx 에서 복사(P7-c2a, R-2).
// 공유 패키지로 추출하지 않는 것이 확정 결정이다. 원본을 고칠 일이 생기면 양쪽을 함께 본다.
import { TableRow, TableCell } from './table';
import { Skeleton } from './skeleton';

interface TableSkeletonRowsProps {
  columns: number;
  rows?: number;
  widths?: string[];
}

export function TableSkeletonRows({ columns, rows = 5, widths }: TableSkeletonRowsProps) {
  return (
    <>
      {Array.from({ length: rows }).map((_, i) => (
        <TableRow key={i}>
          {Array.from({ length: columns }).map((_, j) => (
            <TableCell key={j}>
              <Skeleton className={`h-4 ${widths?.[j] ?? 'w-full'}`} />
            </TableCell>
          ))}
        </TableRow>
      ))}
    </>
  );
}
