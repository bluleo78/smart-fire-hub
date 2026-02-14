import { useState, useEffect, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { rolesApi } from '../../api/roles';
import { permissionsApi } from '../../api/permissions';
import type { RoleDetailResponse } from '../../types/role';
import type { PermissionResponse } from '../../types/role';
import { updateRoleSchema } from '../../lib/validations/role';
import type { UpdateRoleFormData } from '../../lib/validations/role';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Checkbox } from '../../components/ui/checkbox';
import { Badge } from '../../components/ui/badge';
import { Separator } from '../../components/ui/separator';
import { Skeleton } from '../../components/ui/skeleton';
import { ArrowLeft } from 'lucide-react';
import { toast } from 'sonner';
import type { ErrorResponse } from '../../types/auth';
import axios from 'axios';

export function RoleDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [role, setRole] = useState<RoleDetailResponse | null>(null);
  const [allPermissions, setAllPermissions] = useState<PermissionResponse[]>([]);
  const [selectedPermissionIds, setSelectedPermissionIds] = useState<number[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isSavingRole, setIsSavingRole] = useState(false);
  const [isSavingPermissions, setIsSavingPermissions] = useState(false);
  const [roleError, setRoleError] = useState('');

  const form = useForm<UpdateRoleFormData>({
    resolver: zodResolver(updateRoleSchema),
    defaultValues: {
      name: '',
      description: '',
    },
  });

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
        <Button variant="ghost" size="icon" onClick={() => navigate('/admin/roles')}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <h1 className="text-2xl font-bold">역할 상세</h1>
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
            <Button type="submit" disabled={isSavingRole}>
              {isSavingRole ? '저장 중...' : '저장'}
            </Button>
          </form>
        </CardContent>
      </Card>

      <Separator />

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
          <Button onClick={handleSavePermissions} disabled={isSavingPermissions}>
            {isSavingPermissions ? '저장 중...' : '권한 저장'}
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
