import { TableRow, TableCell } from './table';

interface TableEmptyRowProps {
  colSpan: number;
  message?: string;
}

export function TableEmptyRow({ colSpan, message = '데이터가 없습니다.' }: TableEmptyRowProps) {
  return (
    <TableRow>
      <TableCell colSpan={colSpan} className="text-center text-muted-foreground py-8">
        {message}
      </TableCell>
    </TableRow>
  );
}
