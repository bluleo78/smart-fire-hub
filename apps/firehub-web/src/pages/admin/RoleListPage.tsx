import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle, Plus, Trash2 } from 'lucide-react';
import { type ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { FormField } from '@/components/ui/form-field';
import { InlineBanner } from '@/components/ui/inline-banner';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useRoleClearance } from '@/hooks/queries/useSecurityLevels';
import { extractApiError, handleApiError } from '@/lib/api-error';
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
import { createRoleSchema } from '../../lib/validations/role';
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
  const [createError, setCreateError] = useState('');

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
      toast.error('역할 목록을 불러오는데 실패했습니다.');
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
    setCreateError('');
  }, [nameValue]);

  const onCreateSubmit = async (data: CreateRoleFormData) => {
    try {
      setCreateError('');
      await rolesApi.createRole({
        name: data.name,
        description: data.description || undefined,
      });
      toast.success('역할이 생성되었습니다.');
      setDialogOpen(false);
      form.reset();
      fetchRoles();
    } catch (error) {
      setCreateError(extractApiError(error, '역할 생성에 실패했습니다.'));
    }
  };

  const handleDelete = async (role: RoleResponse) => {
    try {
      await rolesApi.deleteRole(role.id);
      toast.success(`역할 "${role.name}"${iGa(role.name)} 삭제되었습니다.`);
      fetchRoles();
    } catch (error) {
      handleApiError(error, '역할 삭제에 실패했습니다.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">역할 관리</h1>
        {canWriteRole && (
          <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
            <DialogTrigger asChild>
              <Button>
                <Plus className="h-4 w-4" />
                역할 추가
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>새 역할 생성</DialogTitle>
                <DialogDescription className="sr-only">새로운 사용자 역할을 생성합니다.</DialogDescription>
              </DialogHeader>
              <form onSubmit={form.handleSubmit(onCreateSubmit)} className="space-y-4">
                <FormField
                  label="역할 이름"
                  htmlFor="role-name"
                  error={form.formState.errors.name?.message}
                >
                  <Input
                    id="role-name"
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
                {createError && (
                  <p className="text-sm text-destructive">{createError}</p>
                )}
                <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
                  {form.formState.isSubmitting ? '생성 중...' : '생성'}
                </Button>
              </form>
            </DialogContent>
          </Dialog>
        )}
      </div>

      <div className="rounded-md border">
        <Table aria-label="역할 목록">
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>유형</TableHead>
              <TableHead>설명</TableHead>
              <TableHead className="w-[60px]" />
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
                  <TableCell className="font-medium">{role.name}</TableCell>
                  <TableCell>
                    {role.isSystem ? (
                      <Badge variant="outline">시스템</Badge>
                    ) : (
                      <Badge variant="secondary">사용자 정의</Badge>
                    )}
                  </TableCell>
                  <TableCell>{role.description ?? '-'}</TableCell>
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
