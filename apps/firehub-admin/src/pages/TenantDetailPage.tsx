import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, UserPlus } from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { PLATFORM_ACCOUNTS_KEY } from '@/api/accounts';
import { TENANT_MEMBERS_KEY, tenantsApi } from '@/api/tenants';
import { AccountDisabledBadge } from '@/components/AccountDisabledBadge';
import { MembershipRoleBadge } from '@/components/MembershipRoleBadge';
import { MembershipStatusBadge } from '@/components/MembershipStatusBadge';
import { PermissionDeniedBanner } from '@/components/PermissionDeniedBanner';
import { AddTenantMemberDialog } from '@/components/tenant/AddTenantMemberDialog';
import { TenantDataStoreCard } from '@/components/tenant/TenantDataStoreCard';
import { TenantVectorStoreCard } from '@/components/tenant/TenantVectorStoreCard';
import { TenantStatusBadge } from '@/components/TenantStatusBadge';
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
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { InlineBanner } from '@/components/ui/inline-banner';
import { Skeleton } from '@/components/ui/skeleton';
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
import { useAuth } from '@/hooks/useAuth';
import { formatDateTimeMinute } from '@/lib/formatters';
import { isForbidden, isNotFound } from '@/lib/http-errors';
import { eulReul } from '@/lib/utils';

/**
 * 테넌트 상세.
 *
 * 기본 정보는 `TenantSummaryResponse` 그대로다 — 목록 행과 **같은 DTO** 라서 상세에만 있는
 * 필드가 존재하지 않는다. 수정 엔드포인트도 없으므로 편집 UI 를 두지 않는다.
 */
