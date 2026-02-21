import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { rolesApi } from '../../api/roles';
import type { RoleResponse } from '../../types/role';
import { createRoleSchema } from '../../lib/validations/role';
import type { CreateRoleFormData } from '../../lib/validations/role';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Badge } from '../../components/ui/badge';
import {
  Table,
  TableBody,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '../../components/ui/dialog';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { FormField } from '@/components/ui/form-field';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { extractApiError, handleApiError } from '@/lib/api-error';

export default function RoleListPage() {
  const navigate = useNavigate();
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
      toast.success(`역할 "${role.name}"이(가) 삭제되었습니다.`);
      fetchRoles();
    } catch (error) {
      handleApiError(error, '역할 삭제에 실패했습니다.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">역할 관리</h1>
        <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="mr-2 h-4 w-4" />
              역할 추가
            </Button>
          </DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>새 역할 생성</DialogTitle>
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
      </div>

      <div className="rounded-md border">
        <Table>
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
                  className="cursor-pointer hover:bg-muted/50 transition-colors"
                  onClick={() => navigate(`/admin/roles/${role.id}`)}
                >
                  <td className="p-4 font-medium">{role.name}</td>
                  <td className="p-4">
                    {role.isSystem ? (
                      <Badge variant="outline">시스템</Badge>
                    ) : (
                      <Badge variant="secondary">사용자 정의</Badge>
                    )}
                  </td>
                  <td className="p-4">{role.description ?? '-'}</td>
                  <td className="p-4">
                    {!role.isSystem && (
                      <DeleteConfirmDialog
                        entityName="역할"
                        itemName={role.name}
                        onConfirm={() => handleDelete(role)}
                        trigger={
                          <Button variant="outline" size="sm">
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        }
                      />
                    )}
                  </td>
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
