import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useId, useState } from 'react';

import { auditLogsApi } from '@/api/audit-logs';
import { PermissionDeniedBanner } from '@/components/PermissionDeniedBanner';
import { TableErrorRow } from '@/components/TableErrorRow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { SimplePagination } from '@/components/ui/simple-pagination';
import { StatusBadge } from '@/components/ui/status-badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useDebounceValue } from '@/hooks/useDebounceValue';
import { formatDateTimeSecond, localMidnightIso } from '@/lib/formatters';
import { isForbidden } from '@/lib/http-errors';
import { MAX_AUDIT_SEARCH_LENGTH } from '@/lib/search-limits';
import type { PlatformAuditLogResponse } from '@/types/platform';

const PAGE_SIZE = 20;
const COLUMNS = 6;
const ALL = 'ALL';

/**
 * 플랫폼 감사 로그에 실제로 남는 액션의 한국어 라벨. 새 플랫폼 감사 액션이 생기면 여기 더한다 —
 * 매핑이 없으면 원문을 그대로 보여 준다(모르는 값을 숨기지 않는다).
 */
const ACTION_LABELS: Record<string, string> = {
  ACCOUNT_CREATE: '계정 생성',
  ACCOUNT_DEACTIVATE: '계정 비활성화',
  ACCOUNT_REACTIVATE: '계정 재활성화',
  TENANT_CREATE: '테넌트 생성',
  TENANT_SUSPEND: '테넌트 정지',
  TENANT_ACTIVATE: '테넌트 활성화',
  LOGIN: '로그인',
  LOGOUT: '로그아웃',
  // WD-290: 워크스페이스가 하나로 정해지지 않는 계정의 잠금·비밀번호 변경도 여기 남는다(없는 아이디는 로그인 실패 행만).
  ACCOUNT_LOCKED: '계정 잠금',
  PASSWORD_CHANGE: '비밀번호 변경',
  // WD-1132: 운영자 콘솔의 소유자 지정(OWNER_TRANSFER)과 그때 새로 준 ADMIN 역할(ROLE_ASSIGN) — 라벨이 없으면 원문 코드가 보인다.
  OWNER_TRANSFER: '소유권 이전',
  ROLE_ASSIGN: '역할 부여',
  // WD-1157: 테넌트 멤버 추가(PlatformTenantMemberService)·저장소 연결 확인/스크립트 재발급(TenantDataStoreSetupService)·
  // 최초 운영자 가입(PlatformAuthService)도 플랫폼 감사 로그에 남는다 — 라벨이 없으면 원문 코드가 보인다.
  MEMBER_ADD: '멤버 추가',
  TENANT_DATA_STORE_VERIFY: '저장소 연결 확인',
  TENANT_DATA_STORE_SCRIPT_REISSUE: '저장소 스크립트 재발급',
  BOOTSTRAP_SIGNUP: '최초 운영자 가입',
};

/**
 * 대상: 계정 조치는 metadata.targetUsername, 테넌트 생명주기는 "이름 (slug)"(WD-12 — 숫자 id 는 운영자에게 의미가 없다),
 * 둘 다 없으면 resourceId, 그것도 없으면 '-'.
 * WD-1158: 소유권 이전(OWNER_TRANSFER)은 "옛1, 옛2 → 새 · 테넌트", 옛 소유자가 없던 테넌트의 복구 지정은 "→ 새 · 테넌트",
 * 이름 키가 없는 옛 행은 기존 테넌트 표기 그대로.
 * WD-1180: 최초 운영자 가입(BOOTSTRAP_SIGNUP)은 계정 숫자 id 대신 metadata.username, 멤버 추가(MEMBER_ADD)·그 짝 역할 부여(ROLE_ASSIGN)는
 * "멤버 · 테넌트"(누가 추가됐는지가 빠져 있었다). 테넌트 부분은 서버가 싣는 tenantSlug·tenantName 으로 모두 "이름 (slug)" 가 되고,
 * tenantName 이 없는 옛 행은 slug 만 보인다.
 * 해당 키가 없는 옛 행은 기존 표기(resourceId 등) 그대로.
 */
/**
 * 테넌트 표기 한 곳(WD-1180) — 이름이 있으면 "이름 (slug)", 이름 키가 없는 옛 행은 slug 만.
 * 저장소 확인·재발급, 멤버 추가·역할 부여, 소유권 이전이 모두 이 표기를 쓴다.
 */
function tenantLabelOf(m: { tenantSlug: string; tenantName?: unknown }): string {
  return typeof m.tenantName === 'string' ? `${m.tenantName} (${m.tenantSlug})` : m.tenantSlug;
}

