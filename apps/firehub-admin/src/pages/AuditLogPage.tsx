import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useId, useState } from 'react';

import { auditLogsApi } from '@/api/audit-logs';
import { PermissionDeniedBanner } from '@/components/PermissionDeniedBanner';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { SimplePagination } from '@/components/ui/simple-pagination';
import { StatusBadge } from '@/components/ui/status-badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useDebounceValue } from '@/hooks/useDebounceValue';
import { formatDateTimeSecond } from '@/lib/formatters';
import { isForbidden } from '@/lib/http-errors';
import type { PlatformAuditLogResponse } from '@/types/platform';

const PAGE_SIZE = 20;
const COLUMNS = 6;
const ALL = 'ALL';

/**
 * 플랫폼 감사 로그에 실제로 남는 액션의 한국어 라벨. 새 플랫폼 감사 액션이 생기면 여기 더한다 —
 * 매핑이 없으면 원문을 그대로 보여 준다(모르는 값을 숨기지 않는다).
 */
const ACTION_LABELS: Record<string, string> = {
  ACCOUNT_DEACTIVATE: '계정 비활성화',
  ACCOUNT_REACTIVATE: '계정 재활성화',
  LOGIN: '로그인',
  LOGOUT: '로그아웃',
};

/** 대상: 운영자 계정 조치가 남긴 metadata.targetUsername, 없으면 resourceId, 둘 다 없으면 '-'. */
function targetOf(log: PlatformAuditLogResponse): string {
  const name = log.metadata?.targetUsername;
  return typeof name === 'string' ? name : (log.resourceId ?? '-');
}

/**
 * 운영자 콘솔 "감사 로그" 화면(WD-4) — 테넌트에 속하지 않는 감사 행만 본다.
 *
 * 워크스페이스 안의 활동은 각 워크스페이스의 감사 로그에서 본다(운영자가 테넌트 행을 보면 권한 상승이다).
 * 기간은 날짜만 보낸다 — 서버가 벽시계 하루로 바꾸므로 표에 보이는 시각과 조회 범위가 같다.
 */
