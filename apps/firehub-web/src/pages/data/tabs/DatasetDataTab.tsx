import React, { useMemo, lazy, Suspense } from 'react';
import { useDatasetData } from '../../../hooks/queries/useDatasets';
import { dataImportsApi } from '../../../api/dataImports';
import type { DatasetDetailResponse } from '../../../types/dataset';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { Card } from '../../../components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../../components/ui/table';
import { ChevronLeft, ChevronRight, Download, Upload, Search } from 'lucide-react';
import { toast } from 'sonner';
import { formatCellValue } from '../../../lib/formatters';
import type { ErrorResponse } from '../../../types/auth';
import axios from 'axios';

const ImportMappingDialog = lazy(() =>
  import('../components/ImportMappingDialog').then((m) => ({ default: m.ImportMappingDialog }))
);

interface DatasetDataTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

export const DatasetDataTab = React.memo(function DatasetDataTab({
  dataset,
  datasetId,
}: DatasetDataTabProps) {
  const [dataPage, setDataPage] = React.useState(0);
  const dataSize = 20;
  const [dataSearch, setDataSearch] = React.useState('');
  const [debouncedSearch, setDebouncedSearch] = React.useState('');
  const [importDialogOpen, setImportDialogOpen] = React.useState(false);

  const { data: dataQueryResult } = useDatasetData(datasetId, {
    search: debouncedSearch || undefined,
    page: dataPage,
    size: dataSize,
  });

  React.useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(dataSearch);
      setDataPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [dataSearch]);

  const handleExport = async () => {
    try {
      const response = await dataImportsApi.exportCsv(datasetId);
      const blob = new Blob([response.data], { type: 'text/csv;charset=utf-8' });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${dataset?.tableName || 'export'}_export.csv`;
      a.click();
      window.URL.revokeObjectURL(url);
      toast.success('CSV 파일이 다운로드되었습니다.');
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '내보내기에 실패했습니다.');
      } else {
        toast.error('내보내기에 실패했습니다.');
      }
    }
  };

  const rows = useMemo(() => dataQueryResult?.rows || [], [dataQueryResult?.rows]);
  const totalDataPages = useMemo(
    () => dataQueryResult?.totalPages || 0,
    [dataQueryResult?.totalPages]
  );

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="relative flex-1 max-w-sm">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="text"
            placeholder="데이터 검색..."
            value={dataSearch}
            onChange={(e) => setDataSearch(e.target.value)}
            maxLength={200}
            className="pl-9"
          />
        </div>
        <Button variant="outline" onClick={() => setImportDialogOpen(true)}>
          <Upload className="mr-2 h-4 w-4" />
          임포트
        </Button>
        <Button variant="outline" onClick={handleExport}>
          <Download className="mr-2 h-4 w-4" />
          CSV 내보내기
        </Button>
      </div>

      <h2 className="text-lg font-semibold">
        데이터 ({dataQueryResult?.totalElements.toLocaleString() || 0}행)
      </h2>

      {dataQueryResult && rows.length > 0 ? (
        <>
          <div className="rounded-md border overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  {dataQueryResult.columns.map((col) => (
                    <TableHead key={col.id}>{col.displayName || col.columnName}</TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row, idx) => (
                  <TableRow key={idx}>
                    {dataQueryResult.columns.map((col) => (
                      <TableCell key={col.id} className="max-w-xs truncate">
                        {formatCellValue(row[col.columnName])}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>

          {totalDataPages > 1 && (
            <div className="flex items-center justify-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setDataPage((p) => Math.max(0, p - 1))}
                disabled={dataPage === 0}
              >
                <ChevronLeft className="h-4 w-4" />
              </Button>
              <span className="text-sm">
                {dataPage + 1} / {totalDataPages}
              </span>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setDataPage((p) => Math.min(totalDataPages - 1, p + 1))}
                disabled={dataPage >= totalDataPages - 1}
              >
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          )}
        </>
      ) : (
        <Card className="p-8">
          <p className="text-center text-muted-foreground">
            {dataSearch ? '검색 결과가 없습니다.' : '데이터가 없습니다.'}
          </p>
        </Card>
      )}

      {/* Import Dialog */}
      {importDialogOpen && (
        <Suspense fallback={null}>
          <ImportMappingDialog
            open={importDialogOpen}
            onOpenChange={setImportDialogOpen}
            datasetId={datasetId}
            datasetColumns={dataset.columns}
          />
        </Suspense>
      )}
    </div>
  );
});
