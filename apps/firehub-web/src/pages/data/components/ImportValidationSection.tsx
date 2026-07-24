import { AlertTriangle, CheckCircle2, Loader2 } from 'lucide-react';

import { Button } from '../../../components/ui/button';
import { Card } from '../../../components/ui/card';
import type { ImportValidateResponse, ValidationErrorDetail } from '../../../types/dataImport';
import { ValidationErrorTable } from './ValidationErrorTable';

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
              <Loader2 className="h-4 w-4 animate-spin" />
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
                <CheckCircle2 className="h-5 w-5 text-success flex-shrink-0 mt-0.5" />
                <div className="text-sm">
                  <p className="font-medium text-success">검증 성공</p>
                  <p className="text-muted-foreground">
                    유효 {validationResult.validRows.toLocaleString()}행
                  </p>
                </div>
              </>
            ) : (
              <>
                <AlertTriangle className="h-5 w-5 text-warning flex-shrink-0 mt-0.5" />
                <div className="text-sm">
                  <p className="font-medium text-warning">검증 오류 발견</p>
                  <p className="text-muted-foreground">
                    유효 {validationResult.validRows.toLocaleString()}행 / 오류{' '}
                    <span className="text-destructive font-medium">
                      {validationResult.errorRows.toLocaleString()}건
                    </span>
                  </p>
                </div>
              </>
            )}
          </div>
          {/* 사전 검증은 전량이 아니라 앞부분 샘플만 검사한다. 사용자가 "오류 0 = 전체 안전"으로 오해하지 않게 명시. */}
          <p className="text-sm text-muted-foreground">
            샘플 {validationResult.sampleSize.toLocaleString()}행 검사 결과 · 전체 데이터 검증은 임포트 시 수행됩니다
          </p>

          {validationResult.errors.length > 0 && (
            <div className="space-y-2">
              <details className="group" open={validationResult.errorRows <= 10}>
                <summary className="text-xs font-medium text-muted-foreground cursor-pointer hover:text-foreground">
                  오류 상세 보기 ({validationResult.errors.length}개)
                </summary>
                <div className="mt-2 rounded-md border">
                  <ValidationErrorTable errors={displayedErrors} />
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
