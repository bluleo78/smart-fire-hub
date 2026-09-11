import { zodResolver } from '@hookform/resolvers/zod';
import axios from 'axios';
import { ArrowLeft } from 'lucide-react';
import { useEffect, useMemo,useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate,useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { permissionsApi } from '../../api/permissions';
import { rolesApi } from '../../api/roles';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../../components/ui/alert-dialog';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Checkbox } from '../../components/ui/checkbox';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Separator } from '../../components/ui/separator';
import { Skeleton } from '../../components/ui/skeleton';
import { useUnsavedChangesGuard } from '../../hooks/useUnsavedChangesGuard';
import type { UpdateRoleFormData } from '../../lib/validations/role';
import { updateRoleSchema } from '../../lib/validations/role';
import type { ErrorResponse } from '../../types/auth';
import type { RoleDetailResponse } from '../../types/role';
import type { PermissionResponse } from '../../types/role';

export default function RoleDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [role, setRole] = useState<RoleDetailResponse | null>(null);
  const [allPermissions, setAllPermissions] = useState<PermissionResponse[]>([]);
  const [selectedPermissionIds, setSelectedPermissionIds] = useState<number[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isSavingRole, setIsSavingRole] = useState(false);
  const [isSavingPermissions, setIsSavingPermissions] = useState(false);
  const [roleError, setRoleError] = useState('');
  // 권한 전체 해제 확인 다이얼로그 표시 상태 — "권한 저장" 시점에 선택된 권한이 0개일 때만 열린다 (#567)
  // 사용자 상세(#512)의 "역할 전체 해제"와 동일한 위험 패턴: 이 역할을 사용 중인 모든 사용자가 즉시 권한을 전부 잃는다.
  const [isClearPermissionsDialogOpen, setIsClearPermissionsDialogOpen] = useState(false);

  const form = useForm<UpdateRoleFormData>({
    resolver: zodResolver(updateRoleSchema),
    defaultValues: {
      name: '',
      description: '',
    },
  });

  // 이름/설명 폼이 저장되지 않은 채로 이탈하는 것을 막는 가드 (#636) — 목록으로 돌아가기 버튼은
  // navigate() 대신 requestNavigate를 통해서만 이동해야 dirty 시 확인 다이얼로그가 뜬다.
  const { dialog: unsavedChangesDialog, requestNavigate } = useUnsavedChangesGuard(
    form.formState.isDirty,
  );

  useEffect(() => {
    const fetchData = async () => {
      if (!id) return;
      setIsLoading(true);
      try {
        const [roleRes, permRes] = await Promise.all([
          rolesApi.getRoleById(Number(id)),
          permissionsApi.getPermissions(),
        ]);
        setRole(roleRes.data);
        setAllPermissions(permRes.data);
        setSelectedPermissionIds(roleRes.data.permissions.map(p => p.id));
        form.reset({
          name: roleRes.data.name,
          description: roleRes.data.description ?? '',
        });
      } catch {
        toast.error('역할 정보를 불러오는데 실패했습니다.');
        navigate('/admin/roles');
      } finally {
        setIsLoading(false);
      }
    };

    fetchData();
  }, [id, navigate, form]);

  const permissionsByCategory = useMemo(() => {
    const grouped: Record<string, PermissionResponse[]> = {};
    for (const perm of allPermissions) {
      if (!grouped[perm.category]) {
        grouped[perm.category] = [];
      }
      grouped[perm.category].push(perm);
    }
    return grouped;
  }, [allPermissions]);

  const handlePermissionToggle = (permId: number, checked: boolean) => {
    setSelectedPermissionIds(prev =>
      checked ? [...prev, permId] : prev.filter(id => id !== permId)
    );
  };

  const onRoleSubmit = async (data: UpdateRoleFormData) => {
    if (!role) return;
    setIsSavingRole(true);
    setRoleError('');
    try {
      await rolesApi.updateRole(role.id, {
        name: data.name,
        description: data.description || undefined,
      });
      const { data: updatedRole } = await rolesApi.getRoleById(role.id);
      setRole(updatedRole);
      toast.success('역할 정보가 업데이트되었습니다.');
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        setRoleError(errData.message || '역할 업데이트에 실패했습니다.');
      } else {
        setRoleError('역할 업데이트에 실패했습니다.');
      }
    } finally {
      setIsSavingRole(false);
    }
  };

  /**
   * "권한 저장" 버튼 클릭 핸들러 — 선택된 권한이 0개(전체 해제)면 확인 다이얼로그를 먼저 띄우고,
   * 그 외에는 바로 저장을 진행한다. 이 역할을 사용 중인 모든 사용자가 즉시 권한을 전부 잃는
   * 파급력 큰 작업이므로 #512(사용자의 역할 전체 해제)와 동일한 확인 절차를 둔다 (#567).
   */
  const handleSavePermissionsClick = () => {
    if (selectedPermissionIds.length === 0) {
      setIsClearPermissionsDialogOpen(true);
      return;
    }
    void handleSavePermissions();
  };

  const handleSavePermissions = async () => {
    if (!role) return;
    setIsSavingPermissions(true);
    try {
      await rolesApi.setPermissions(role.id, { permissionIds: selectedPermissionIds });
      const { data: updatedRole } = await rolesApi.getRoleById(role.id);
      setRole(updatedRole);
      setSelectedPermissionIds(updatedRole.permissions.map(p => p.id));
      toast.success('권한이 저장되었습니다.');
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '권한 저장에 실패했습니다.');
      } else {
        toast.error('권한 저장에 실패했습니다.');
      }
    } finally {
      setIsSavingPermissions(false);
    }
  };

  if (isLoading) {
    return (
      <div className="mx-auto max-w-2xl space-y-6">
        <Skeleton className="h-8 w-48" />
        <Card>
          <CardContent className="space-y-4 pt-6">
            <Skeleton className="h-4 w-64" />
            <Skeleton className="h-4 w-48" />
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!role) return null;

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div className="flex items-center gap-4">
        {/* 역할 목록으로 돌아가는 뒤로가기 버튼 — 접근성 보강 (#102)
            (#636) navigate() 직접 호출 대신 requestNavigate 사용 — dirty 상태면 이탈 가드 다이얼로그를 띄운다 */}
        <Button variant="ghost" size="icon" onClick={() => requestNavigate('/admin/roles')} aria-label="목록으로 돌아가기" title="목록으로 돌아가기">
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">역할 상세</h1>
        {role.isSystem && (
          <Badge variant="outline">시스템 역할</Badge>
        )}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>역할 정보</CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={form.handleSubmit(onRoleSubmit)} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="role-name">역할 이름</Label>
              <Input
                id="role-name"
                {...form.register('name')}
                disabled={role.isSystem}
              />
              {form.formState.errors.name && (
                <p className="text-sm text-destructive">{form.formState.errors.name.message}</p>
              )}
            </div>
            <div className="space-y-2">
              <Label htmlFor="role-description">설명</Label>
              <Input
                id="role-description"
                {...form.register('description')}
              />
            </div>
            {roleError && (
              <p className="text-sm text-destructive">{roleError}</p>
            )}
            {/* 시스템 역할은 수정 불가 — isSystem 플래그로 저장 버튼도 비활성화 */}
            <Button type="submit" disabled={isSavingRole || role.isSystem}>
              {isSavingRole ? '저장 중...' : '저장'}
            </Button>
          </form>
        </CardContent>
      </Card>

      <Separator />

      {/* 권한 전체 해제 확인 AlertDialog — "권한 저장" 시점에 선택된 권한이 0개일 때만 표시된다 (#567) */}
      <AlertDialog open={isClearPermissionsDialogOpen} onOpenChange={setIsClearPermissionsDialogOpen}>
        <AlertDialogContent size="sm">
          <AlertDialogHeader>
            <AlertDialogTitle>권한 전체 해제</AlertDialogTitle>
            <AlertDialogDescription>
              이 역할의 모든 권한을 해제하면 이 역할을 사용 중인 모든 사용자가 해당 권한을 즉시 잃습니다. 계속하시겠습니까?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                setIsClearPermissionsDialogOpen(false);
                void handleSavePermissions();
              }}
            >
              권한 해제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Card>
        <CardHeader>
          <CardTitle>권한 할당</CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          {Object.entries(permissionsByCategory).map(([category, perms]) => (
            <div key={category} className="space-y-3">
              <h3 className="text-sm font-semibold uppercase text-muted-foreground">{category}</h3>
              <div className="space-y-2">
                {perms.map((perm) => (
                  <div key={perm.id} className="flex items-center gap-3">
                    <Checkbox
                      id={`perm-${perm.id}`}
                      checked={selectedPermissionIds.includes(perm.id)}
                      onCheckedChange={(checked) => handlePermissionToggle(perm.id, checked === true)}
                      disabled={role.isSystem}
                    />
                    <Label htmlFor={`perm-${perm.id}`} className="text-sm">
                      {perm.code}
                    </Label>
                    {perm.description && (
                      <span className="text-sm text-muted-foreground">- {perm.description}</span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          ))}
          {allPermissions.length === 0 && (
            <p className="text-sm text-muted-foreground">등록된 권한이 없습니다.</p>
          )}
          <Button onClick={handleSavePermissionsClick} disabled={isSavingPermissions || role.isSystem}>
            {isSavingPermissions ? '저장 중...' : '권한 저장'}
          </Button>
        </CardContent>
      </Card>

      {/* 미저장 변경 이탈 확인 다이얼로그 (#636) */}
      {unsavedChangesDialog}
    </div>
  );
}
