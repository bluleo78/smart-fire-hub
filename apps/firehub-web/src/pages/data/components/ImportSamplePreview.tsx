import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../../../components/ui/table';
import type { ColumnMappingEntry,ImportPreviewResponse } from '../../../types/dataImport';
import type { DatasetColumnResponse } from '../../../types/dataset';
import { fileColumnLabel, mappingAt } from '../hooks/useImportDialog';

interface ImportSamplePreviewProps {
  previewData: ImportPreviewResponse;
  mappings: ColumnMappingEntry[];
  datasetColumns: DatasetColumnResponse[];
}

export function ImportSamplePreview({ previewData, mappings, datasetColumns }: ImportSamplePreviewProps) {
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold">샘플 데이터 (상위 5행)</h3>
      <div className="rounded-md border overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              {previewData.fileHeaders.map((header, colIdx) => {
                // 매핑은 파일 열과 같은 순서라 위치로 고르고, 키도 위치로 둔다 — 이름이 같은(빈) 열끼리 겹치지 않게 (WD-369)
                const mapping = mappingAt(mappings, colIdx, header);
                const displayName = mapping?.datasetColumn
                  ? datasetColumns.find((col) => col.columnName === mapping.datasetColumn)?.displayName ||
                    mapping.datasetColumn
                  : fileColumnLabel(header, colIdx);
                return (
                  <TableHead key={colIdx} className="text-xs whitespace-nowrap px-2">
                    {displayName}
                  </TableHead>
                );
              })}
            </TableRow>
          </TableHeader>
          <TableBody>
            {previewData.sampleRows.slice(0, 5).map((row, idx) => (
              <TableRow key={idx}>
                {previewData.fileHeaders.map((header, colIdx) => (
                  <TableCell key={colIdx} className="text-xs px-2 max-w-[100px] truncate">
                    {row[header] || '-'}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
