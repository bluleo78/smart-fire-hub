import { Button } from '../../../components/ui/button';
import { Card } from '../../../components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../../../components/ui/table';
import { AlertTriangle, CheckCircle2, Loader2 } from 'lucide-react';
import type { ImportValidateResponse, ValidationErrorDetail } from '../../../types/dataImport';

interface ImportValidationSectionProps {
  validationResult: ImportValidateResponse | null;
  showAllErrors: boolean;
  displayedErrors: ValidationErrorDetail[];
  hasUnmappedRequired: boolean;
  isValidating: boolean;
  onValidate: () => void;
  onShowAllErrors: (show: boolean) => void;
}

export function ImportValidationSection({
  validationResult,
  showAllErrors,
  displayedErrors,
  hasUnmappedRequired,
  isValidating,
  onValidate,
  onShowAllErrors,
}: ImportValidationSectionProps) {
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">검증</h3>
        <Button
          variant="outline"
          size="sm"
          onClick={onValidate}
          disabled={isValidating || hasUnmappedRequired}
        >
          {isValidating ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              검증 중...
            </>
          ) : (
            '검증'
          )}
        </Button>
      </div>

      {validationResult && (
        <Card className="p-4 space-y-3">
          <div className="flex items-start gap-2">
            {validationResult.errorRows === 0 ? (
              <>
                <CheckCircle2 className="h-5 w-5 text-green-600 flex-shrink-0 mt-0.5" />
                <div className="text-sm">
                  <p className="font-medium text-green-600">검증 성공</p>
                  <p className="text-muted-foreground">
                    전체 {validationResult.totalRows.toLocaleString()}행 / 유효{' '}
                    {validationResult.validRows.toLocaleString()}행
                  </p>
                </div>
              </>
            ) : (
              <>
                <AlertTriangle className="h-5 w-5 text-amber-600 flex-shrink-0 mt-0.5" />
                <div className="text-sm">
                  <p className="font-medium text-amber-600">검증 오류 발견</p>
                  <p className="text-muted-foreground">
                    전체 {validationResult.totalRows.toLocaleString()}행 / 유효{' '}
                    {validationResult.validRows.toLocaleString()}행 / 오류{' '}
                    <span className="text-destructive font-medium">
                      {validationResult.errorRows.toLocaleString()}행
                    </span>
                  </p>
                </div>
              </>
            )}
          </div>

          {validationResult.errors.length > 0 && (
            <div className="space-y-2">
              <details className="group" open={validationResult.errorRows <= 10}>
                <summary className="text-xs font-medium text-muted-foreground cursor-pointer hover:text-foreground">
                  오류 상세 보기 ({validationResult.errors.length}개)
                </summary>
                <div className="mt-2 rounded-md border overflow-x-auto">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>행</TableHead>
                        <TableHead>컬럼</TableHead>
                        <TableHead>값</TableHead>
                        <TableHead>오류</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {displayedErrors.map((err, idx) => (
                        <TableRow key={idx}>
                          <TableCell>{err.rowNumber}</TableCell>
                          <TableCell className="font-medium">{err.columnName}</TableCell>
                          <TableCell className="max-w-xs truncate">{err.value || '-'}</TableCell>
                          <TableCell className="text-destructive text-xs">{err.error}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
                {validationResult.errors.length > 100 && !showAllErrors && (
                  <Button
                    variant="ghost"
                    size="sm"
                    className="w-full mt-2"
                    onClick={() => onShowAllErrors(true)}
                  >
                    전체 오류 보기 ({validationResult.errors.length}개)
                  </Button>
                )}
              </details>
            </div>
          )}
        </Card>
      )}
    </div>
  );
}
