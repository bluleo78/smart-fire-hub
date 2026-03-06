import { useQuery } from '@tanstack/react-query';
import { Download, FileSpreadsheet, FileText, MapPin } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';

import { exportsApi } from '../../../api/exports';
import { Button } from '../../../components/ui/button';
import { Checkbox } from '../../../components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../../components/ui/dialog';
import { Skeleton } from '../../../components/ui/skeleton';
import { useExportJobTracking } from '../../../hooks/useExportJobTracking';
import { handleApiError } from '../../../lib/api-error';
import { downloadBlob } from '../../../lib/download';
import type { ExportFormat } from '../../../types/export';

interface ExportDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  datasetId: number;
  datasetName: string;
  search?: string;
}

const FORMAT_OPTIONS: {
  value: ExportFormat;
  label: string;
  icon: typeof FileText;
}[] = [
  { value: 'CSV', label: 'CSV', icon: FileText },
  { value: 'EXCEL', label: 'Excel', icon: FileSpreadsheet },
  { value: 'GEOJSON', label: 'GeoJSON', icon: MapPin },
];

export function ExportDialog({
  open,
  onOpenChange,
  datasetId,
  datasetName,
  search,
}: ExportDialogProps) {
  const [format, setFormat] = useState<ExportFormat>('CSV');
  const [selectedColumns, setSelectedColumns] = useState<string[]>([]);
  const [isExporting, setIsExporting] = useState(false);
  const { startTracking } = useExportJobTracking();

  const { data: estimate, isLoading } = useQuery({
    queryKey: ['export-estimate', datasetId, search],
    queryFn: () =>
      exportsApi.estimateExport(datasetId, search).then((r) => r.data),
    enabled: open,
  });

  const allSelected =
    selectedColumns.length === 0 ||
    (estimate && selectedColumns.length === estimate.columns.length);

  const handleToggleAll = () => {
    if (allSelected) {
      // Deselect all — but we need at least 1
      // Actually empty = all, so toggling to "select specific" means uncheck all initially
      // We'll set it to the full list when unchecked
      if (selectedColumns.length === 0 && estimate) {
        setSelectedColumns(
          estimate.columns.slice(0, 1).map((c) => c.columnName)
        );
      } else {
        setSelectedColumns([]);
      }
    } else {
      setSelectedColumns([]);
    }
  };

  const handleToggleColumn = (columnName: string) => {
    setSelectedColumns((prev) => {
      if (prev.length === 0 && estimate) {
        // switching from "all" to specific — include all except this one
        return estimate.columns
          .map((c) => c.columnName)
          .filter((n) => n !== columnName);
      }
      if (prev.includes(columnName)) {
        const next = prev.filter((n) => n !== columnName);
        return next.length === 0 ? [] : next; // empty = all
      }
      const next = [...prev, columnName];
      if (estimate && next.length === estimate.columns.length) {
        return []; // all selected = empty
      }
      return next;
    });
  };

  const handleExport = async () => {
    if (!estimate) return;

    const geometryColumn =
      format === 'GEOJSON'
        ? estimate.columns.find((c) => c.isGeometry)?.columnName
        : undefined;

    const request = {
      format,
      columns: selectedColumns.length > 0 ? selectedColumns : undefined,
      search,
      geometryColumn,
    };

    if (estimate.async) {
      try {
        const { data } = await exportsApi.exportDatasetAsync(
          datasetId,
          request
        );
        onOpenChange(false);
        startTracking(data.jobId, datasetName);
      } catch (error) {
        handleApiError(error, '내보내기 요청에 실패했습니다.');
      }
    } else {
      setIsExporting(true);
      try {
        const response = await exportsApi.exportDataset(datasetId, request);
        const ext =
          format === 'CSV' ? 'csv' : format === 'EXCEL' ? 'xlsx' : 'geojson';
        const filename = `${datasetName}_export_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.${ext}`;
        downloadBlob(filename, response.data as Blob);
        toast.success('파일이 다운로드되었습니다.');
        onOpenChange(false);
      } catch (error) {
        handleApiError(error, '내보내기에 실패했습니다.');
      } finally {
        setIsExporting(false);
      }
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>데이터 내보내기</DialogTitle>
        </DialogHeader>

        {isLoading ? (
          <div className="space-y-4 py-4">
            <Skeleton className="h-5 w-32" />
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-32 w-full" />
          </div>
        ) : estimate ? (
          <div className="space-y-5 py-2">
            {/* Row count */}
            <p className="text-sm text-muted-foreground">
              총{' '}
              <span className="font-semibold text-foreground">
                {estimate.rowCount.toLocaleString()}
              </span>
              행
              {estimate.async && (
                <span className="ml-2 text-xs text-orange-500">
                  (대용량 — 비동기 내보내기)
                </span>
              )}
            </p>

            {/* Format selection */}
            <div className="space-y-2">
              <p className="text-sm font-medium">포맷 선택</p>
              <div className="flex gap-2">
                {FORMAT_OPTIONS.map((opt) => {
                  const disabled =
                    opt.value === 'GEOJSON' && !estimate.hasGeometryColumn;
                  const selected = format === opt.value;
                  return (
                    <Button
                      key={opt.value}
                      variant={selected ? 'default' : 'outline'}
                      size="sm"
                      disabled={disabled}
                      onClick={() => setFormat(opt.value)}
                      className="flex-1"
                    >
                      <opt.icon className="mr-1.5 h-4 w-4" />
                      {opt.label}
                    </Button>
                  );
                })}
              </div>
              {!estimate.hasGeometryColumn && (
                <p className="text-xs text-muted-foreground">
                  GEOMETRY 컬럼이 없어 GeoJSON을 사용할 수 없습니다.
                </p>
              )}
            </div>

            {/* Column selection */}
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <p className="text-sm font-medium">컬럼 선택</p>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 text-xs"
                  onClick={handleToggleAll}
                >
                  {allSelected ? '선택 해제' : '전체 선택'}
                </Button>
              </div>
              <div className="max-h-48 space-y-1 overflow-y-auto rounded-md border p-2">
                {estimate.columns.map((col) => {
                  const checked =
                    selectedColumns.length === 0 ||
                    selectedColumns.includes(col.columnName);
                  return (
                    <label
                      key={col.columnName}
                      className="flex items-center gap-2 rounded px-2 py-1 text-sm hover:bg-muted/50 cursor-pointer"
                    >
                      <Checkbox
                        checked={checked}
                        onCheckedChange={() =>
                          handleToggleColumn(col.columnName)
                        }
                      />
                      <span>
                        {col.displayName || col.columnName}
                        {col.isGeometry && (
                          <span className="ml-1 text-xs text-muted-foreground">
                            (GEOMETRY)
                          </span>
                        )}
                      </span>
                    </label>
                  );
                })}
              </div>
            </div>
          </div>
        ) : null}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            취소
          </Button>
          <Button
            onClick={handleExport}
            disabled={isLoading || isExporting || !estimate}
          >
            <Download className="mr-2 h-4 w-4" />
            {isExporting ? '내보내는 중...' : '내보내기'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
