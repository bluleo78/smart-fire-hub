import { zodResolver } from '@hookform/resolvers/zod';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { toast } from 'sonner';
import { z } from 'zod';

import { PageHeading } from '@/components/PageHeading';
import { TruncatedText } from '@/components/ui/truncated-text';

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
  useDatasetPermissions,
  useDeleteCategory,
  useUpdateCategory,
} from '../../hooks/queries/useDatasets';
import { handleApiError, isConflictError } from '../../lib/api-error';
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

/**
 * 카테고리 이름 중복(409) 시 이름 칸에 보이는 인라인 오류 문구 — 생성·수정 공통.
 * 카테고리 409 응답에는 code 가 없어 공용 applyDuplicateNameError(code DUPLICATE_NAME 필요)를 쓰지 못한다 —
 * 서버가 code 를 보내게 되면 그 헬퍼로 바꾼다.
 */
const DUPLICATE_NAME_MESSAGE = '이미 사용 중인 카테고리 이름입니다.';

/**
 * 409 가 돌아왔을 때 이름 칸이 아직 보낸 이름 그대로인가 (WD-905 리뷰 후속).
 * 요청이 도는 사이 이름을 고쳤거나(다른 이름이 중복으로 찍힘) 수정 폼이 다른 카테고리로 다시 채워졌으면
 * 지금 칸의 이름은 중복이 아니다 — 그때는 칸에 오류를 붙이지 않고(버튼이 잠기지 않게) 토스트로만 알린다.
 * 스키마가 이름을 trim 해서 보내므로 칸 값도 trim 해 견준다.
 */
const isSameSubmittedName = (currentName: string, submittedName: string) => currentName.trim() === submittedName;

const categorySchema = z.object({
  // trim() 없이 min(1)만 검사하면 공백만 있는 값(length >= 1)도 통과해
  // "생성" 버튼이 활성화되는 문제(#659)가 있어, trim() 후 길이를 검사하고
  // 제출 값도 trim된 문자열이 되도록 한다.
  name: z
    .string()
    .trim()
    .min(1, '카테고리 이름을 입력해주세요.')
    .max(50, '카테고리 이름은 50자 이하여야 합니다.'),
  description: z.string().max(255, '설명은 255자 이하여야 합니다.').optional(),
});

type CategoryFormData = z.infer<typeof categorySchema>;

