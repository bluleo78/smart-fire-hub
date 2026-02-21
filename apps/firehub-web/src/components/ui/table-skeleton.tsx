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
