import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../../../components/ui/table';
import type { DatasetColumnResponse } from '../../../types/dataset';
import type { ImportPreviewResponse, ColumnMappingEntry } from '../../../types/dataImport';

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
              {previewData.fileHeaders.map((header) => {
                const mapping = mappings.find((m) => m.fileColumn === header);
                const displayName = mapping?.datasetColumn
                  ? datasetColumns.find((col) => col.columnName === mapping.datasetColumn)?.displayName ||
                    mapping.datasetColumn
                  : header;
                return (
                  <TableHead key={header} className="text-xs whitespace-nowrap px-2">
                    {displayName}
                  </TableHead>
                );
              })}
            </TableRow>
          </TableHeader>
          <TableBody>
            {previewData.sampleRows.slice(0, 5).map((row, idx) => (
              <TableRow key={idx}>
                {previewData.fileHeaders.map((header) => (
                  <TableCell key={header} className="text-xs px-2 max-w-[100px] truncate">
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