export default function CategoryListPage() {
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [editingCategory, setEditingCategory] = useState<CategoryResponse | null>(null);
  // 검색어/정렬 상태 — 클라이언트 사이드에서만 적용 (백엔드 변경 없음)
  // 검색어는 SearchInput 이 입력이 멈춘 뒤(debounceMs) 넘겨준 값이라 여기서 다시 늦추지 않는다 (WD-815)
  const [search, setSearch] = useState('');
  const [sortKey, setSortKey] = useState<SortKey>('name-asc');

  const { data: categories, isLoading } = useCategories();

  // 필터·검색 빈 상태 문구가 같은 기준을 쓰게 한 곳에서 다듬는다
  const trimmedSearch = search.trim();

  /**
   * 검색·정렬을 클라이언트에서 적용한 결과 목록.
   * - 검색은 이름·설명 부분일치 (대소문자 무시).
   * - 정렬은 SORT_OPTIONS 정의에 따른 안정적 비교 (이름은 localeCompare로 한국어 자모 정렬).
   * - useMemo: categories/검색어/정렬키 변경 시에만 재계산하여 행 렌더 비용 최소화.
   */
  const visibleCategories = useMemo(() => {
    if (!categories) return [];
    const keyword = trimmedSearch.toLowerCase();
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
  }, [categories, trimmedSearch, sortKey]);

  // 검색이 적용된 상태에서 결과가 0건일 때만 별도 안내를 노출 (전체 빈 상태와 구분)
  const isFiltering = trimmedSearch.length > 0;
  const hasResults = visibleCategories.length > 0;
  // 로딩이 끝났는데 보일 행이 없을 때만 개수 낭독을 멈춘다 — 로딩 중(스켈레톤)에는 빈 상태 안내가 없으므로 (WD-815)
  const countSilenced = !isLoading && !hasResults;
  const totalCount = categories?.length ?? 0;
  const createCategory = useCreateCategory();
  const updateCategory = useUpdateCategory(editingCategory?.id || 0);
  const deleteCategory = useDeleteCategory();
  // 쓰기 진입점 노출 — 서버 DatasetCategoryController 가 생성·수정은 dataset:write, 삭제는 dataset:delete 를 요구한다(WD-144).
  // 조회 전용 사용자가 누르고 나서야 403 을 받지 않게 같은 코드로 나눠 숨긴다. 권한 조회 중에는 둘 다 false 라 그리지 않는다.
  const { canWriteDataset: canWriteCategory, canDeleteDataset: canDeleteCategory } = useDatasetPermissions();
  // 편집·삭제 둘 다 없으면 빈 칸만 남는 「작업」 열을 통째로 뺀다(데이터셋 필드 탭 WD-139 와 같다)
  const showActions = canWriteCategory || canDeleteCategory;

  // mode: 'onChange' — 입력 즉시 isValid 상태가 반영되어 저장 버튼 비활성화 동작에 사용
  const createForm = useForm<CategoryFormData>({
    resolver: zodResolver(categorySchema),
    mode: 'onChange',
  });

  // (WD-809) 생성 버튼이 꺼진 이유 안내 — 이름이 (공백 제외) 비어 있고 아직 오류 문구가 없을 때만 보인다.
  // 오류 문구와 겹치지 않게 하고, #49 의 비활성 동작은 그대로 두면서 이유를 문구로 알린다.
  // watch() 대신 useWatch 를 쓰고(React Compiler 린트 경고 회피) compute 로 「비었는가」만 구독해,
  // 글자마다가 아니라 빈 상태가 바뀔 때만 다시 그린다(WD-812 와 같은 방식).
  const createNameEmpty = useWatch({
    control: createForm.control,
    name: 'name',
    compute: (v) => !(v ?? '').trim(),
  });
  const showCreateNameHint = createNameEmpty && !createForm.formState.errors.name;

  // (WD-905 리뷰 후속) 409 로 붙인 서버 이름 오류가 남아 있는가.
  // resolver 폼에서 setError 가 내린 isValid=false 는 다음 스키마 검사(다른 칸 입력)에서 true 로 돌아오지만,
  // 이름 칸 오류는 이름을 고칠 때만 지워진다. 그래서 설명만 고쳐도 버튼이 다시 켜져 같은 이름으로 또 보내게 된다 —
  // 서버 이름 오류가 남아 있는 동안은 버튼을 끈다(이름을 고치면 onChange 재검증이 오류를 지워 다시 켜진다).
  const createNameServerError = createForm.formState.errors.name?.type === 'server';

  // mode: 'onChange' — 수정 폼도 동일하게 실시간 유효성 반영
  const editForm = useForm<CategoryFormData>({
    resolver: zodResolver(categorySchema),
    mode: 'onChange',
  });
  // 수정 폼도 같은 이유로 서버 이름 오류가 남아 있으면 「수정」을 끈다 (WD-905 리뷰 후속)
  const editNameServerError = editForm.formState.errors.name?.type === 'server';

  /**
   * 카테고리 생성 핸들러
   * - 409 Conflict(중복 이름) 오류 시 이름 칸 인라인 오류로 안내한다.
   * - 그 외 오류는 범용 에러 핸들러로 처리한다.
   */
  const handleCreate = async (data: CategoryFormData) => {
    try {
      await createCategory.mutateAsync(data);
      toast.success('카테고리가 생성되었습니다.');
      setIsCreateOpen(false);
      createForm.reset();
    } catch (error) {
      if (isConflictError(error) && isSameSubmittedName(createForm.getValues('name'), data.name)) {
        // 토스트 대신 이름 칸 인라인 오류로 알리고 칸에 포커스한다(재입력 유도)
        createForm.setError('name', { type: 'server', message: DUPLICATE_NAME_MESSAGE }, { shouldFocus: true });
      } else {
        handleApiError(error, '카테고리 생성에 실패했습니다.');
      }
    }
  };

  /**
   * 카테고리 수정 핸들러
   * - 409 Conflict(중복 이름) 오류 시 이름 칸 인라인 오류로 안내한다.
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
      if (isConflictError(error) && isSameSubmittedName(editForm.getValues('name'), data.name)) {
        // 토스트 대신 이름 칸 인라인 오류로 알리고 칸에 포커스한다(재입력 유도)
        editForm.setError('name', { type: 'server', message: DUPLICATE_NAME_MESSAGE }, { shouldFocus: true });
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

  // WD-904: 헤더 버튼과 빈 목록 CTA 가 같은 동작(폼 초기화 후 만들기 창 열기)을 하도록 한 함수로 묶는다
  const openCreateDialog = () => {
    createForm.reset();
    setIsCreateOpen(true);
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <PageHeading>카테고리 관리</PageHeading>
        {canWriteCategory && (
          <Button onClick={openCreateDialog}>
            <Plus className="h-4 w-4" />
            새 카테고리
          </Button>
        )}
      </div>

      {/* 검색·정렬 툴바 — 클라이언트 사이드 필터/정렬 (백엔드 호출 없음) */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        {/* WD-815: 0건 안내(TableEmptyRow)와 개수 영역이 라이브 리전이라, 글자마다 다시 읽히지 않게 입력이 멈춘 뒤 거른다 */}
        <SearchInput
          placeholder="이름 또는 설명으로 검색..."
          aria-label="카테고리 검색"
          debounceMs={300}
          value={search}
          onChange={setSearch}
        />
        <div className="flex items-center gap-2">
          {/* 0건이면 표 안 status 가 안내를 읽으므로 개수는 aria-hidden 노드로 바꿔 두 번 읽히지 않게 한다(이 화면만 개수 라이브 리전이 있다).
              aria-live 값을 껐다 켜면 켜지는 렌더의 변화가 빠질 수 있어 영역은 늘 polite 로 두고 key 로 노드를 갈아 끼운다 (WD-815) */}
          <span className="text-sm text-muted-foreground" aria-live="polite">
            <span key={countSilenced ? 'silenced' : 'announced'} aria-hidden={countSilenced ? true : undefined}>
              {isFiltering ? `${visibleCategories.length} / ${totalCount}` : `${totalCount}개`}
            </span>
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

      {/* WD-634: 긴 값 상한 = (표 폭 100cqw − --k) × 몫. --k = 짧은 칸 선언 폭(작업) + 긴 값 칸 여백
          WD-650: 작업 열은 쓰기·삭제 권한이 있을 때만 그리므로, 없을 때 그 폭(7.5rem)을 빼면 긴 값이 쓸 폭이 그만큼 줄어 필요 이상으로 잘린다.
          Tailwind 는 소스의 문자열 그대로만 클래스를 만들므로 두 값을 온전한 클래스 문자열로 둔다. */}
      <div
        className={`@container ${showActions ? '[--k:calc(7.5rem_+_2rem)]' : '[--k:2rem]'} rounded-md border`}
      >
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>설명</TableHead>
              {showActions && <TableHead className="w-[7.5rem]">작업</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={showActions ? 3 : 2} rows={3} />
            ) : hasResults ? (
              visibleCategories.map((category) => (
                <TableRow key={category.id}>
                  {/* WD-559·WD-634: 긴 값은 표 컨테이너 비례 상한(cqw)으로 말줄임 — 이유는 TruncatedText 설명 */}
                  <TableCell className="font-medium">
                    <TruncatedText value={category.name} className="max-w-[min(20rem,calc((100cqw_-_var(--k))_*_0.32))]" />
                  </TableCell>
                  <TableCell>
                    <TruncatedText value={category.description} className="max-w-[min(40rem,calc((100cqw_-_var(--k))_*_0.68))]" />
                  </TableCell>
                  {showActions && (
                    <TableCell>
                      <div className="flex gap-2">
                        {/* 편집 버튼(PUT, dataset:write) — 아이콘만 있으므로 스크린 리더를 위해 aria-label 필수 */}
                        {canWriteCategory && (
                          <Button
                            variant="outline"
                            size="sm"
                            aria-label={`${category.name} 편집`}
                            onClick={() => openEditDialog(category)}
                          >
                            <Pencil className="h-4 w-4" />
                          </Button>
                        )}
                        {canDeleteCategory && (
                          <DeleteConfirmDialog
                            entityName="카테고리"
                            itemName={category.name}
                            onConfirm={() => handleDelete(category.id, category.name)}
                            trigger={
                              /* 삭제 버튼(DELETE, dataset:delete) — 아이콘만 있으므로 스크린 리더를 위해 aria-label 필수 */
                              <Button variant="outline" size="sm" aria-label={`${category.name} 삭제`}>
                                <Trash2 className="h-4 w-4" />
                              </Button>
                            }
                          />
                        )}
                      </div>
                    </TableCell>
                  )}
                </TableRow>
              ))
            ) : (
              <TableEmptyRow
                colSpan={showActions ? 3 : 2}
                // WD-904: 다른 목록과 같은 공용 빈 상태 — 검색 중엔 다듬은 검색어 문구·「검색 초기화」(공백만이면 검색 아님),
                // 실제 0건엔 message 와 쓰기 권한(WD-144)일 때만 만들기 CTA. 검색어 문구가 바뀌면 다시 낭독되므로 announceKey 는 두지 않는다.
                message="카테고리가 없습니다."
                searchKeyword={isFiltering ? trimmedSearch : undefined}
                onResetSearch={isFiltering ? () => setSearch('') : undefined}
                emptyAction={
                  canWriteCategory ? (
                    <Button size="sm" variant="outline" onClick={openCreateDialog}>
                      <Plus className="h-4 w-4" />
                      새 카테고리 만들기
                    </Button>
                  ) : undefined
                }
              />
            )}
          </TableBody>
        </Table>
      </div>

      {/* Create Dialog */}
      <Dialog open={isCreateOpen} onOpenChange={(open) => { if (!open) setIsCreateOpen(false); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>새 카테고리</DialogTitle>
            <DialogDescription className="sr-only">새 데이터셋 카테고리를 생성합니다.</DialogDescription>
          </DialogHeader>
          <form onSubmit={createForm.handleSubmit(handleCreate)} className="space-y-4">
            <FormField
              label="이름"
              htmlFor="create-name"
              required
              hint={showCreateNameHint ? '이름을 입력하면 만들 수 있습니다.' : undefined}
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
              {/* isValid: 필수 필드(이름)가 비어 있으면 버튼 비활성화(#49 유지). 이유는 이름 칸의 안내 문구로 보인다 (WD-809)
                  서버 이름 중복 오류가 남아 있어도 끈다 — 이름을 고쳐야 다시 보낼 수 있다 (WD-905) */}
              <Button
                type="submit"
                disabled={createCategory.isPending || !createForm.formState.isValid || createNameServerError}
              >
                만들기
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
              required
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
              {/* isValid: 필수 필드(이름)가 비어 있으면 수정 버튼 비활성화. 서버 이름 중복 오류가 남아 있어도 끈다 (WD-905) */}
              <Button
                type="submit"
                disabled={updateCategory.isPending || !editForm.formState.isValid || editNameServerError}
              >
                수정
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