function targetOf(log: PlatformAuditLogResponse): string {
  const m = log.metadata;
  if (typeof m?.targetUsername === 'string') return m.targetUsername;
  // WD-1180: 부트스트랩 가입 행은 resourceId 가 계정 숫자 id 라 의미가 없다 — 가입한 아이디를 보인다.
  if (log.actionType === 'BOOTSTRAP_SIGNUP' && typeof m?.username === 'string') return m.username;
  if (typeof m?.tenantSlug === 'string') {
    const tenant = tenantLabelOf({ tenantSlug: m.tenantSlug, tenantName: m.tenantName });
    // WD-1158: 소유권 이전은 옛 소유자 → 새 소유자 이름을 앞에 붙인다(서버가 이름을 못 찾은 사용자는 이미 뺀다).
    // 이름 키가 없는 옛 행은 기존 테넌트 표기 그대로.
    // 옛 소유자가 없던 테넌트(복구 지정)는 fromUsernames 가 비거나 없다 — 그래도 새 소유자 이름은 보여야 한다.
    if (log.actionType === 'OWNER_TRANSFER' && typeof m.toUsername === 'string') {
      const from = Array.isArray(m.fromUsernames) ? m.fromUsernames.filter((n): n is string => typeof n === 'string') : [];
      return from.length > 0 ? `${from.join(', ')} → ${m.toUsername} · ${tenant}` : `→ ${m.toUsername} · ${tenant}`;
    }
    // WD-1180: 멤버 추가·역할 부여는 추가된 멤버를 앞에 붙인다(소유권 이전과 같은 " · " 구분). username 이 없는 행(소유권 이전이 남기는
    // ROLE_ASSIGN 등)은 기존 테넌트 표기 그대로.
    if ((log.actionType === 'MEMBER_ADD' || log.actionType === 'ROLE_ASSIGN') && typeof m.username === 'string') {
      return `${m.username} · ${tenant}`;
    }
    return tenant;
  }
  return log.resourceId ?? '-';
}

/**
 * 운영자 콘솔 "감사 로그" 화면(WD-4) — 테넌트에 속하지 않는 감사 행만 본다.
 *
 * 워크스페이스 안의 활동은 각 워크스페이스의 감사 로그에서 본다(운영자가 테넌트 행을 보면 권한 상승이다).
 * 기간은 운영자 브라우저의 하루다 — 시작일 자정과 종료일 다음 날 자정을 절대 시각으로 보내고, 표의 시각도 같은
 * 브라우저 로컬로 그리므로 화면의 날짜와 조회 범위가 같다(WD-11).
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
  // WD-15: 필터가 하나라도 걸려 있는지 — 빈 결과를 "기록 없음" 과 "조건에 맞는 기록 없음" 으로 구분한다.
  const hasFilter = Boolean(actor || target || actionType !== ALL || from || to);
  /** 모든 필터를 비운다 — page 는 filterKey 보정이 0 으로 되돌린다. */
  const resetFilters = () => {
    setActor('');
    setTarget('');
    setActionType(ALL);
    setFrom('');
    setTo('');
  };

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
    // WD-11: 저장 TZ 는 서버만 안다 — 로컬 자정 순간을 보내고 서버가 저장 벽시계로 바꾼다. 종료일은 다음 날 자정 미만.
    from: from ? localMidnightIso(from) : undefined,
    to: to ? localMidnightIso(to, 1) : undefined,
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
          워크스페이스에 속하지 않는 기록입니다 — 테넌트 생성·정지·활성화, 전역 계정 비활성화·재활성화, 워크스페이스가 정해지지 않은 로그인이 남습니다.
          워크스페이스 안의 활동은 각 워크스페이스의 감사 로그에서 확인합니다.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1">
          <Label htmlFor={actorId}>행위자</Label>
          <Input id={actorId} className="w-48" placeholder="아이디 일부" maxLength={MAX_AUDIT_SEARCH_LENGTH} value={actor} onChange={(e) => setActor(e.target.value)} />
        </div>
        <div className="space-y-1">
          <Label htmlFor={targetId}>대상</Label>
          <Input id={targetId} className="w-64" placeholder="아이디·테넌트 이름·식별자 일부" maxLength={MAX_AUDIT_SEARCH_LENGTH} value={target} onChange={(e) => setTarget(e.target.value)} />
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
              <TableErrorRow colSpan={COLUMNS} />
            ) : data && data.content.length > 0 ? (
              data.content.map((log) => (
                <TableRow key={log.id}>
                  <TableCell className="whitespace-nowrap tabular-nums">{formatDateTimeSecond(log.actionTime)}</TableCell>
                  {/* 행위자 칸 — 공백 없는 긴 이메일이 열을 밀지 않도록 안쪽 div 로 폭을 묶고 어디서든 줄바꿈한다(WD-1158) */}
                  <TableCell className="whitespace-normal">
                    <div className="max-w-[240px] [overflow-wrap:anywhere]">{log.username}</div>
                  </TableCell>
                  <TableCell>{ACTION_LABELS[log.actionType] ?? log.actionType}</TableCell>
                  {/* 대상 칸 — 소유권 이전 행은 「옛 소유자들 → 새 소유자 · 테넌트」로 길어질 수 있다. 셀의 max-width 는 표 열 폭을 못 묶고 공백 없는 긴 이메일은 꺾이지 않으므로, 안쪽 div 로 폭을 묶고 anywhere 로 어디서든 줄바꿈해 결과 칸이 밀리지 않게 한다(WD-1158) */}
                  <TableCell className="whitespace-normal">
                    <div className="max-w-[320px] [overflow-wrap:anywhere]">{targetOf(log)}</div>
                  </TableCell>
                  {/* 설명 칸 — td 의 max-width 는 열 폭을 못 묶으므로 안쪽 div 에서 말줄임해 긴 설명이 열을 넓히지 않게 한다(WD-1158) */}
                  <TableCell>
                    <div className="max-w-[280px] truncate" title={log.description ?? undefined}>
                      {log.description ?? '-'}
                    </div>
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
              // WD-15: 필터가 걸린 빈 결과는 "기록 없음" 과 구분하고 한 번에 되돌릴 행동을 준다.
              <TableEmptyRow
                colSpan={COLUMNS}
                message={hasFilter ? '조건에 맞는 감사 로그가 없습니다.' : '감사 로그가 없습니다.'}
                emptyAction={
                  hasFilter ? (
                    <Button variant="outline" size="sm" onClick={resetFilters}>
                      필터 초기화
                    </Button>
                  ) : undefined
                }
              />
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