export default function AuditLogPage() {
  const actorId = useId();
  const targetId = useId();
  const fromId = useId();
  const toId = useId();
  const [actor, setActor] = useState('');
  const [target, setTarget] = useState('');
  const [actionType, setActionType] = useState(ALL);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const debouncedActor = useDebounceValue(actor, 300).trim();
  const debouncedTarget = useDebounceValue(target, 300).trim();
  // 날짜 입력은 세그먼트 단위 편집이라 역전 상태가 실제로 생긴다 — 조회를 보류하고 이유를 말한다(서버도 400).
  const inverted = Boolean(from && to && from > to);

  // 조회에 실제로 쓰이는 필터 값(디바운스 반영)이 바뀌면 같은 렌더 안에서 page 를 0 으로 되돌린다.
  // 입력 즉시 setPage(0) 하면 디바운스 전에 "이전 필터 + page 0" 요청이 한 번 더 나가고,
  // useEffect 로 되돌리면 "새 필터 + 이전 page" 요청이 먼저 나간다 — 렌더 중 보정은 둘 다 막는다.
  const filterKey = [debouncedActor, debouncedTarget, actionType, from, to].join('\u0000');
  const [appliedFilterKey, setAppliedFilterKey] = useState(filterKey);
  let effectivePage = page;
  if (appliedFilterKey !== filterKey) {
    setAppliedFilterKey(filterKey);
    setPage(0);
    effectivePage = 0;
  }

  const params = {
    actor: debouncedActor || undefined,
    target: debouncedTarget || undefined,
    actionType: actionType === ALL ? undefined : actionType,
    from: from || undefined,
    to: to || undefined,
    page: effectivePage,
    size: PAGE_SIZE,
  };
  const query = useQuery({
    queryKey: ['platform-audit-logs', params],
    queryFn: () => auditLogsApi.search(params).then((r) => r.data),
    enabled: !inverted,
    // 페이지 이동 중 이전 결과를 유지해 페이지네이션이 사라졌다 나타나지 않게 한다.
    placeholderData: keepPreviousData,
  });

  if (query.isError && isForbidden(query.error)) {
    return (
      <div className="space-y-6">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">감사 로그</h1>
        <PermissionDeniedBanner />
      </div>
    );
  }

  const data = query.data;

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">감사 로그</h1>
        <p className="text-sm text-muted-foreground">
          워크스페이스에 속하지 않는 기록입니다 — 전역 계정 비활성화·재활성화와 워크스페이스가 정해지지 않은 로그인이 남습니다.
          워크스페이스 안의 활동은 각 워크스페이스의 감사 로그에서 확인합니다.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1">
          <Label htmlFor={actorId}>행위자</Label>
          <Input id={actorId} className="w-48" placeholder="아이디 일부" value={actor} onChange={(e) => setActor(e.target.value)} />
        </div>
        <div className="space-y-1">
          <Label htmlFor={targetId}>대상</Label>
          <Input id={targetId} className="w-48" placeholder="대상 아이디 일부" value={target} onChange={(e) => setTarget(e.target.value)} />
        </div>
        <div className="space-y-1">
          <span className="text-sm font-medium">액션</span>
          <Select value={actionType} onValueChange={setActionType}>
            <SelectTrigger className="w-40" aria-label="액션">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>전체</SelectItem>
              {Object.entries(ACTION_LABELS).map(([value, label]) => (
                <SelectItem key={value} value={value}>
                  {label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <Label htmlFor={fromId}>시작일</Label>
          <Input id={fromId} type="date" className="w-40" max={to || undefined} value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div className="space-y-1">
          <Label htmlFor={toId}>종료일</Label>
          <Input id={toId} type="date" className="w-40" min={from || undefined} value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
      </div>

      {/* 기간 역전은 필드 유효성 오류 — 배너가 아니라 인라인 오류 문구(06 패턴 선택 가이드, 웹 #541 과 통일). */}
      {inverted && (
        <p role="alert" className="text-sm text-destructive">
          시작일이 종료일보다 늦습니다. 기간을 다시 고르세요.
        </p>
      )}

      <div className="rounded-md border">
        <Table aria-label="플랫폼 감사 로그">
          <TableHeader>
            <TableRow>
              <TableHead>시간</TableHead>
              <TableHead>행위자</TableHead>
              <TableHead>액션</TableHead>
              <TableHead>대상</TableHead>
              <TableHead>설명</TableHead>
              <TableHead>결과</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {inverted ? (
              // 조회를 보류한 상태 — "결과 없음" 으로 보이면 기간이 틀린 것과 기록이 없는 것을 구분할 수 없다.
              <TableEmptyRow colSpan={COLUMNS} message="기간을 고치면 결과가 표시됩니다." />
            ) : query.isLoading ? (
              <TableSkeletonRows columns={COLUMNS} rows={5} />
            ) : query.isError ? (
              <TableRow>
                <TableCell colSpan={COLUMNS} className="text-center text-destructive">
                  데이터를 불러오는데 실패했습니다.
                </TableCell>
              </TableRow>
            ) : data && data.content.length > 0 ? (
              data.content.map((log) => (
                <TableRow key={log.id}>
                  <TableCell className="whitespace-nowrap tabular-nums">{formatDateTimeSecond(log.actionTime)}</TableCell>
                  <TableCell>{log.username}</TableCell>
                  <TableCell>{ACTION_LABELS[log.actionType] ?? log.actionType}</TableCell>
                  <TableCell>{targetOf(log)}</TableCell>
                  <TableCell className="max-w-[280px] truncate" title={log.description ?? undefined}>
                    {log.description ?? '-'}
                  </TableCell>
                  <TableCell>
                    {log.result === 'SUCCESS' ? (
                      <StatusBadge type="success">성공</StatusBadge>
                    ) : (
                      <StatusBadge type="error">실패</StatusBadge>
                    )}
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={COLUMNS} message="감사 로그가 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>

      {data && !inverted && (
        <SimplePagination
          page={effectivePage}
          totalPages={data.totalPages}
          onPageChange={setPage}
          totalElements={data.totalElements}
          pageSize={PAGE_SIZE}
        />
      )}
    </div>
  );
}
