import React, { useMemo } from 'react';
import { useImports } from '../../../hooks/queries/useDatasets';
import type { DatasetDetailResponse } from '../../../types/dataset';
import { Badge } from '../../../components/ui/badge';
import { formatDate, formatFileSize, getStatusBadgeVariant, getStatusLabel } from '../../../lib/formatters';

interface DatasetHistoryTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

export const DatasetHistoryTab = React.memo(function DatasetHistoryTab({
  dataset,
  datasetId,
}: DatasetHistoryTabProps) {
  const { data: imports } = useImports(datasetId);

  const sortedImports = useMemo(() => {
    if (!imports) return [];
    return [...imports].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  }, [imports]);

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold">변경 이력</h2>
      <div className="space-y-6">
        {sortedImports.length > 0 &&
          sortedImports.map((imp) => (
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
                  {imp.errorDetails && Object.keys(imp.errorDetails).length > 0 && (
                    <details className="mt-2">
                      <summary className="text-xs text-muted-foreground cursor-pointer hover:text-foreground">
                        오류 상세 보기
                      </summary>
                      <pre className="mt-1 text-xs bg-muted/50 rounded p-2 overflow-x-auto whitespace-pre-wrap">
                        {JSON.stringify(imp.errorDetails, null, 2)}
                      </pre>
                    </details>
                  )}
                </div>
              </div>
            </div>
          ))}

        {/* Dataset creation event */}
        <div className="flex gap-4">
          <div className="flex flex-col items-center">
            <div className="h-2.5 w-2.5 rounded-full bg-muted-foreground mt-1.5" />
          </div>
          <div className="flex-1">
            <p className="text-sm font-medium">{dataset.createdBy}님이 데이터셋을 생성했습니다</p>
            <p className="text-xs text-muted-foreground mt-0.5">{formatDate(dataset.createdAt)}</p>
          </div>
        </div>
      </div>
    </div>
  );
});
