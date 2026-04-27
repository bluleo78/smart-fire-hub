import { zodResolver } from '@hookform/resolvers/zod';
import axios from 'axios';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { toast } from 'sonner';
import { z } from 'zod';

import { useDebounceValue } from '@/hooks/useDebounceValue';

import { Button } from '../../components/ui/button';
import { DeleteConfirmDialog } from '../../components/ui/delete-confirm-dialog';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { FormField } from '../../components/ui/form-field';
import { Input } from '../../components/ui/input';
import { SearchInput } from '../../components/ui/search-input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
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
import { iGa } from '../../lib/utils';
import type { CategoryResponse } from '../../types/dataset';

/**
 * 카테고리 목록 정렬 옵션
 * - CategoryResponse 스키마는 id/name/description 만 제공하므로 정렬 키도 그 범위 내로 한정한다.
 * - 생성일/항목수 등은 백엔드 스키마 변경(parentId, displayOrder, createdAt 노출)이 필요하므로 후속 작업으로 분리.
 *   id 기반 정렬은 "생성순(autoincrement)"의 근사치로 사용한다.
 */
type SortKey = 'name-asc' | 'name-desc' | 'id-asc' | 'id-desc';

const SORT_OPTIONS: { value: SortKey; label: string }[] = [
  { value: 'name-asc', label: '이름 (오름차순)' },
  { value: 'name-desc', label: '이름 (내림차순)' },
  { value: 'id-asc', label: '생성순 (오래된 순)' },
  { value: 'id-desc', label: '생성순 (최신 순)' },
];

const categorySchema = z.object({
  name: z.string().min(1, '카테고리 이름을 입력해주세요.').max(50, '카테고리 이름은 50자 이하여야 합니다.'),
  description: z.string().max(255, '설명은 255자 이하여야 합니다.').optional(),
});

type CategoryFormData = z.infer<typeof categorySchema>;

