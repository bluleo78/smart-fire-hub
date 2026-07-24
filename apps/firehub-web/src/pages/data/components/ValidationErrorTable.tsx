import type { ValidationErrorDetail } from '../../../types/dataImport';

/**
 * 행/컬럼/값/오류 4열 검증 오류 테이블.
 * 사전 검증(ImportValidationSection), 임포트 진행 실패(ImportProgressView),
 * 변경 이력(DatasetHistoryTab) 3곳에서 공유하는 재사용 컴포넌트.
 */
export function ValidationErrorTable({ errors }: { errors: ValidationErrorDetail[] }) {
  if (!errors?.length) return null;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-muted-foreground">
            <th className="py-1 pr-4">행</th>
            <th className="py-1 pr-4">컬럼</th>
            <th className="py-1 pr-4">값</th>
            <th className="py-1">오류</th>
          </tr>
        </thead>
        <tbody>
          {errors.map((e, i) => (
            <tr key={i} className="border-t border-border">
              <td className="py-1 pr-4">{e.rowNumber}</td>
              <td className="py-1 pr-4">{e.columnName}</td>
              <td className="py-1 pr-4 font-mono">{e.value}</td>
              <td className="py-1 text-destructive">{e.error}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
