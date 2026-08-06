import { AlertTriangle, ArrowRight } from 'lucide-react';
import { useEffect, useRef } from 'react';

import { Badge } from '../../../components/ui/badge';
import { InlineBanner } from '../../../components/ui/inline-banner';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../../components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../../../components/ui/table';
import type { ColumnMappingDto,ColumnMappingEntry } from '../../../types/dataImport';
import type { DatasetColumnResponse } from '../../../types/dataset';

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
  // 파일 업로드(1단계) → 컬럼 매핑(2단계)은 다이얼로그 내용이 통째로 교체되는데, 포커스는 다이얼로그
  // 컨테이너에 그대로 남아 스크린리더 사용자에게는 아무 일도 없던 것과 구별되지 않는다.
  // 이 표가 마운트되는 시점 = 2단계 진입이므로 새 단계의 제목으로 포커스를 옮겨 맥락을 알린다(#330).
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    headingRef.current?.focus();
  }, []);

  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold" ref={headingRef} tabIndex={-1}>
        컬럼 매핑
      </h3>
      {hasUnmappedRequired && (
        <InlineBanner icon={<AlertTriangle />} title="필수 필드가 매핑되지 않았습니다:">
          <p className="text-xs">
            {unmappedRequired.map((col) => col.displayName || col.columnName).join(', ')}
          </p>
        </InlineBanner>
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
                      {/*
                       * Radix Select은 role/aria-expanded는 붙여주지만 접근 가능한 이름은 만들지 않는다.
                       * 이름이 없으면 스크린리더에 "현재 선택된 값"만 읽혀 어느 파일 컬럼의 매핑인지 알 수 없다 —
                       * 표 셀의 인접성은 프로그램적 연결이 아니므로 파일 컬럼명을 이름에 담는다(#331, WCAG SC 4.1.2).
                       */}
                      <SelectTrigger
                        className="h-8 text-xs w-full"
                        aria-label={`${suggestion.fileColumn} 컬럼을 매핑할 데이터셋 컬럼`}
                      >
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
