import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle, Plus, Trash2 } from 'lucide-react';
import { type ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { PendingSubmitButton } from '@/components/PendingSubmitButton';
import { TruncatedLink } from '@/components/TruncatedLink';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { FormField } from '@/components/ui/form-field';
import { InlineBanner } from '@/components/ui/inline-banner';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { TruncatedText } from '@/components/ui/truncated-text';
import { useRoleClearance } from '@/hooks/queries/useSecurityLevels';
import { useCloseGeneration } from '@/hooks/useCloseGeneration';
import { useGuardedSubmit } from '@/hooks/useSubmitGuard';
import { extractApiError, handleApiError, isBadRequestError, isNotFoundError } from '@/lib/api-error';
import { iGa } from '@/lib/utils';

import { rolesApi } from '../../api/roles';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '../../components/ui/dialog';
import { Input } from '../../components/ui/input';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { useMyPermissions } from '../../hooks/queries/useMyPermissions';
import type { CreateRoleFormData } from '../../lib/validations/role';
import { createRoleSchema, ROLE_NAME_RULE } from '../../lib/validations/role';
import type { RoleResponse } from '../../types/role';

/**
 * 사용자 정의 역할의 삭제 버튼 + 확인 창 (WD-123).
 * 지우면 이 역할을 쓰는 사용자가 이 역할의 권한을 잃으므로, 창을 열 때마다 사용자 수를 새로 물어 영향 안내를 보인다
 * (역할 상세 열람 등급 카드와 같은 수). 다른 화면에서 역할을 부여·회수했을 수 있어 캐시를 쓰지 않고(staleTime 0),
 * 수를 확인하는 동안에는 안내를 보기 전에 지우지 않도록 삭제 버튼을 막는다. 조회가 실패하면 경고만 하고 삭제는 막지 않는다.
 */
function RoleDeleteButton({ role, onConfirm }: { role: RoleResponse; onConfirm: () => void }) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const { data, isFetching, isError } = useRoleClearance(role.id, { enabled: open, staleTime: 0 });

  let impact: ReactNode = null;
  if (isFetching) {
    impact = <p className="text-sm text-muted-foreground">이 역할을 쓰는 사용자를 확인하는 중…</p>;
  } else if (isError || !data) {
    impact = (
      <InlineBanner variant="warning" icon={<AlertTriangle />}>
        이 역할을 쓰는 사용자 수를 불러오지 못했습니다. 사용자가 있다면 이 역할의 권한을 잃습니다.
      </InlineBanner>
    );
  } else if (data.userCount > 0) {
    impact = (
      <InlineBanner variant="destructive" icon={<AlertTriangle />} data-testid="role-delete-impact">
        이 역할을 쓰는 사용자 {data.userCount}명이 이 역할의 권한을 즉시 잃습니다.
      </InlineBanner>
    );
  }

  return (
    <>
      {/* 삭제 버튼 — 아이콘만 있으므로 스크린 리더를 위해 aria-label 필수 */}
      <Button
        ref={triggerRef}
        variant="outline"
        size="sm"
        aria-label={`${role.name} 역할 삭제`}
        onClick={(e) => {
          // 행 클릭(상세로 이동)으로 번지지 않게 막는다 — DeleteConfirmDialog 의 트리거 모드와 같은 처리.
          e.stopPropagation();
          setOpen(true);
        }}
      >
        <Trash2 className="h-4 w-4" />
      </Button>
      <DeleteConfirmDialog
        open={open}
        onOpenChange={setOpen}
        entityName="역할"
        itemName={role.name}
        onConfirm={onConfirm}
        restoreFocusRef={triggerRef}
        details={impact}
        confirmDisabled={isFetching}
      />
    </>
  );
}

