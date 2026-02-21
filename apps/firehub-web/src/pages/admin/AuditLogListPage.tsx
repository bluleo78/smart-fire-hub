import { useState } from 'react';
import { useAuditLogs } from '../../hooks/queries/useAuditLogs';
import { Badge } from '../../components/ui/badge';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { SearchInput } from '@/components/ui/search-input';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { SimplePagination } from '@/components/ui/simple-pagination';
import { useDebounceValue } from '@/hooks/useDebounceValue';

const ACTION_TYPES = [
  { value: 'CREATE', label: '생성' },
  { value: 'UPDATE', label: '수정' },
  { value: 'DELETE', label: '삭제' },
  { value: 'LOGIN', label: '로그인' },
  { value: 'LOGOUT', label: '로그아웃' },
  { value: 'IMPORT', label: '임포트' },
  { value: 'EXECUTE', label: '실행' },
];

const RESOURCES = [
  { value: 'user', label: '사용자' },
  { value: 'role', label: '역할' },
  { value: 'dataset', label: '데이터셋' },
  { value: 'pipeline', label: '파이프라인' },
  { value: 'data_import', label: '데이터 임포트' },
];

const RESULTS = [
  { value: 'SUCCESS', label: '성공' },
  { value: 'FAILURE', label: '실패' },
];

function formatDateTime(dateStr: string) {
  const date = new Date(dateStr);
  return date.toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export default function AuditLogListPage() {
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebounceValue(search, 300);
  const [actionType, setActionType] = useState<string>('');
  const [resource, setResource] = useState<string>('');
  const [result, setResult] = useState<string>('');
  const [page, setPage] = useState(0);
  const pageSize = 20;

  const handleSearchChange = (value: string) => {
    setSearch(value);
    setPage(0);
  };

  const { data: logs, isLoading, isError } = useAuditLogs({
    search: debouncedSearch || undefined,
    actionType: actionType || undefined,
    resource: resource || undefined,
    result: result || undefined,
    page,
    size: pageSize,
  });

  const handleFilterChange = (setter: (v: string) => void) => (value: string) => {
    setter(value === 'all' ? '' : value);
    setPage(0);
  };

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">감사 로그</h1>

      <div className="flex flex-wrap items-center gap-4">
        <SearchInput
          placeholder="사용자명 또는 설명으로 검색..."
          value={search}
          onChange={handleSearchChange}
        />

        <Select value={actionType || 'all'} onValueChange={handleFilterChange(setActionType)}>
          <SelectTrigger className="w-[140px]">
            <SelectValue placeholder="액션 유형" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">전체 액션</SelectItem>
            {ACTION_TYPES.map((t) => (
              <SelectItem key={t.value} value={t.value}>{t.label}</SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={resource || 'all'} onValueChange={handleFilterChange(setResource)}>
          <SelectTrigger className="w-[140px]">
            <SelectValue placeholder="리소스" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">전체 리소스</SelectItem>
            {RESOURCES.map((r) => (
              <SelectItem key={r.value} value={r.value}>{r.label}</SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={result || 'all'} onValueChange={handleFilterChange(setResult)}>
          <SelectTrigger className="w-[120px]">
            <SelectValue placeholder="결과" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">전체 결과</SelectItem>
            {RESULTS.map((r) => (
              <SelectItem key={r.value} value={r.value}>{r.label}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>시간</TableHead>
              <TableHead>사용자</TableHead>
              <TableHead>액션</TableHead>
              <TableHead>리소스</TableHead>
              <TableHead>설명</TableHead>
              <TableHead>결과</TableHead>
              <TableHead>IP</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={7} rows={5} />
            ) : isError ? (
              <TableRow>
                <TableCell colSpan={7} className="text-center text-destructive">
                  데이터를 불러오는데 실패했습니다.
                </TableCell>
              </TableRow>
            ) : logs && logs.content.length > 0 ? (
              logs.content.map((log) => (
                <TableRow key={log.id}>
                  <TableCell className="whitespace-nowrap text-sm">
                    {formatDateTime(log.actionTime)}
                  </TableCell>
                  <TableCell className="font-medium">{log.username}</TableCell>
                  <TableCell>{log.actionType}</TableCell>
                  <TableCell>{log.resource}</TableCell>
                  <TableCell className="max-w-xs truncate">{log.description ?? '-'}</TableCell>
                  <TableCell>
                    <Badge variant={log.result === 'SUCCESS' ? 'default' : 'destructive'}>
                      {log.result === 'SUCCESS' ? '성공' : '실패'}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-sm text-muted-foreground">
                    {log.ipAddress ?? '-'}
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={7} message="감사 로그가 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>

      {logs && (
        <SimplePagination
          page={page}
          totalPages={logs.totalPages}
          onPageChange={setPage}
          totalElements={logs.totalElements}
          pageSize={pageSize}
        />
      )}
    </div>
  );
}
