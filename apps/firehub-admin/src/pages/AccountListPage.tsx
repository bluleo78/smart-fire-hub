import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { toast } from 'sonner';

import { accountsApi } from '@/api/accounts';
import { AccountStatusBadge } from '@/components/AccountStatusBadge';
import { PermissionDeniedBanner } from '@/components/PermissionDeniedBanner';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { SearchInput } from '@/components/ui/search-input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useAuth } from '@/hooks/useAuth';
import { useDebounceValue } from '@/hooks/useDebounceValue';
import { isForbidden, serverMessage } from '@/lib/http-errors';
import { MAX_SEARCH_RESULTS, MIN_QUERY_LENGTH } from '@/lib/search-limits';
import type { PlatformAccountResponse } from '@/types/platform';

/** 비활성화 실패 시 서버 문구를 그대로 보여 줄 상태: 400(자기 자신)·409(운영자). 재활성화는 이런 거절이 없다. */
const SERVER_REASON_STATUSES = [400, 409];

/**
 * 비활성화·재활성화 뮤테이션 골격 — 성공 토스트+무효화, 실패 시 서버가 아는 사유는 그 문구를, 아니면
 * fallback 을 보여 준다. 두 동작이 문구와 사유 상태만 다르다.
 */
function useAccountMutation(
  fn: (id: number) => Promise<unknown>,
  onDone: () => void,
  okMessage: string,
  failMessage: string,
  reasonStatuses?: number[],
) {
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      toast.success(okMessage);
      onDone();
    },
    onError: (error) => toast.error(serverMessage(error, reasonStatuses) ?? failMessage),
  });
}

/**
 * 운영자 콘솔 "계정" 화면(#784) — 전역 계정(`user.is_active`) 검색·비활성화·재활성화.
 *
 * WD-2 이후 워크스페이스 화면의 활성 스위치는 "그 워크스페이스 멤버십 정지" 라 전역 잠금 수단이 여기뿐이다.
 * 목록 API 가 없고 검색만 있다(전 사용자 열거 표면을 만들지 않는다) — 결과는 최대 MAX_SEARCH_RESULTS건.
 * 비활성화는 확인 다이얼로그(D-2: 범위·즉시/지연·되돌릴 수 있음을 말한다), 재활성화는 복구 방향이라 즉시.
 */