export default function RoleListPage() {
  const navigate = useNavigate();
  // 역할 추가(POST /roles) = role:write, 삭제(DELETE /roles/{id}) = role:delete — 서버가 막는 진입점은 숨긴다(WD-121).
  // 서버에 관리자 우회가 없으므로(ADMIN 역할이 권한을 가짐) 권한 코드만 본다. 라우트 가드가 권한 조회를 끝낸 뒤 그린다.
  const { permissions } = useMyPermissions();
  const canWriteRole = permissions.has('role:write');
  const canDeleteRole = permissions.has('role:delete');
  const [roles, setRoles] = useState<RoleResponse[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [dialogOpen, setDialogOpen] = useState(false);
  // 생성 실패 서버 오류 — 이름 칸 오류(400: 이름 중복 등, 칸에 이어 읽힘)와 이름과 무관한 폼 오류(그 밖, 폼 문단 알림)는
  // 동시에 나지 않으므로 한 상태에 field 로 구분해 담는다. 지울 때는 안정적인 setter 하나만 부르면 된다.
  const [serverError, setServerError] = useState<{ field: 'name' | 'form'; message: string } | null>(null);

  const form = useForm<CreateRoleFormData>({
    resolver: zodResolver(createRoleSchema),
    defaultValues: {
      name: '',
      description: '',
    },
  });

  const fetchRoles = useCallback(async () => {
    setIsLoading(true);
    try {
      const { data } = await rolesApi.getRoles();
      setRoles(data);
    } catch {
      toast.error('역할 목록을 불러오는 데 실패했습니다.');
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchRoles();
  }, [fetchRoles]);

  // 역할 이름 입력값이 바뀌면 이전 서버 중복 에러를 지운다.
  // (제출 시에만 초기화하면, 값만 고치고 아직 제출 전인 상태에서 stale 에러가 남는 문제가 있었다 - #558)
  const nameValue = form.watch('name');
  useEffect(() => {
    setServerError(null);
  }, [nameValue]);

  // 닫힘 세대(WD-819) — 요청 중 닫힘·다시 열림 판정(상세는 훅 JSDoc)
  const closeGen = useCloseGeneration(dialogOpen);

  const onCreateSubmit = async (data: CreateRoleFormData) => {
    const { isStale, isReopened } = closeGen.capture();
    try {
      setServerError(null);
      await rolesApi.createRole({
        name: data.name,
        description: data.description || undefined,
      });
      toast.success('역할이 생성되었습니다.');
      // 늦은 성공 + 다시 열려 있음(WD-819): 입력 중일 수 있어 열림·폼은 건드리지 않고 목록만 갱신한다.
      // (다시 열지 않았다면 아래 평소 경로 — 이미 닫혀 있어도 폼은 비워야 다음에 열 때 값이 남지 않는다.)
      if (isReopened()) {
        fetchRoles();
        return;
      }
      setDialogOpen(false);
      form.reset();
      fetchRoles();
    } catch (error) {
      // 요청 중에 닫혔다면 칸 오류 대신 토스트로 알린다(다시 열었을 때 묵은 오류가 남지 않게, WD-819)
      if (isStale()) {
        handleApiError(error, '역할 생성에 실패했습니다.');
        return;
      }
      const message = extractApiError(error, '역할 생성에 실패했습니다.');
      // CreateRoleRequest 는 name 만 검증하므로 400 은 이름 문제다 — 이름 칸 오류로 이어 읽히게 하고 칸으로 포커스를 돌린다.
      if (isBadRequestError(error)) {
        setServerError({ field: 'name', message });
        form.setFocus('name');
      } else {
        setServerError({ field: 'form', message });
      }
    }
  };

  const submitCreate = useGuardedSubmit(form.handleSubmit, onCreateSubmit);

  const handleDelete = async (role: RoleResponse) => {
    try {
      await rolesApi.deleteRole(role.id);
      toast.success(`역할 "${role.name}"${iGa(role.name)} 삭제되었습니다.`);
      fetchRoles();
    } catch (error) {
      handleApiError(error, '역할 삭제에 실패했습니다.');
      // 이미 다른 곳에서 삭제된 역할(404)이면 서버의 「…이미 삭제되었을 수 있습니다.」 안내 뒤 목록을 다시 읽어 남은 행을 지운다(WD-287).
      if (isNotFoundError(error)) fetchRoles();
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">역할 관리</h1>
        {canWriteRole && (
          <Dialog
            open={dialogOpen}
            onOpenChange={(next) => {
              // 닫힐 때(취소·X·ESC·바깥 클릭 모두) 서버 오류를 지워 다시 열었을 때 묵은 오류가 남지 않게 한다.
              if (!next) {
                setServerError(null);
              }
              setDialogOpen(next);
            }}
          >
            <DialogTrigger asChild>
              <Button>
                <Plus className="h-4 w-4" />
                새 역할
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>새 역할</DialogTitle>
                <DialogDescription className="sr-only">새로운 사용자 역할을 생성합니다.</DialogDescription>
              </DialogHeader>
              <form onSubmit={submitCreate} className="space-y-4">
                {/* WD-817: 필수 표시와 입력 규칙(createRoleSchema 의 정규식)을 입력 전에 보인다 — 예전에는 제출해야만 규칙을 알 수 있었다.
                    규칙 문구는 FormField 의 description 으로 그려 aria-describedby 로 칸에 늘 잇는다. */}
                <FormField
                  label="역할 이름"
                  htmlFor="role-name"
                  required
                  error={form.formState.errors.name?.message ?? (serverError?.field === 'name' ? serverError.message : undefined)}
                  description={ROLE_NAME_RULE}
                >
                  {/* * 는 눈으로만 보이므로 보조 기술에는 aria-required 로 필수를 알린다(브라우저 기본 검증 말풍선을 피해 required 는 쓰지 않는다) */}
                  <Input
                    id="role-name"
                    aria-required
                    {...form.register('name')}
                  />
                </FormField>
                <FormField
                  label="설명 (선택)"
                  htmlFor="role-description"
                >
                  <Input
                    id="role-description"
                    {...form.register('description')}
                  />
                </FormField>
                {serverError?.field === 'form' && (
                  <p role="alert" className="text-sm text-destructive">{serverError.message}</p>
                )}
                <PendingSubmitButton className="w-full" pending={form.formState.isSubmitting}>
                  {form.formState.isSubmitting ? '만드는 중...' : '만들기'}
                </PendingSubmitButton>
              </form>
            </DialogContent>
          </Dialog>
        )}
      </div>

      {/* WD-634: 긴 값 상한 = (표 폭 100cqw − --k) × 몫. --k = 짧은 칸 선언 폭(유형·삭제) + 긴 값 칸 여백 */}
      <div className="@container [--k:calc(6rem_+_3.75rem_+_2rem)] rounded-md border">
        <Table aria-label="역할 목록">
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead className="w-[6rem]">유형</TableHead>
              <TableHead>설명</TableHead>
              <TableHead className="w-[3.75rem]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={4} rows={3} />
            ) : roles.length > 0 ? (
              roles.map((role) => (
                <TableRow
                  key={role.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors row-hover"
                  onClick={() => navigate(`/admin/roles/${role.id}`)}
                >
                  {/* WD-559·WD-634: 긴 값은 표 컨테이너 비례 상한(cqw)으로 말줄임 — 이유는 TruncatedText 설명 */}
                  <TableCell className="font-medium">
                    {/* 키보드 접근성(WD-286): 행 클릭은 마우스 전용이라 이름을 상세 링크로 둔다 */}
                    <TruncatedLink to={`/admin/roles/${role.id}`} value={role.name} className="max-w-[min(20rem,calc((100cqw_-_var(--k))_*_0.38))]" />
                  </TableCell>
                  <TableCell>
                    {role.isSystem ? (
                      <Badge variant="outline">시스템</Badge>
                    ) : (
                      <Badge variant="secondary">사용자 정의</Badge>
                    )}
                  </TableCell>
                  <TableCell>
                    <TruncatedText value={role.description} className="max-w-[min(32rem,calc((100cqw_-_var(--k))_*_0.62))]" />
                  </TableCell>
                  <TableCell>
                    {canDeleteRole && !role.isSystem && (
                      <RoleDeleteButton role={role} onConfirm={() => handleDelete(role)} />
                    )}
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={4} message="역할이 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
