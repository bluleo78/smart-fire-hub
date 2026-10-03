import { TableCell, TableRow } from '@/components/ui/table';

/**
 * 목록 표의 조회 실패 행(WD-16). 빈 결과("없음")와 구별되는 "불러오지 못함" 문구 — 테넌트·계정·감사 로그 세 목록이
 * 같은 문구·스타일을 쓰도록 한 곳에 둔다. components/ui 가 아닌 이유: shadcn 생성물이 아니라 앱 조합 컴포넌트다.
 */
export function TableErrorRow({ colSpan }: { colSpan: number }) {
  return (
    <TableRow>
      <TableCell colSpan={colSpan} className="text-center text-destructive">
        데이터를 불러오는데 실패했습니다.
      </TableCell>
    </TableRow>
  );
}