export default function AccountListPage() {
  const { hasPermission } = useAuth();
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = useState('');
  const debounced = useDebounceValue(keyword, 300).trim();
  const enabled = debounced.length >= MIN_QUERY_LENGTH;
  const canChange = hasPermission('platform:tenant:suspend');
  const [target, setTarget] = useState<PlatformAccountResponse | null>(null);
  const columns = canChange ? 5 : 4;

  const query = useQuery({
    queryKey: ['platform-accounts', debounced],
    queryFn: () => accountsApi.search(debounced).then((r) => r.data),
    enabled,
  });

  /**
   * 성공 후 무효화. Owner 검색(`platform-user-search`)도 넣는 이유: 그 검색은 비활성 계정을 빼므로, 캐시가 남아
   * 있으면 방금 비활성화한 계정을 테넌트 Owner 로 고를 수 있다(아무도 못 들어가는 테넌트).
   */
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['platform-accounts'] });
    void queryClient.invalidateQueries({ queryKey: ['platform-user-search'] });
  };

  const deactivateMutation = useAccountMutation(
    (id) => accountsApi.deactivate(id),
    invalidate,
    '계정을 비활성화했습니다.',
    '계정 비활성화에 실패했습니다.',
    SERVER_REASON_STATUSES,
  );
  const activateMutation = useAccountMutation(
    (id) => accountsApi.activate(id),
    invalidate,
    '계정을 재활성화했습니다.',
    '계정 재활성화에 실패했습니다.',
  );

  if (query.isError && isForbidden(query.error)) {
    return (
      <div className="space-y-6">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">계정</h1>
        <PermissionDeniedBanner />
      </div>
    );
  }

  const rows = query.data ?? [];

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">계정</h1>
        <p className="text-sm text-muted-foreground">
          전역 계정을 비활성화하면 모든 워크스페이스에 로그인할 수 없습니다. 한 워크스페이스에서만 막으려면 그
          워크스페이스 관리자가 멤버십을 정지합니다.
        </p>
      </div>

      <div className="space-y-2">
        <SearchInput
          placeholder="이메일·이름·아이디로 검색..."
          aria-label="계정 검색"
          value={keyword}
          onChange={setKeyword}
        />
        <p className="text-sm text-muted-foreground">
          {enabled
            ? `검색 결과는 최대 ${MAX_SEARCH_RESULTS}건까지 표시됩니다. 찾는 계정이 없으면 검색어를 더 좁혀보세요.`
            : '이메일·이름·아이디를 2자 이상 입력하세요.'}
        </p>
      </div>

      {enabled && (
        <div className="rounded-md border">
          <Table aria-label="계정 목록">
            <TableHeader>
              <TableRow>
                <TableHead>이름</TableHead>
                <TableHead>아이디</TableHead>
                <TableHead>이메일</TableHead>
                <TableHead>상태</TableHead>
                {canChange && <TableHead className="text-right">작업</TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {query.isLoading ? (
                <TableSkeletonRows columns={columns} rows={3} />
              ) : query.isError ? (
                <TableRow>
                  <TableCell colSpan={columns} className="text-center text-destructive">
                    데이터를 불러오는데 실패했습니다.
                  </TableCell>
                </TableRow>
              ) : rows.length > 0 ? (
                rows.map((a) => (
                  <TableRow key={a.id}>
                    <TableCell className="font-medium" title={`userId: ${a.id}`}>
                      <span className="inline-flex items-center gap-2">
                        {a.name}
                        {a.operator && <Badge variant="outline">운영자</Badge>}
                      </span>
                    </TableCell>
                    <TableCell>{a.username}</TableCell>
                    <TableCell>{a.email ?? '-'}</TableCell>
                    <TableCell>
                      <AccountStatusBadge active={a.active} />
                    </TableCell>
                    {canChange && (
                      <TableCell className="text-right">
                        {a.active ? (
                          <Button
                            variant="outline"
                            size="sm"
                            aria-label={`${a.name} 계정 비활성화`}
                            // 운영자 계정은 서버가 409 로 막는다 — 누르기 전에 이유를 보여 준다.
                            disabled={a.operator || deactivateMutation.isPending}
                            title={a.operator ? '운영자 계정은 비활성화할 수 없습니다' : undefined}
                            onClick={() => setTarget(a)}
                          >
                            비활성화
                          </Button>
                        ) : (
                          <Button
                            variant="outline"
                            size="sm"
                            aria-label={`${a.name} 계정 재활성화`}
                            disabled={activateMutation.isPending}
                            onClick={() => activateMutation.mutate(a.id)}
                          >
                            재활성화
                          </Button>
                        )}
                      </TableCell>
                    )}
                  </TableRow>
                ))
              ) : (
                <TableEmptyRow colSpan={columns} message="검색 결과가 없습니다." />
              )}
            </TableBody>
          </Table>
        </div>
      )}

      {/*
        DeleteConfirmDialog 를 쓰지 않는다 — "되돌릴 수 없습니다" 고정 문구가 거짓이다(D-2).
        본문에 반드시: 이름+아이디, 범위(모든 워크스페이스), 즉시 막히는 것(권한을 검사하는 요청만 — 예약 실행 전체가 멈추는 것은 아니다), 남는 것(최대 30분), 되돌릴 수 있음.
        "즉시 거부"는 PermissionRepository 의 user 활성 조인에 기대는 문장이다 — 그 조인을 빼면 이 문구도 고친다.
      */}
      <AlertDialog open={target !== null} onOpenChange={(open) => !open && setTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>계정 비활성화</AlertDialogTitle>
            <AlertDialogDescription>
              {target &&
                `"${target.name}"(${target.username}) 계정을 비활성화합니다. 모든 워크스페이스에서 로그인할 수 없고, 권한이 필요한 작업은 즉시 거부됩니다. 이미 열린 화면은 최대 30분간 일부 보일 수 있습니다. 재활성화하면 다시 로그인해 쓸 수 있습니다.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                if (target) deactivateMutation.mutate(target.id);
                setTarget(null);
              }}
            >
              비활성화
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
