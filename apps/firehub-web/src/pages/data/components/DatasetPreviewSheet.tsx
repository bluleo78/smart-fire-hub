import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '../../../components/ui/dialog';
import { Button } from '../../../components/ui/button';
import { Skeleton } from '../../../components/ui/skeleton';
import { datasetsApi } from '../../../api/datasets';
import type { DataQueryResponse } from '../../../types/dataset';

interface DatasetPreviewSheetProps {
  datasetId: number;
  datasetName: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function DatasetPreviewSheet({ datasetId, datasetName, open, onOpenChange }: DatasetPreviewSheetProps) {
  const navigate = useNavigate();
  const [previewData, setPreviewData] = useState<DataQueryResponse | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    if (!open || !datasetId) return;
    setIsLoading(true);
    datasetsApi.getDatasetData(datasetId, { size: 5, page: 0, includeTotalCount: true })
      .then(r => setPreviewData(r.data))
      .catch(() => setPreviewData(null))
      .finally(() => setIsLoading(false));
  }, [open, datasetId]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-4xl max-h-[80vh] overflow-auto">
        <DialogHeader>
          <DialogTitle>미리보기: {datasetName}</DialogTitle>
        </DialogHeader>

        {isLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-8 w-full" />
            ))}
          </div>
        ) : previewData && previewData.columns.length > 0 ? (
          <div className="space-y-4">
            <div className="text-sm text-muted-foreground">
              총 {previewData.totalElements >= 0 ? previewData.totalElements.toLocaleString() : '?'}행 중 상위 5행
            </div>
            <div className="overflow-x-auto rounded-md border">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b bg-muted/50">
                    {previewData.columns.map((col) => (
                      <th key={col.columnName} className="px-3 py-2 text-left font-medium text-muted-foreground whitespace-nowrap">
                        {col.displayName || col.columnName}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {previewData.rows.map((row, i) => (
                    <tr key={i} className="border-b last:border-0 hover:bg-muted/30">
                      {previewData.columns.map((col) => (
                        <td key={col.columnName} className="px-3 py-2 whitespace-nowrap max-w-[200px] truncate">
                          {row[col.columnName] == null ? (
                            <span className="text-muted-foreground italic">null</span>
                          ) : String(row[col.columnName])}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex justify-end">
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  onOpenChange(false);
                  navigate(`/data/datasets/${datasetId}`);
                }}
              >
                상세 보기
              </Button>
            </div>
          </div>
        ) : (
          <div className="text-center text-muted-foreground py-8">
            데이터를 불러올 수 없습니다.
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
