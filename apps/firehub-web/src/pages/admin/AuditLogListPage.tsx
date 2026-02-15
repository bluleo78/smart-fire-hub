import { useState, useEffect } from 'react';
import { useAuditLogs } from '../../hooks/queries/useAuditLogs';
import { Input } from '../../components/ui/input';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Skeleton } from '../../components/ui/skeleton';
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
import { Search, ChevronLeft, ChevronRight } from 'lucide-react';

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
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [actionType, setActionType] = useState<string>('');
  const [resource, setResource] = useState<string>('');
  const [result, setResult] = useState<string>('');
  const [page, setPage] = useState(0);
  const pageSize = 20;

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(search);
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [search]);

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
        <div className="relative w-full max-w-sm">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="사용자명 또는 설명으로 검색..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            maxLength={200}
            className="pl-9"
          />
        </div>

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
              Array.from({ length: 5 }).map((_, i) => (
                <TableRow key={i}>
                  <TableCell><Skeleton className="h-4 w-32" /></TableCell>
                  <TableCell><Skeleton className="h-4 w-20" /></TableCell>
                  <TableCell><Skeleton className="h-4 w-16" /></TableCell>
                  <TableCell><Skeleton className="h-4 w-20" /></TableCell>
                  <TableCell><Skeleton className="h-4 w-40" /></TableCell>
                  <TableCell><Skeleton className="h-5 w-14" /></TableCell>
                  <TableCell><Skeleton className="h-4 w-24" /></TableCell>
                </TableRow>
              ))
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
              <TableRow>
                <TableCell colSpan={7} className="text-center text-muted-foreground">
                  감사 로그가 없습니다.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>

      {logs && logs.totalPages > 1 && (
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">
            총 {logs.totalElements}건 중 {page * pageSize + 1}-{Math.min((page + 1) * pageSize, logs.totalElements)}
          </p>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={page === 0}
            >
              <ChevronLeft className="h-4 w-4" />
              이전
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage(p => p + 1)}
              disabled={page >= logs.totalPages - 1}
            >
              다음
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