export default function TenantDetailPage() {
  const { id } = useParams<{ id: string }>();
  const tenantId = Number(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { hasPermission } = useAuth();
  const [isSuspendDialogOpen, setIsSuspendDialogOpen] = useState(false);
  const [isAddMemberOpen, setIsAddMemberOpen] = useState(false);

  // 4xx(권한 없음 등)는 재시도해도 결과가 같다 — 이 사실은 화면이 아니라 상태코드의
  // 성질이므로 전역 기본값(main.tsx 의 `retry`)이 판정한다. 화면별로 `retry: false` 를
  // 반복하지 않는다.
  const tenantQuery = useQuery({
    queryKey: ['platform-tenant', tenantId],
    queryFn: () => tenantsApi.get(tenantId).then((r) => r.data),
  });

  const membersQuery = useQuery({
    queryKey: [TENANT_MEMBERS_KEY, tenantId],
    queryFn: () => tenantsApi.members(tenantId).then((r) => r.data),
  });

  /**
   * 정지/활성화 성공 후 무효화 대상 — 테넌트 상태(상세·목록)만 바뀐다. 멤버십 행은 그대로라 멤버 목록은 다시 읽지 않는다.
   */
  const invalidateTenant = () => {
    void queryClient.invalidateQueries({ queryKey: ['platform-tenant', tenantId] });
    void queryClient.invalidateQueries({ queryKey: ['platform-tenants'] });
  };

  /**
   * 멤버 추가(WD-750) 성공 후 무효화 대상 — 멤버 표와 인원수(`memberCount` — `platform-tenant`·`platform-tenants` 응답의 필드)를
   * 함께 바꾸므로 테넌트 쿼리에 더해 멤버 목록도 무효화한다.
   *
   * 계정 목록(`platform-accounts` 접두)도 무효화한다(WD-773) — 새 계정 탭이 계정을 만들거나 소속 수(`membershipCount`)가 바뀌었고,
   * 멤버 추가 대화상자의 기존 계정 사전 확인도 이 접두 아래라 다음 열림에 「계정 없음」 캐시로 안내를 놓치지 않게 한다.
   * `refetchType: 'none'` — 낡음 표시만 한다. 이 시점에는 대화상자의 사전 확인 쿼리가 아직 마운트돼 있어 바로 다시 읽으면 곧 사라질
   * 화면을 위한 낭비 요청이 된다. 다음에 마운트될 때(다시 열기·계정 화면 진입) 낡은 쿼리라 다시 읽는다.
   */
  const invalidateAfterMemberAdd = () => {
    invalidateTenant();
    void queryClient.invalidateQueries({ queryKey: [TENANT_MEMBERS_KEY, tenantId] });
    void queryClient.invalidateQueries({ queryKey: [PLATFORM_ACCOUNTS_KEY], refetchType: 'none' });
  };

  const suspendMutation = useMutation({
    mutationFn: () => tenantsApi.suspend(tenantId),
    onSuccess: () => {
      toast.success('테넌트를 정지했습니다.');
      invalidateTenant();
    },
    onError: () => toast.error('테넌트 정지에 실패했습니다.'),
  });

  const activateMutation = useMutation({
    mutationFn: () => tenantsApi.activate(tenantId),
    onSuccess: () => {
      toast.success('테넌트를 활성화했습니다.');
      invalidateTenant();
    },
    onError: () => toast.error('테넌트 활성화에 실패했습니다.'),
  });

  if (tenantQuery.isLoading) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-9 w-64" />
        <Skeleton className="h-40 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  // 403 은 재시도해도 소용없는데 아래 포괄 분기(:126)로 흘리면 "다시 시도" 버튼을 주는
  // 실패로 잘못 보인다(M-2). 같은 파일의 membersQuery(:212)는 이미 이 분기를 갖고 있었다 —
  // 여기 없던 것은 태스크 간이 아니라 **파일 안** 불일치였다.
  if (tenantQuery.isError && isForbidden(tenantQuery.error)) {
    return (
      <div className="space-y-4">
        <PermissionDeniedBanner />
        <Link to="/tenants" className="text-sm underline">
          목록으로 돌아가기
        </Link>
      </div>
    );
  }

  // 404("없음")와 그 외 실패(500·네트워크 등, "불러오지 못함")를 구별한다 — 뭉뚱그리면
  // 일시적 장애가 영구적인 "존재하지 않음"으로 보여 운영자를 오도한다.
  if (tenantQuery.isError && isNotFound(tenantQuery.error)) {
    return (
      <div className="space-y-4">
        <p className="text-sm text-muted-foreground">테넌트를 찾을 수 없습니다.</p>
        <Link to="/tenants" className="text-sm underline">
          목록으로 돌아가기
        </Link>
      </div>
    );
  }

  if (tenantQuery.isError || !tenantQuery.data) {
    return (
      <div className="space-y-4">
        {/* TenantListPage 의 조회 실패 문구와 같은 어휘를 쓴다. */}
        <p className="text-sm text-muted-foreground">데이터를 불러오는 데 실패했습니다.</p>
        <div className="flex items-center gap-3">
          <Button variant="outline" size="sm" onClick={() => void tenantQuery.refetch()}>
            다시 시도
          </Button>
          <Link to="/tenants" className="text-sm underline">
            목록으로 돌아가기
          </Link>
        </div>
      </div>
    );
  }

  const tenant = tenantQuery.data;
  const isActive = tenant.status === 'ACTIVE';
  // 멤버 추가 엔드포인트는 테넌트 생성과 같은 권한(platform:tenant:create)을 요구하고, 활성 테넌트에만 넣을 수 있다.
  const canAddMember = isActive && hasPermission('platform:tenant:create');

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <Button
          variant="ghost"
          size="icon"
          onClick={() => navigate('/tenants')}
          aria-label="목록으로 돌아가기"
          title="목록으로 돌아가기"
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">{tenant.name}</h1>
        <TenantStatusBadge status={tenant.status} />

        {hasPermission('platform:tenant:suspend') && (
          <div className="ml-auto">
            {isActive ? (
              <Button
                variant="destructive"
                onClick={() => setIsSuspendDialogOpen(true)}
                disabled={suspendMutation.isPending}
              >
                테넌트 정지
              </Button>
            ) : (
              // 복구 방향이라 확인을 받지 않는다(D-2).
              <Button
                onClick={() => activateMutation.mutate()}
                disabled={activateMutation.isPending}
              >
                테넌트 활성화
              </Button>
            )}
          </div>
        )}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>기본 정보</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
            <span className="text-muted-foreground">이름</span>
            <span>{tenant.name}</span>
            <span className="text-muted-foreground">식별자</span>
            <span className="font-mono text-[13px]">{tenant.slug}</span>
            <span className="text-muted-foreground">상태</span>
            <span>
              <TenantStatusBadge status={tenant.status} />
            </span>
            <span className="text-muted-foreground">멤버 수</span>
            <span className="tabular-nums">{tenant.memberCount}</span>
            <span className="text-muted-foreground">생성일</span>
            <span>{formatDateTimeMinute(tenant.createdAt)}</span>
          </div>
        </CardContent>
      </Card>

      {/* WD-73: 데이터셋 저장소(PostgreSQL 이면 이름만, 외부 저장소면 접속 정보·상태·확인/재발급). */}
      <TenantDataStoreCard
        tenantId={tenantId}
        canManage={hasPermission('platform:tenant:create')}
      />

      {/* WD-742: 벡터 저장소(공유/전용 Qdrant). 설치가 Qdrant 가 아니면 그리지 않는다. */}
      <TenantVectorStoreCard tenantId={tenantId} />

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle>멤버 ({tenant.memberCount})</CardTitle>
          {/* WD-750: 정지된 테넌트에는 서버가 TENANT_SUSPENDED 로 거절한다 — 누를 수 없는 버튼을 보이지 않는다. */}
          {canAddMember && (
            <Button size="sm" variant="outline" onClick={() => setIsAddMemberOpen(true)}>
              <UserPlus className="h-4 w-4" aria-hidden="true" />
              멤버 추가
            </Button>
          )}
        </CardHeader>
        <CardContent className="space-y-3">
          {membersQuery.isError && isForbidden(membersQuery.error) ? (
            // 기본 정보는 그대로 두고 멤버 카드만 바꾼다 — 나머지 화면을 날릴 이유가 없다.
            <InlineBanner variant="info">멤버를 조회할 권한이 없습니다.</InlineBanner>
          ) : membersQuery.isError ? (
            // 403 이 아닌 실패(500·네트워크 등)를 "멤버 0명"으로 보여주면 안 된다 — 못 불러온
            // 것과 실제로 없는 것은 다르다. TenantListPage 조회 실패 문구와 같은 어휘를 쓴다.
            <InlineBanner
              variant="caution"
              actions={
                <Button variant="outline" size="sm" onClick={() => void membersQuery.refetch()}>
                  다시 시도
                </Button>
              }
            >
              데이터를 불러오는 데 실패했습니다.
            </InlineBanner>
          ) : (
            <>
              <p className="text-sm text-muted-foreground">
                역할은 워크스페이스 내 표시용이며, 실제 권한은 워크스페이스 관리자가 설정합니다.
              </p>
              <div className="rounded-md border">
                <Table aria-label="테넌트 멤버 목록">
                  <TableHeader>
                    <TableRow>
                      {/* WD-7: 이 열은 username 만 보인다(이름 필드가 없다) — 헤더도 "아이디". 계정 식별자는 일반 텍스트(02-typography §4.1). */}
                      <TableHead>아이디</TableHead>
                      <TableHead>이메일</TableHead>
                      <TableHead>역할</TableHead>
                      <TableHead>상태</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {membersQuery.isLoading ? (
                      // 로딩 중을 "0명"으로 보여주지 않는다 — 위 에러 분기와 같은 이유다.
                      <TableSkeletonRows columns={4} rows={3} />
                    ) : membersQuery.data && membersQuery.data.length > 0 ? (
                      membersQuery.data.map((m) => (
                        <TableRow key={m.userId}>
                          <TableCell title={`userId: ${m.userId}`}>{m.username}</TableCell>
                          <TableCell>{m.email ?? '-'}</TableCell>
                          <TableCell>
                            <MembershipRoleBadge role={m.role} />
                          </TableCell>
                          <TableCell>
                            {/* 멤버십 상태 옆에 전역 계정 비활성을 따로 단다(WD-313) — 비활성 계정이 「활성」 멤버로만 보이면
                                로그인할 수 없는 사람을 정상 멤버로 오해한다(웹 사용자 관리 WD-3 와 같은 판단). */}
                            <div className="flex flex-wrap items-center gap-1">
                              <MembershipStatusBadge status={m.status} />
                              {m.accountActive === false && <AccountDisabledBadge />}
                            </div>
                          </TableCell>
                        </TableRow>
                      ))
                    ) : (
                      <TableEmptyRow colSpan={4} message="멤버가 없습니다." />
                    )}
                  </TableBody>
                </Table>
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <AddTenantMemberDialog
        tenantId={tenantId}
        tenantName={tenant.name}
        open={isAddMemberOpen}
        onOpenChange={setIsAddMemberOpen}
        onAdded={invalidateAfterMemberAdd}
      />

      {/*
        DeleteConfirmDialog 를 쓰지 않는다 — 그 고정 문구의 "되돌릴 수 없습니다"가 거짓이다.
        본문에 반드시 셋을 넣는다: 이름+slug(잘못된 행을 눌렀는지), 영향 인원, 최대 30분 지연.
        세 번째가 없으면 운영자는 눌렀으니 차단됐다고 믿는다(D-2).
      */}
      <AlertDialog open={isSuspendDialogOpen} onOpenChange={setIsSuspendDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>테넌트 정지</AlertDialogTitle>
            <AlertDialogDescription>
              {/* WD-6: 괄호 뒤 조사는 괄호 앞 이름의 받침에 맞춘다(공백 없이). 이름이 동적이라 "를" 고정은 틀린다. */}
              {`"${tenant.name}"(${tenant.slug})${eulReul(tenant.name)} 정지합니다. 이 워크스페이스의 멤버 ${tenant.memberCount}명은 새로 로그인하거나 세션을 갱신하는 시점부터 접근이 차단됩니다. 이미 발급된 세션은 최대 30분간 유효합니다. 정지는 언제든 되돌릴 수 있습니다.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                setIsSuspendDialogOpen(false);
                suspendMutation.mutate();
              }}
            >
              정지
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
