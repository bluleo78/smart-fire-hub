import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';

import { accountsApi } from '@/api/accounts';
import { AccountStatusBadge } from '@/components/AccountStatusBadge';
import { CreateAccountDialog } from '@/components/CreateAccountDialog';
import { PermissionDeniedBanner } from '@/components/PermissionDeniedBanner';
import { TableErrorRow } from '@/components/TableErrorRow';
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
import { SimplePagination } from '@/components/ui/simple-pagination';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useAuth } from '@/hooks/useAuth';
import { useDebounceValue } from '@/hooks/useDebounceValue';
import { formatDateOnly } from '@/lib/formatters';
import { isForbidden, serverMessage } from '@/lib/http-errors';
import type { PlatformAccountResponse } from '@/types/platform';

/** 한 페이지 행 수 — 감사 로그·테넌트 목록과 같은 20. */
const PAGE_SIZE = 20;

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
 * 운영자 콘솔 "계정" 화면(#784) — 전역 계정(`user.is_active`) 목록·생성(WD-46)·비활성화·재활성화.
 *
 * WD-2 이후 워크스페이스 화면의 활성 스위치는 "그 워크스페이스 멤버십 정지" 라 전역 잠금 수단이 여기뿐이다.
 * WD-47: 검색 전용(2자·20건 상한)에서 전체 목록 + 서버 페이지네이션으로 바꿨다 — 첫 진입에 생성 최신순 목록을 보이고,
 * 검색창은 1자부터 필터로 쓴다. 소속 수 열로 아무 워크스페이스에도 속하지 않은 계정("미소속")을 바로 찾는다.
 * 비활성화는 확인 다이얼로그(D-2: 범위·즉시/지연·되돌릴 수 있음을 말한다), 재활성화는 복구 방향이라 즉시.
 */
export default function AccountListPage() {
  const { hasPermission } = useAuth();
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = useState('');
  const debounced = useDebounceValue(keyword, 300).trim();
  const [page, setPage] = useState(0);
  const canChange = hasPermission('platform:tenant:suspend');
  const [target, setTarget] = useState<PlatformAccountResponse | null>(null);
  // 계정 생성(WD-46)은 테넌트 생성의 앞단이라 같은 권한을 쓴다(서버 PlatformAccountController#create 주석).
  const canCreate = hasPermission('platform:tenant:create');
  const [createOpen, setCreateOpen] = useState(false);
  const columns = canChange ? 6 : 5;

  // 조회에 실제로 쓰이는 검색어(디바운스 반영)가 바뀌면 같은 렌더 안에서 page 를 0 으로 되돌린다(감사 로그 화면과 같은 이유:
  // 입력 즉시 되돌리면 "이전 검색어 + 0쪽" 요청이, effect 로 되돌리면 "새 검색어 + 이전 쪽" 요청이 한 번 더 나간다).
  const [appliedKeyword, setAppliedKeyword] = useState(debounced);
  let effectivePage = page;
  if (appliedKeyword !== debounced) {
    setAppliedKeyword(debounced);
    setPage(0);
    effectivePage = 0;
  }

  const params = { q: debounced || undefined, page: effectivePage, size: PAGE_SIZE };
  const query = useQuery({
    queryKey: ['platform-accounts', params],
    queryFn: () => accountsApi.list(params).then((r) => r.data),
    // 페이지 이동 중 이전 결과를 유지해 표·페이지네이션이 사라졌다 나타나지 않게 한다.
    placeholderData: keepPreviousData,
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

  const data = query.data;
  const rows = data?.content ?? [];

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <div className="flex items-center justify-between gap-4">
          <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">계정</h1>
          {canCreate && (
            <Button onClick={() => setCreateOpen(true)}>
              <Plus className="h-4 w-4" aria-hidden="true" />
              계정 생성
            </Button>
          )}
        </div>
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
      </div>

      <div className="rounded-md border">
        <Table aria-label="계정 목록">
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>아이디</TableHead>
              <TableHead>소속</TableHead>
              <TableHead>상태</TableHead>
              <TableHead>생성일</TableHead>
              {canChange && <TableHead className="text-right">작업</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {query.isLoading ? (
              <TableSkeletonRows columns={columns} rows={5} />
            ) : query.isError ? (
              <TableErrorRow colSpan={columns} />
            ) : rows.length > 0 ? (
              rows.map((a) => (
                <TableRow key={a.id}>
                  <TableCell className="font-medium" title={`userId: ${a.id}`}>
                    <span className="inline-flex items-center gap-2">
                      {a.name}
                      {a.operator && <Badge variant="outline">운영자</Badge>}
                    </span>
                  </TableCell>
                  {/* WD-15: 아이디는 이메일 형식이 강제된다 — 같은 값을 두 열에 반복하지 않고, 이메일이 다를 때만 보조 줄.
                      대소문자만 다른 주소는 같은 메일함이라 반복으로 본다. */}
                  <TableCell>
                    <div>{a.username}</div>
                    {a.email && a.email.toLowerCase() !== a.username.toLowerCase() && (
                      <div className="text-xs text-muted-foreground">이메일 {a.email}</div>
                    )}
                  </TableCell>
                  {/* 소속 0 은 숫자 "0개" 대신 배지로 — 아무 워크스페이스에도 못 들어가는 계정을 훑어보며 찾게 한다. */}
                  <TableCell>
                    {a.membershipCount > 0 ? (
                      <span className="tabular-nums">{a.membershipCount}개</span>
                    ) : (
                      <Badge variant="secondary">미소속</Badge>
                    )}
                  </TableCell>
                  <TableCell>
                    <AccountStatusBadge active={a.active} />
                  </TableCell>
                  <TableCell className="tabular-nums">{formatDateOnly(a.createdAt)}</TableCell>
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
              // 검색 중 빈 결과와 전체가 빈 상태를 나눈다 — 검색 중이면 검색어와 초기화 버튼을 보인다.
              <TableEmptyRow
                colSpan={columns}
                message="등록된 계정이 없습니다."
                searchKeyword={debounced || undefined}
                onResetSearch={() => setKeyword('')}
              />
            )}
          </TableBody>
        </Table>
      </div>

      {data && data.totalPages > 0 && (
        <SimplePagination
          page={effectivePage}
          totalPages={data.totalPages}
          onPageChange={setPage}
          totalElements={data.totalElements}
          pageSize={PAGE_SIZE}
        />
      )}

      <CreateAccountDialog open={createOpen} onOpenChange={setCreateOpen} finishLabel="확인" />

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
