import { Badge } from '../../../components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../../../components/ui/table';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../../components/ui/select';
import { AlertTriangle, ArrowRight } from 'lucide-react';
import type { DatasetColumnResponse } from '../../../types/dataset';
import type { ColumnMappingEntry, ColumnMappingDto } from '../../../types/dataImport';

interface ImportMappingTableProps {
  suggestedMappings: ColumnMappingDto[];
  mappings: ColumnMappingEntry[];
  hasUnmappedRequired: boolean;
  unmappedRequired: DatasetColumnResponse[];
  getAvailableDatasetColumns: (fileColumn: string) => DatasetColumnResponse[];
  onMappingChange: (fileColumn: string, datasetColumn: string | null) => void;
}

function getMatchTypeBadge(matchType: string) {
  const variants: Record<string, { variant: 'default' | 'secondary' | 'destructive' | 'outline'; label: string }> = {
    EXACT: { variant: 'default', label: '정확 일치' },
    CASE_INSENSITIVE: { variant: 'secondary', label: '대소문자' },
    DISPLAY_NAME: { variant: 'secondary', label: '표시명' },
    NORMALIZED: { variant: 'secondary', label: '유사' },
    NONE: { variant: 'destructive', label: '미매핑' },
  };
  const config = variants[matchType] || { variant: 'outline' as const, label: matchType };
  return <Badge variant={config.variant}>{config.label}</Badge>;
}

export function ImportMappingTable({
  suggestedMappings,
  mappings,
  hasUnmappedRequired,
  unmappedRequired,
  getAvailableDatasetColumns,
  onMappingChange,
}: ImportMappingTableProps) {
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold">컬럼 매핑</h3>
      {hasUnmappedRequired && (
        <div className="flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800">
          <AlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
          <div>
            <p className="font-medium">필수 필드가 매핑되지 않았습니다:</p>
            <p className="text-xs mt-1">
              {unmappedRequired.map((col) => col.displayName || col.columnName).join(', ')}
            </p>
          </div>
        </div>
      )}
      <div className="rounded-md border">
        <Table className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead className="text-xs px-2 w-[28%]">파일 컬럼</TableHead>
              <TableHead className="text-xs w-6 px-0"></TableHead>
              <TableHead className="text-xs px-2">데이터셋 컬럼</TableHead>
              <TableHead className="text-xs px-2 w-[72px]">매칭</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {suggestedMappings.map((suggestion) => {
              const currentMapping = mappings.find((m) => m.fileColumn === suggestion.fileColumn);
              const availableColumns = getAvailableDatasetColumns(suggestion.fileColumn);
              return (
                <TableRow key={suggestion.fileColumn}>
                  <TableCell className="text-xs font-medium px-2 py-1.5 truncate">
                    {suggestion.fileColumn}
                  </TableCell>
                  <TableCell className="px-0 py-1.5">
                    <ArrowRight className="h-3 w-3 text-muted-foreground" />
                  </TableCell>
                  <TableCell className="px-2 py-1.5">
                    <Select
                      value={currentMapping?.datasetColumn || '__none__'}
                      onValueChange={(value) =>
                        onMappingChange(suggestion.fileColumn, value === '__none__' ? null : value)
                      }
                    >
                      <SelectTrigger className="h-8 text-xs w-full">
                        <SelectValue placeholder="매핑 안 함" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="__none__">매핑 안 함</SelectItem>
                        {availableColumns.map((col) => (
                          <SelectItem key={col.id} value={col.columnName}>
                            {col.displayName || col.columnName}
                            {!col.isNullable && <span className="text-destructive ml-1">*</span>}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </TableCell>
                  <TableCell className="px-2 py-1.5">{getMatchTypeBadge(suggestion.matchType)}</TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
