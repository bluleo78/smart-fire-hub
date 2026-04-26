import { useState } from 'react';

import { Badge } from '@/components/ui/badge';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { SearchInput } from '@/components/ui/search-input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { SimplePagination } from '@/components/ui/simple-pagination';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useAuditLogs } from '@/hooks/queries/useAuditLogs';
import { useDebounceValue } from '@/hooks/useDebounceValue';
import type { AuditLogResponse } from '@/types/auditLog';

/** 액션 유형 옵션 목록 */
const ACTION_TYPES = [
  { value: 'CREATE', label: '생성' },
  { value: 'UPDATE', label: '수정' },
  { value: 'DELETE', label: '삭제' },
  { value: 'LOGIN', label: '로그인' },
  { value: 'LOGOUT', label: '로그아웃' },
  { value: 'IMPORT', label: '임포트' },
  { value: 'EXECUTE', label: '실행' },
];

/** 리소스 유형 옵션 목록 */
const RESOURCES = [
  { value: 'user', label: '사용자' },
  { value: 'role', label: '역할' },
  { value: 'dataset', label: '데이터셋' },
  { value: 'pipeline', label: '파이프라인' },
  { value: 'data_import', label: '데이터 임포트' },
];

/** 결과 필터 옵션 목록 */
const RESULTS = [
  { value: 'SUCCESS', label: '성공' },
  { value: 'FAILURE', label: '실패' },
];

/** 날짜-시간 문자열을 한국어 형식으로 포맷 */
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

/**
 * 날짜 문자열(YYYY-MM-DD)을 ISO 8601 datetime 문자열로 변환.
 * endDate의 경우 하루의 끝(23:59:59)으로 설정해 inclusive 범위를 구현한다.
 */
function toIsoDateTime(dateStr: string, endOfDay = false): string {
  return endOfDay ? `${dateStr}T23:59:59` : `${dateStr}T00:00:00`;
}

/**
 * 감사 로그 상세 보기 다이얼로그
 * - 테이블 행 클릭 시 표시: description 전문, IP, actionTime 등 전체 필드를 보여준다.
 */
function AuditLogDetailDialog({
  log,
  open,
  onClose,
}: {
  log: AuditLogResponse | null;
  open: boolean;
  onClose: () => void;
}) {
  if (!log) return null;

  return (
    <Dialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>감사 로그 상세</DialogTitle>
        </DialogHeader>
        <div className="space-y-3 text-sm">
          {/* 기본 정보 행 */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <p className="text-muted-foreground mb-1 text-xs">시간</p>
              <p className="font-medium">{formatDateTime(log.actionTime)}</p>
            </div>
            <div>
              <p className="text-muted-foreground mb-1 text-xs">사용자</p>
              <p className="font-medium">{log.username}</p>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <p className="text-muted-foreground mb-1 text-xs">액션</p>
              <p>{log.actionType}</p>
            </div>
            <div>
              <p className="text-muted-foreground mb-1 text-xs">리소스</p>
              <p>{log.resource}{log.resourceId ? ` (${log.resourceId})` : ''}</p>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <p className="text-muted-foreground mb-1 text-xs">결과</p>
              <Badge variant={log.result === 'SUCCESS' ? 'default' : 'destructive'}>
                {log.result === 'SUCCESS' ? '성공' : '실패'}
              </Badge>
            </div>
            <div>
              <p className="text-muted-foreground mb-1 text-xs">IP 주소</p>
              <p>{log.ipAddress ?? '-'}</p>
            </div>
          </div>

          {/* 설명 전문: truncate 없이 전체 표시 */}
          <div>
            <p className="text-muted-foreground mb-1 text-xs">설명</p>
            <p className="bg-muted rounded-md p-3 whitespace-pre-wrap break-words">
              {log.description ?? '-'}
            </p>
          </div>

          {/* 에러 메시지 (실패인 경우) */}
          {log.errorMessage && (
            <div>
              <p className="text-muted-foreground mb-1 text-xs">에러 메시지</p>
              <p className="bg-muted text-destructive rounded-md p-3 whitespace-pre-wrap break-words">
                {log.errorMessage}
              </p>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** 감사 로그 목록 페이지 — 날짜 범위 필터 + 행 클릭 상세 보기 */
export default function AuditLogListPage() {
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebounceValue(search, 300);
  const [actionType, setActionType] = useState<string>('');
  const [resource, setResource] = useState<string>('');
  const [result, setResult] = useState<string>('');
  /** 날짜 범위 필터: YYYY-MM-DD 형식으로 저장 */
  const [startDate, setStartDate] = useState<string>('');
  const [endDate, setEndDate] = useState<string>('');
  const [page, setPage] = useState(0);
  /** 페이지당 표시 건수: 사용자가 selector 로 변경 가능 (기본 20) */
  const [pageSize, setPageSize] = useState(20);

  /** 상세 보기 다이얼로그 상태 */
  const [selectedLog, setSelectedLog] = useState<AuditLogResponse | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  const handleSearchChange = (value: string) => {
    setSearch(value);
    setPage(0);
  };

  const { data: logs, isLoading, isError } = useAuditLogs({
    search: debouncedSearch || undefined,
    actionType: actionType || undefined,
    resource: resource || undefined,
    result: result || undefined,
    // 날짜 범위를 ISO datetime으로 변환하여 API에 전달
    startDate: startDate ? toIsoDateTime(startDate) : undefined,
    endDate: endDate ? toIsoDateTime(endDate, true) : undefined,
    page,
    size: pageSize,
  });

  const handleFilterChange = (setter: (v: string) => void) => (value: string) => {
    setter(value === 'all' ? '' : value);
    setPage(0);
  };

  /** 날짜 필터 변경 시 페이지 리셋 */
  const handleDateChange = (setter: (v: string) => void) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setter(e.target.value);
    setPage(0);
  };

  /** 행 클릭 → 상세 보기 다이얼로그 열기 */
  const handleRowClick = (log: AuditLogResponse) => {
    setSelectedLog(log);
    setDetailOpen(true);
  };

  return (
    <div className="space-y-6">
      <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">감사 로그</h1>

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

        {/* 날짜 범위 필터: 시작일 ~ 종료일 */}
        <div className="flex items-center gap-2">
          <Input
            type="date"
            aria-label="시작 날짜"
            className="w-[150px]"
            value={startDate}
            max={endDate || undefined}
            onChange={handleDateChange(setStartDate)}
          />
          <span className="text-muted-foreground text-sm">~</span>
          <Input
            type="date"
            aria-label="종료 날짜"
            className="w-[150px]"
            value={endDate}
            min={startDate || undefined}
            onChange={handleDateChange(setEndDate)}
          />
        </div>
      </div>

      <div className="rounded-md border">
        <Table aria-label="감사 로그">
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
                // 행 클릭 시 상세 보기 다이얼로그를 열어 truncate된 description 전문을 표시한다
                <TableRow
                  key={log.id}
                  className="row-hover cursor-pointer"
                  onClick={() => handleRowClick(log)}
                >
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
          onPageSizeChange={(size) => {
            setPageSize(size);
            setPage(0);
          }}
        />
      )}

      {/* 감사 로그 상세 보기 다이얼로그 */}
      <AuditLogDetailDialog
        log={selectedLog}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
      />
    </div>
  );
}
