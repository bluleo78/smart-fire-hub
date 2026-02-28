import { zodResolver } from '@hookform/resolvers/zod';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { toast } from 'sonner';
import { z } from 'zod';

import { Button } from '../../components/ui/button';
import { DeleteConfirmDialog } from '../../components/ui/delete-confirm-dialog';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { FormField } from '../../components/ui/form-field';
import { Input } from '../../components/ui/input';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { TableEmptyRow } from '../../components/ui/table-empty';
import { TableSkeletonRows } from '../../components/ui/table-skeleton';
import {
  useCategories,
  useCreateCategory,
  useDeleteCategory,
  useUpdateCategory,
} from '../../hooks/queries/useDatasets';
import { handleApiError } from '../../lib/api-error';
import type { CategoryResponse } from '../../types/dataset';

const categorySchema = z.object({
  name: z.string().min(1, '카테고리 이름을 입력해주세요.'),
  description: z.string().optional(),
});

type CategoryFormData = z.infer<typeof categorySchema>;

export default function CategoryListPage() {
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [editingCategory, setEditingCategory] = useState<CategoryResponse | null>(null);

  const { data: categories, isLoading } = useCategories();
  const createCategory = useCreateCategory();
  const updateCategory = useUpdateCategory(editingCategory?.id || 0);
  const deleteCategory = useDeleteCategory();

  const createForm = useForm<CategoryFormData>({
    resolver: zodResolver(categorySchema),
  });

  const editForm = useForm<CategoryFormData>({
    resolver: zodResolver(categorySchema),
  });

  const handleCreate = async (data: CategoryFormData) => {
    try {
      await createCategory.mutateAsync(data);
      toast.success('카테고리가 생성되었습니다.');
      setIsCreateOpen(false);
      createForm.reset();
    } catch (error) {
      handleApiError(error, '카테고리 생성에 실패했습니다.');
    }
  };

  const handleUpdate = async (data: CategoryFormData) => {
    if (!editingCategory) return;
    try {
      await updateCategory.mutateAsync(data);
      toast.success('카테고리가 수정되었습니다.');
      setEditingCategory(null);
      editForm.reset();
    } catch (error) {
      handleApiError(error, '카테고리 수정에 실패했습니다.');
    }
  };

  const handleDelete = async (id: number, name: string) => {
    try {
      await deleteCategory.mutateAsync(id);
      toast.success(`카테고리 "${name}"이(가) 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, '카테고리 삭제에 실패했습니다.');
    }
  };

  const openEditDialog = (category: CategoryResponse) => {
    setEditingCategory(category);
    editForm.reset({
      name: category.name,
      description: category.description || '',
    });
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">카테고리 관리</h1>
        <Button onClick={() => { createForm.reset(); setIsCreateOpen(true); }}>
          <Plus className="mr-2 h-4 w-4" />
          새 카테고리
        </Button>
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>설명</TableHead>
              <TableHead className="w-[120px]">작업</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={3} rows={3} />
            ) : categories && categories.length > 0 ? (
              categories.map((category) => (
                <TableRow key={category.id}>
                  <TableCell className="font-medium">{category.name}</TableCell>
                  <TableCell>{category.description || '-'}</TableCell>
                  <TableCell>
                    <div className="flex gap-2">
                      <Button variant="outline" size="sm" onClick={() => openEditDialog(category)}>
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <DeleteConfirmDialog
                        entityName="카테고리"
                        itemName={category.name}
                        onConfirm={() => handleDelete(category.id, category.name)}
                        trigger={
                          <Button variant="outline" size="sm">
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        }
                      />
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={3} message="카테고리가 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>

      {/* Create Dialog */}
      <Dialog open={isCreateOpen} onOpenChange={(open) => { if (!open) setIsCreateOpen(false); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>카테고리 생성</DialogTitle>
          </DialogHeader>
          <form onSubmit={createForm.handleSubmit(handleCreate)} className="space-y-4">
            <FormField
              label="이름"
              htmlFor="create-name"
              error={createForm.formState.errors.name?.message}
            >
              <Input
                id="create-name"
                {...createForm.register('name')}
                placeholder="카테고리 이름"
              />
            </FormField>
            <FormField label="설명" htmlFor="create-description">
              <Input
                id="create-description"
                {...createForm.register('description')}
                placeholder="카테고리 설명 (선택사항)"
              />
            </FormField>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setIsCreateOpen(false)}>
                취소
              </Button>
              <Button type="submit" disabled={createCategory.isPending}>
                생성
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Edit Dialog */}
      <Dialog open={!!editingCategory} onOpenChange={(open) => { if (!open) setEditingCategory(null); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>카테고리 수정</DialogTitle>
          </DialogHeader>
          <form onSubmit={editForm.handleSubmit(handleUpdate)} className="space-y-4">
            <FormField
              label="이름"
              htmlFor="edit-name"
              error={editForm.formState.errors.name?.message}
            >
              <Input
                id="edit-name"
                {...editForm.register('name')}
                placeholder="카테고리 이름"
              />
            </FormField>
            <FormField label="설명" htmlFor="edit-description">
              <Input
                id="edit-description"
                {...editForm.register('description')}
                placeholder="카테고리 설명 (선택사항)"
              />
            </FormField>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setEditingCategory(null)}>
                취소
              </Button>
              <Button type="submit" disabled={updateCategory.isPending}>
                수정
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
