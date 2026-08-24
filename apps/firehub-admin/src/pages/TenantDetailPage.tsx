import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft } from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { tenantsApi } from '@/api/tenants';
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
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { InlineBanner } from '@/components/ui/inline-banner';
import { Skeleton } from '@/components/ui/skeleton';
import { StatusBadge } from '@/components/ui/status-badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { useAuth } from '@/hooks/useAuth';
import { formatDateTimeMinute } from '@/lib/formatters';
import { isForbidden } from '@/lib/http-errors';

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

  const tenantQuery = useQuery({
    queryKey: ['platform-tenant', tenantId],
    queryFn: () => tenantsApi.get(tenantId).then((r) => r.data),
  });

  const membersQuery = useQuery({
    queryKey: ['platform-tenant-members', tenantId],
    queryFn: () => tenantsApi.members(tenantId).then((r) => r.data),
    // 403(멤버 조회 권한 없음)은 재시도해도 결과가 같다.
    retry: false,
  });

  /**
   * 정지/활성화 성공 후 무효화 대상.
   *
   * <b>`platform-tenant-members` 를 넣지 않는 것은 의도다.</b> 정지/활성화는 `tenant.status` 만
   * 바꾸고 멤버십 행은 건드리지 않는다 — 멤버 목록·역할·인원수 어느 것도 달라지지 않으므로
   * 무효화하면 같은 응답을 다시 받아 오는 낭비일 뿐이다. (`memberCount` 는 멤버 쿼리가 아니라
   * `platform-tenant` 응답의 필드라 이미 갱신된다.) 리뷰어가 "누락"으로 보고 고치지 않도록
   * 여기 근거를 남긴다. 멤버를 바꾸는 엔드포인트가 생기면 그때 이 목록에 더한다.
   */
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['platform-tenant', tenantId] });
    void queryClient.invalidateQueries({ queryKey: ['platform-tenants'] });
  };

  const suspendMutation = useMutation({
    mutationFn: () => tenantsApi.suspend(tenantId),
    onSuccess: () => {
      toast.success('테넌트를 정지했습니다.');
      invalidate();
    },
    onError: () => toast.error('테넌트 정지에 실패했습니다.'),
  });

  const activateMutation = useMutation({
    mutationFn: () => tenantsApi.activate(tenantId),
    onSuccess: () => {
      toast.success('테넌트를 활성화했습니다.');
      invalidate();
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

  if (tenantQuery.isError || !tenantQuery.data) {
    return (
      <div className="space-y-4">
        <p className="text-sm text-muted-foreground">테넌트를 찾을 수 없습니다.</p>
        <Link to="/tenants" className="text-sm underline">
          목록으로 돌아가기
        </Link>
      </div>
    );
  }

  const tenant = tenantQuery.data;
  const isActive = tenant.status === 'ACTIVE';

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
        {isActive ? (
          <StatusBadge type="active">활성</StatusBadge>
        ) : (
          <StatusBadge type="warning">정지됨</StatusBadge>
        )}

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
            <span className="text-muted-foreground">slug</span>
            <span className="font-mono text-[13px]">{tenant.slug}</span>
            <span className="text-muted-foreground">상태</span>
            <span>
              {isActive ? (
                <StatusBadge type="active">활성</StatusBadge>
              ) : (
                <StatusBadge type="warning">정지됨</StatusBadge>
              )}
            </span>
            <span className="text-muted-foreground">멤버 수</span>
            <span className="tabular-nums">{tenant.memberCount}</span>
            <span className="text-muted-foreground">생성일</span>
            <span>{formatDateTimeMinute(tenant.createdAt)}</span>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>멤버 ({tenant.memberCount})</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {membersQuery.isError && isForbidden(membersQuery.error) ? (
            // 기본 정보는 그대로 두고 멤버 카드만 바꾼다 — 나머지 화면을 날릴 이유가 없다.
            <InlineBanner variant="info">멤버를 조회할 권한이 없습니다.</InlineBanner>
          ) : (
            <>
              <p className="text-sm text-muted-foreground">
                역할은 워크스페이스 내 표시용이며, 실제 권한은 워크스페이스 관리자가 설정합니다.
              </p>
              <div className="rounded-md border">
                <Table aria-label="테넌트 멤버 목록">
                  <TableHeader>
                    <TableRow>
                      <TableHead>이름/아이디</TableHead>
                      <TableHead>이메일</TableHead>
                      <TableHead>역할</TableHead>
                      <TableHead>상태</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {membersQuery.data && membersQuery.data.length > 0 ? (
                      membersQuery.data.map((m) => (
                        <TableRow key={m.userId}>
                          <TableCell className="font-medium" title={`userId: ${m.userId}`}>
                            {m.username}
                          </TableCell>
                          <TableCell>{m.email ?? '-'}</TableCell>
                          <TableCell>
                            <Badge variant="outline">{m.role}</Badge>
                          </TableCell>
                          <TableCell>{m.status}</TableCell>
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
              {`"${tenant.name}"(${tenant.slug}) 를 정지합니다. 이 워크스페이스의 멤버 ${tenant.memberCount}명은 새로 로그인하거나 세션을 갱신하는 시점부터 접근이 차단됩니다. 이미 발급된 세션은 최대 30분간 유효합니다. 정지는 언제든 되돌릴 수 있습니다.`}
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