export default function CategoryListPage() {
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [editingCategory, setEditingCategory] = useState<CategoryResponse | null>(null);
  // 검색어/정렬 상태 — 클라이언트 사이드에서만 적용 (백엔드 변경 없음)
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebounceValue(search, 200);
  const [sortKey, setSortKey] = useState<SortKey>('name-asc');

  const { data: categories, isLoading } = useCategories();

  /**
   * 검색·정렬을 클라이언트에서 적용한 결과 목록.
   * - 검색은 이름·설명 부분일치 (대소문자 무시).
   * - 정렬은 SORT_OPTIONS 정의에 따른 안정적 비교 (이름은 localeCompare로 한국어 자모 정렬).
   * - useMemo: categories/검색어/정렬키 변경 시에만 재계산하여 행 렌더 비용 최소화.
   */
  const visibleCategories = useMemo(() => {
    if (!categories) return [];
    const keyword = debouncedSearch.trim().toLowerCase();
    const filtered = keyword
      ? categories.filter((c) => {
          const name = c.name.toLowerCase();
          const desc = (c.description ?? '').toLowerCase();
          return name.includes(keyword) || desc.includes(keyword);
        })
      : categories;
    // slice() 로 원본 보존 후 정렬 — TanStack Query 캐시 객체를 직접 변형하지 않기 위함
    const sorted = filtered.slice();
    switch (sortKey) {
      case 'name-asc':
        sorted.sort((a, b) => a.name.localeCompare(b.name, 'ko'));
        break;
      case 'name-desc':
        sorted.sort((a, b) => b.name.localeCompare(a.name, 'ko'));
        break;
      case 'id-asc':
        sorted.sort((a, b) => a.id - b.id);
        break;
      case 'id-desc':
        sorted.sort((a, b) => b.id - a.id);
        break;
    }
    return sorted;
  }, [categories, debouncedSearch, sortKey]);

  // 검색이 적용된 상태에서 결과가 0건일 때만 별도 안내를 노출 (전체 빈 상태와 구분)
  const isFiltering = debouncedSearch.trim().length > 0;
  const hasResults = visibleCategories.length > 0;
  const totalCount = categories?.length ?? 0;
  const createCategory = useCreateCategory();
  const updateCategory = useUpdateCategory(editingCategory?.id || 0);
  const deleteCategory = useDeleteCategory();

  // mode: 'onChange' — 입력 즉시 isValid 상태가 반영되어 저장 버튼 비활성화 동작에 사용
  const createForm = useForm<CategoryFormData>({
    resolver: zodResolver(categorySchema),
    mode: 'onChange',
  });

  // mode: 'onChange' — 수정 폼도 동일하게 실시간 유효성 반영
  const editForm = useForm<CategoryFormData>({
    resolver: zodResolver(categorySchema),
    mode: 'onChange',
  });

  /**
   * 카테고리 생성 핸들러
   * - 409 Conflict(중복 이름) 오류 시 한국어 메시지로 안내한다.
   * - 그 외 오류는 범용 에러 핸들러로 처리한다.
   */
  const handleCreate = async (data: CategoryFormData) => {
    try {
      await createCategory.mutateAsync(data);
      toast.success('카테고리가 생성되었습니다.');
      setIsCreateOpen(false);
      createForm.reset();
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 409) {
        toast.error('이미 사용 중인 카테고리 이름입니다.');
      } else {
        handleApiError(error, '카테고리 생성에 실패했습니다.');
      }
    }
  };

  /**
   * 카테고리 수정 핸들러
   * - 409 Conflict(중복 이름) 오류 시 한국어 메시지로 안내한다.
   * - 그 외 오류는 범용 에러 핸들러로 처리한다.
   */
  const handleUpdate = async (data: CategoryFormData) => {
    if (!editingCategory) return;
    try {
      await updateCategory.mutateAsync(data);
      toast.success('카테고리가 수정되었습니다.');
      setEditingCategory(null);
      editForm.reset();
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 409) {
        toast.error('이미 사용 중인 카테고리 이름입니다.');
      } else {
        handleApiError(error, '카테고리 수정에 실패했습니다.');
      }
    }
  };

  const handleDelete = async (id: number, name: string) => {
    try {
      await deleteCategory.mutateAsync(id);
      toast.success(`카테고리 "${name}"${iGa(name)} 삭제되었습니다.`);
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
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">카테고리 관리</h1>
        <Button onClick={() => { createForm.reset(); setIsCreateOpen(true); }}>
          <Plus className="h-4 w-4" />
          새 카테고리
        </Button>
      </div>

      {/* 검색·정렬 툴바 — 클라이언트 사이드 필터/정렬 (백엔드 호출 없음) */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <SearchInput
          placeholder="이름 또는 설명으로 검색..."
          aria-label="카테고리 검색"
          value={search}
          onChange={setSearch}
        />
        <div className="flex items-center gap-2">
          <span className="text-sm text-muted-foreground" aria-live="polite">
            {isFiltering ? `${visibleCategories.length} / ${totalCount}` : `${totalCount}개`}
          </span>
          <Select value={sortKey} onValueChange={(v) => setSortKey(v as SortKey)}>
            <SelectTrigger className="w-[180px]" aria-label="정렬 기준">
              <SelectValue placeholder="정렬" />
            </SelectTrigger>
            <SelectContent>
              {SORT_OPTIONS.map((opt) => (
                <SelectItem key={opt.value} value={opt.value}>
                  {opt.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
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
            ) : hasResults ? (
              visibleCategories.map((category) => (
                <TableRow key={category.id}>
                  <TableCell className="font-medium">{category.name}</TableCell>
                  <TableCell>{category.description || '-'}</TableCell>
                  <TableCell>
                    <div className="flex gap-2">
                      {/* 편집 버튼 — 아이콘만 있으므로 스크린 리더를 위해 aria-label 필수 */}
                      <Button
                        variant="outline"
                        size="sm"
                        aria-label={`${category.name} 편집`}
                        onClick={() => openEditDialog(category)}
                      >
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <DeleteConfirmDialog
                        entityName="카테고리"
                        itemName={category.name}
                        onConfirm={() => handleDelete(category.id, category.name)}
                        trigger={
                          /* 삭제 버튼 — 아이콘만 있으므로 스크린 리더를 위해 aria-label 필수 */
                          <Button variant="outline" size="sm" aria-label={`${category.name} 삭제`}>
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        }
                      />
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow
                colSpan={3}
                message={isFiltering ? '검색 결과가 없습니다.' : '카테고리가 없습니다.'}
              />
            )}
          </TableBody>
        </Table>
      </div>

      {/* Create Dialog */}
      <Dialog open={isCreateOpen} onOpenChange={(open) => { if (!open) setIsCreateOpen(false); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>카테고리 생성</DialogTitle>
            <DialogDescription className="sr-only">새 데이터셋 카테고리를 생성합니다.</DialogDescription>
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
              {/* isValid: 필수 필드(이름)가 비어 있으면 버튼 비활성화 */}
              <Button type="submit" disabled={createCategory.isPending || !createForm.formState.isValid}>
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
            <DialogDescription className="sr-only">데이터셋 카테고리 정보를 수정합니다.</DialogDescription>
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
              {/* isValid: 필수 필드(이름)가 비어 있으면 수정 버튼 비활성화 */}
              <Button type="submit" disabled={updateCategory.isPending || !editForm.formState.isValid}>
                수정
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
