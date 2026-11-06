import React, { useState } from 'react';

import { Badge } from '../../../components/ui/badge';
import { SimplePagination } from '../../../components/ui/simple-pagination';
import { IMPORTS_PAGE_SIZE, useImports } from '../../../hooks/queries/useDatasets';
import { parseErrorDetails } from '../../../lib/errorDetails';
import { formatDate, formatFileSize, getStatusBadgeVariant, getStatusLabel } from '../../../lib/formatters';
import type { DatasetDetailResponse } from '../../../types/dataset';
import { ValidationErrorTable } from '../components/ValidationErrorTable';

interface DatasetHistoryTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

export const DatasetHistoryTab = React.memo(function DatasetHistoryTab({
  dataset,
  datasetId,
}: DatasetHistoryTabProps) {
  // 이력은 서버가 최신 순으로 잘라 한 페이지씩 준다(WD-535) — 화면에서 다시 정렬하지 않는다.
  // 페이지 번호는 데이터셋별로 기억한다: 다른 데이터셋으로 옮겨 가면 그 데이터셋의 첫 페이지부터 보이도록 datasetId 와 함께 둔다.
  const [pageState, setPageState] = useState({ datasetId, page: 0 });
  const page = pageState.datasetId === datasetId ? pageState.page : 0;
  const { data: importsPage } = useImports(datasetId, page);
  const imports = importsPage?.content ?? [];
  const totalPages = importsPage?.totalPages ?? 0;
  // 데이터셋 생성은 가장 오래된 사건이라 마지막 페이지(이력이 없으면 totalPages 0 → 참)에만 맨 아래에 보인다.
  const isLastPage = page >= totalPages - 1;

  return (
    <div className="space-y-4">
      <h2 className="text-xl leading-7 font-semibold">변경 이력</h2>
      <div className="space-y-6">
        {imports.length > 0 &&
          imports.map((imp) => {
            // audit_log.metadata.errorDetails 는 { errors: ValidationErrorDetail[] } 형태의 (중첩)JSON.
            // 안전 파싱 후 테이블 렌더 — 파싱 실패/형식 불일치 시 null 반환하여 섹션 자체를 숨긴다.
            const parsedErrors = parseErrorDetails(imp.errorDetails);
            return (
            <div key={`import-${imp.id}`} className="flex gap-4">
              <div className="flex flex-col items-center">
                <div className="h-2.5 w-2.5 rounded-full bg-primary mt-1.5" />
                <div className="flex-1 w-px bg-border" />
              </div>
              <div className="flex-1 pb-6">
                <p className="text-sm font-medium">
                  {imp.importedBy}님이 데이터를 임포트했습니다
                </p>
                <p className="text-xs text-muted-foreground mt-0.5">{formatDate(imp.createdAt)}</p>
                <div className="mt-2 rounded-md border p-3 text-sm space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="text-muted-foreground">파일:</span>
                    <span>
                      {imp.fileName} ({imp.fileSize != null ? formatFileSize(imp.fileSize) : '-'})
                    </span>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-muted-foreground">상태:</span>
                    <Badge variant={getStatusBadgeVariant(imp.status)}>
                      {getStatusLabel(imp.status)}
                    </Badge>
                  </div>
                  {imp.totalRows !== null && (
                    <div className="flex items-center gap-2">
                      <span className="text-muted-foreground">결과:</span>
                      <span>
                        {imp.successRows?.toLocaleString() || 0}행 성공
                        {imp.errorRows ? ` / ${imp.errorRows.toLocaleString()}행 실패` : ''}
                        {' / '}총 {imp.totalRows.toLocaleString()}행
                      </span>
                    </div>
                  )}
                  {/* 같은 키로 버린 행이 있으면 실제 반영 행 수가 「성공」보다 적은 이유를 알린다(WD-387) */}
                  {imp.duplicateKeyRows != null && imp.duplicateKeyRows > 0 && (
                    <div className="text-xs text-muted-foreground" data-testid="import-history-duplicate-keys">
                      파일 안 같은 키 {imp.duplicateKeyRows.toLocaleString()}행은 건너뛰고 키마다 마지막 행만 반영
                    </div>
                  )}
                  {imp.status === 'FAILED' && imp.errorMessage && (
                    <div className="flex items-start gap-2">
                      <span className="text-muted-foreground shrink-0">원인:</span>
                      <span className="text-destructive text-sm">{imp.errorMessage}</span>
                    </div>
                  )}
                  {parsedErrors && (
                    <details className="mt-2">
                      <summary className="text-xs text-muted-foreground cursor-pointer hover:text-foreground">
                        검증 오류 상세
                      </summary>
                      <ValidationErrorTable errors={parsedErrors} />
                    </details>
                  )}
                </div>
              </div>
            </div>
            );
          })}

        {/* Dataset creation event */}
        {isLastPage && (
          <div className="flex gap-4">
            <div className="flex flex-col items-center">
              <div className="h-2.5 w-2.5 rounded-full bg-muted-foreground mt-1.5" />
            </div>
            <div className="flex-1">
              <p className="text-sm font-medium">{dataset.createdBy}님이 데이터셋을 생성했습니다</p>
              <p className="text-xs text-muted-foreground mt-0.5">{formatDate(dataset.createdAt)}</p>
            </div>
          </div>
        )}
      </div>
      {/* 이력이 한 페이지를 넘을 때만 보인다(SimplePagination 이 한 페이지 이하면 스스로 숨김) — 「총 N건 중 a-b」도 함께 표시(WD-535) */}
      <SimplePagination
        page={page}
        totalPages={totalPages}
        onPageChange={(next) => setPageState({ datasetId, page: next })}
        totalElements={importsPage?.totalElements}
        pageSize={IMPORTS_PAGE_SIZE}
      />
    </div>
  );
});
