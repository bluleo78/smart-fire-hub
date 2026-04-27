import { BarChart3, Download,Eye, History, Plus, Star, Trash2 } from 'lucide-react';
import { useCallback, useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { DeleteConfirmDialog } from '../../components/ui/delete-confirm-dialog';
import { SearchInput } from '../../components/ui/search-input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { SimplePagination } from '../../components/ui/simple-pagination';
import {
  SortableHeader,
  type SortDirection,
  Table,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { TableEmptyRow } from '../../components/ui/table-empty';
import { TableSkeletonRows } from '../../components/ui/table-skeleton';
import { useCategories, useDatasets, useDeleteDataset, useToggleFavorite } from '../../hooks/queries/useDatasets';
import { useRecentDatasets } from '../../hooks/useRecentDatasets';
import { handleApiError } from '../../lib/api-error';
import { formatDateShort } from '../../lib/formatters';
import { iGa } from '../../lib/utils';
import { DatasetPreviewSheet } from './components/DatasetPreviewSheet';
import { ExportDialog } from './components/ExportDialog';

function getRelativeTime(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return '방금 전';
  if (mins < 60) return `${mins}분 전`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}일 전`;
  const months = Math.floor(days / 30);
  return `${months}개월 전`;
}

type SortKey = 'name' | 'createdAt';

/**
 * URL 쿼리 파라미터를 단일 진실 소스(single source of truth)로 사용한다 (refs #94).
 * - 새로고침/공유 시 필터·검색·정렬 상태가 복원된다.
 * - 빈 값(default)일 때는 URL에서 제거하여 깔끔한 URL 유지.
 * - history 오염 방지를 위해 replace=true 로 갱신.
 */
export default function DatasetListPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  // URL → 상태 파싱 (매 렌더 시 동기화)
  const categoryIdParam = searchParams.get('categoryId');
  const categoryId = categoryIdParam ? Number(categoryIdParam) : undefined;
  const datasetType = searchParams.get('datasetType') || '';
  const search = searchParams.get('q') || '';
  const page = Number(searchParams.get('page') || '0');
  const favoriteOnly = searchParams.get('favorite') === 'true';
  const statusFilter = searchParams.get('status') || '';
  /** 페이지당 표시 건수: 사용자가 selector 로 변경 가능 (기본 10) */
  const size = Number(searchParams.get('size') || '10');
  const sortKeyParam = searchParams.get('sort');
  const sortKey: SortKey | null =
    sortKeyParam === 'name' || sortKeyParam === 'createdAt' ? sortKeyParam : null;
  const sortOrderParam = searchParams.get('order');
  const sortOrder: SortDirection =
    sortOrderParam === 'asc' || sortOrderParam === 'desc' ? sortOrderParam : 'none';

  /**
   * URL 파라미터 패치 헬퍼 — 빈 값/기본값은 키 자체를 제거하여 URL 청결성 유지.
   * value 가 null/undefined/'' 이면 키 삭제, 그렇지 않으면 set.
   */
  const patchParams = useCallback(
    (patch: Record<string, string | number | boolean | null | undefined>) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          for (const [k, v] of Object.entries(patch)) {
            if (v === null || v === undefined || v === '' || v === false) {
              next.delete(k);
            } else {
              next.set(k, String(v));
            }
          }
          return next;
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );

  const toggleSort = (key: SortKey) => {
    if (sortKey !== key) {
      patchParams({ sort: key, order: 'asc' });
      return;
    }
    // 같은 컬럼 재클릭 — asc → desc → none(원본 순서) 순환
    if (sortOrder === 'asc') patchParams({ sort: key, order: 'desc' });
    else if (sortOrder === 'desc') patchParams({ sort: null, order: null });
    else patchParams({ sort: key, order: 'asc' });
  };

  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewDatasetId, setPreviewDatasetId] = useState<number | null>(null);
  const [previewDatasetName, setPreviewDatasetName] = useState('');

  // 내보내기 다이얼로그 상태 (refs #95) — 호버 액션 '내보내기' 버튼에서 트리거
  const [exportOpen, setExportOpen] = useState(false);
  const [exportDatasetId, setExportDatasetId] = useState<number | null>(null);
  const [exportDatasetName, setExportDatasetName] = useState('');

  const { data: categoriesData } = useCategories();
  const { data: datasetsData, isLoading } = useDatasets({
    categoryId,
    datasetType: datasetType || undefined,
    search: search || undefined,
    page,
    size,
    favoriteOnly: favoriteOnly || undefined,
    status: statusFilter || undefined,
  });
  const deleteDataset = useDeleteDataset();
  const toggleFavorite = useToggleFavorite();
  const { recents } = useRecentDatasets();

  const categories = categoriesData || [];
  const rawDatasets = datasetsData?.content || [];
  /**
   * 정렬이 적용된 데이터셋 목록 (현재 페이지 내).
   * - 원본 배열을 변형하지 않기 위해 slice() 후 정렬.
   * - 이름은 한국어 자모 정렬을 위해 localeCompare('ko'), 날짜는 ISO 문자열 비교.
   */
  const datasets = useMemo(() => {
    if (!sortKey || sortOrder === 'none') return rawDatasets;
    const sorted = rawDatasets.slice();
    sorted.sort((a, b) => {
      let cmp = 0;
      if (sortKey === 'name') cmp = a.name.localeCompare(b.name, 'ko');
      else if (sortKey === 'createdAt') cmp = a.createdAt.localeCompare(b.createdAt);
      return sortOrder === 'asc' ? cmp : -cmp;
    });
    return sorted;
  }, [rawDatasets, sortKey, sortOrder]);
  const totalPages = datasetsData?.totalPages || 0;
  const totalElements = datasetsData?.totalElements;

  const noFiltersActive = !search && !categoryId && !datasetType && !favoriteOnly && !statusFilter;

  const handleDelete = async (id: number, name: string) => {
    try {
      await deleteDataset.mutateAsync(id);
      toast.success(`데이터셋 "${name}"${iGa(name)} 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, '데이터셋 삭제에 실패했습니다.');
    }
  };

  const handleToggleFavorite = async (e: React.MouseEvent, id: number, name: string, isFavorite: boolean) => {
    e.stopPropagation();
    try {
      await toggleFavorite.mutateAsync(id);
      toast.success(isFavorite ? `"${name}" 즐겨찾기 해제` : `"${name}" 즐겨찾기 추가`);
    } catch {
      toast.error('즐겨찾기 변경에 실패했습니다.');
    }
  };


  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">데이터셋 관리</h1>
        <Button asChild>
          <Link to="/data/datasets/new">
            <Plus className="h-4 w-4" />
            데이터셋 추가
          </Link>
        </Button>
      </div>

      {/* Recent Datasets */}
      {noFiltersActive && recents.length > 0 && (
        <div className="space-y-2">
          <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
            <History className="h-4 w-4" />
            <span>최근 접근</span>
          </div>
          <div className="flex overflow-x-auto gap-3 pb-2">
            {recents.map((recent) => (
              <div
                key={recent.id}
                className="p-3 min-w-[200px] max-w-[250px] cursor-pointer hover:bg-accent/50 transition-colors flex-shrink-0 rounded-lg border bg-card shadow-sm"
                onClick={() => navigate(`/data/datasets/${recent.id}`)}
              >
                <p className="font-medium truncate">{recent.name}</p>
                <p className="text-xs text-muted-foreground font-mono truncate mt-0.5">
                  {recent.tableName}
                </p>
                <p className="text-xs text-muted-foreground mt-1">
                  {getRelativeTime(recent.accessedAt)}
                </p>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Filters */}
      <div className="space-y-3">
        <div className="flex items-center gap-3 flex-wrap">
          <SearchInput
            placeholder="데이터셋 검색..."
            value={search}
            onChange={(value) => {
              patchParams({ q: value, page: null });
            }}
          />
          <Select
            value={datasetType || '__all__'}
            onValueChange={(value) => {
              patchParams({ datasetType: value === '__all__' ? null : value, page: null });
            }}
          >
            <SelectTrigger className="w-[160px]">
              <SelectValue placeholder="전체 유형" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__all__">전체 유형</SelectItem>
              <SelectItem value="SOURCE">원본</SelectItem>
              <SelectItem value="DERIVED">파생</SelectItem>
              <SelectItem value="TEMP">임시</SelectItem>
            </SelectContent>
          </Select>

          {/* Status filter */}
          <Select
            value={statusFilter || '__all__'}
            onValueChange={(value) => {
              patchParams({ status: value === '__all__' ? null : value, page: null });
            }}
          >
            <SelectTrigger className="w-[140px]">
              <SelectValue placeholder="전체 상태" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__all__">전체 상태</SelectItem>
              <SelectItem value="CERTIFIED">인증됨</SelectItem>
              <SelectItem value="DEPRECATED">사용 중단</SelectItem>
              <SelectItem value="NONE">상태 없음</SelectItem>
            </SelectContent>
          </Select>

          {/* Favorite toggle */}
          <Button
            variant={favoriteOnly ? 'default' : 'outline'}
            size="sm"
            onClick={() => {
              patchParams({ favorite: !favoriteOnly ? 'true' : null, page: null });
            }}
            className="gap-1.5"
          >
            <Star className={`h-4 w-4 ${favoriteOnly ? 'fill-current' : ''}`} />
            즐겨찾기
          </Button>
        </div>

        {/* Category Chip Filter */}
        <div className="flex gap-2 flex-wrap">
          <Badge
            variant={categoryId === undefined ? 'default' : 'outline'}
            className="cursor-pointer hover:opacity-80 transition-opacity"
            onClick={() => {
              patchParams({ categoryId: null, page: null });
            }}
          >
            전체
          </Badge>
          {categories.map((cat) => (
            <Badge
              key={cat.id}
              variant={categoryId === cat.id ? 'default' : 'outline'}
              className="cursor-pointer hover:opacity-80 transition-opacity"
              onClick={() => {
                patchParams({ categoryId: categoryId === cat.id ? null : cat.id, page: null });
              }}
            >
              {cat.name}
            </Badge>
          ))}
        </div>
      </div>

      <div className="rounded-md border">
        <Table aria-label="데이터셋 목록">
          <TableHeader>
            <TableRow>
              <TableHead className="w-[32px]" />
              <SortableHeader
                direction={sortKey === 'name' ? sortOrder : 'none'}
                onSort={() => toggleSort('name')}
              >
                이름
              </SortableHeader>
              <TableHead>태그</TableHead>
              <TableHead>유형</TableHead>
              <TableHead>카테고리</TableHead>
              <SortableHeader
                direction={sortKey === 'createdAt' ? sortOrder : 'none'}
                onSort={() => toggleSort('createdAt')}
              >
                생성일
              </SortableHeader>
              <TableHead className="w-[60px]" />
            </TableRow>
          </TableHeader>
          <tbody>
            {isLoading ? (
              <TableSkeletonRows columns={7} rows={5} />
            ) : datasets.length > 0 ? (
              datasets.map((dataset) => (
                <TableRow
                  key={dataset.id}
                  className="row-hover cursor-pointer hover:bg-muted/50 transition-colors group relative"
                  onClick={() => navigate(`/data/datasets/${dataset.id}`)}
                >
                  {/* Favorite star */}
                  <TableCell onClick={(e) => e.stopPropagation()} className="pr-0">
                    <button
                      className="p-1 rounded hover:bg-muted transition-colors"
                      aria-label={dataset.isFavorite ? '즐겨찾기 해제' : '즐겨찾기 추가'}
                      onClick={(e) => handleToggleFavorite(e, dataset.id, dataset.name, dataset.isFavorite)}
                    >
                      <Star
                        className={`h-4 w-4 transition-colors ${
                          dataset.isFavorite
                            ? 'fill-yellow-400 text-yellow-400'
                            : 'text-muted-foreground hover:text-yellow-400'
                        }`}
                      />
                    </button>
                  </TableCell>

                  {/* Name + status badge */}
                  <TableCell className="font-medium">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span>{dataset.name}</span>
                      {dataset.status === 'CERTIFIED' && (
                        <Badge variant="success" className="text-xs">
                          ✓ Certified
                        </Badge>
                      )}
                      {dataset.status === 'DEPRECATED' && (
                        <Badge variant="destructive" className="text-xs">
                          Deprecated
                        </Badge>
                      )}
                    </div>
                  </TableCell>

                  {/* Tags column */}
                  <TableCell>
                    <div className="flex items-center gap-1 flex-wrap">
                      {(dataset.tags || []).slice(0, 3).map((tag) => (
                        <Badge key={tag} variant="secondary" className="text-xs">
                          {tag}
                        </Badge>
                      ))}
                      {(dataset.tags || []).length > 3 && (
                        <Badge variant="secondary" className="text-xs">
                          +{dataset.tags.length - 3}
                        </Badge>
                      )}
                    </div>
                  </TableCell>

                  <TableCell>
                    {dataset.datasetType === 'SOURCE' && (
                      <Badge className="bg-primary/10 text-primary border-0">원본</Badge>
                    )}
                    {dataset.datasetType === 'DERIVED' && (
                      <Badge className="bg-success/10 text-success border-0">파생</Badge>
                    )}
                    {dataset.datasetType === 'TEMP' && (
                      <Badge className="bg-muted text-muted-foreground border-0">임시</Badge>
                    )}
                  </TableCell>
                  <TableCell>{dataset.category?.name || '-'}</TableCell>
                  <TableCell className="tabular-nums">{formatDateShort(dataset.createdAt)}</TableCell>
                  <TableCell className="relative">
                    {/* Hover action buttons */}
                    <div className="absolute right-10 top-1/2 -translate-y-1/2 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity bg-background/90 backdrop-blur-sm rounded-md p-1 shadow-sm z-10">
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-7 w-7 p-0"
                        title="미리보기"
                        aria-label="미리보기"
                        onClick={(e) => {
                          e.stopPropagation();
                          setPreviewDatasetId(dataset.id);
                          setPreviewDatasetName(dataset.name);
                          setPreviewOpen(true);
                        }}
                      >
                        <Eye size={14} />
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-7 w-7 p-0"
                        title="프로파일"
                        aria-label="프로파일"
                        onClick={(e) => {
                          e.stopPropagation();
                          navigate(`/data/datasets/${dataset.id}?tab=data`);
                        }}
                      >
                        <BarChart3 size={14} />
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-7 w-7 p-0"
                        title="내보내기"
                        aria-label="내보내기"
                        onClick={(e) => {
                          // refs #95: navigate 가 아닌 ExportDialog 오픈 — 라벨/아이콘과 동작 일치
                          e.stopPropagation();
                          setExportDatasetId(dataset.id);
                          setExportDatasetName(dataset.name);
                          setExportOpen(true);
                        }}
                      >
                        <Download size={14} />
                      </Button>
                    </div>

                    {/* Delete button */}
                    <DeleteConfirmDialog
                      entityName="데이터셋"
                      itemName={dataset.name}
                      onConfirm={() => handleDelete(dataset.id, dataset.name)}
                      trigger={
                        <Button
                          variant="ghost"
                          size="sm"
                          aria-label="삭제"
                          onClick={(e) => e.stopPropagation()}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      }
                    />
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow
                colSpan={7}
                message="데이터셋이 없습니다."
                searchKeyword={search || undefined}
                onResetSearch={search ? () => { patchParams({ q: null, page: null }); } : undefined}
                emptyAction={
                  noFiltersActive ? (
                    <Button asChild size="sm" variant="outline">
                      <Link to="/data/datasets/new">
                        <Plus className="h-4 w-4" />
                        새 데이터셋 만들기
                      </Link>
                    </Button>
                  ) : undefined
                }
              />
            )}
          </tbody>
        </Table>
      </div>

      <SimplePagination
        page={page}
        totalPages={totalPages}
        onPageChange={(p) => patchParams({ page: p === 0 ? null : p })}
        totalElements={totalElements}
        pageSize={size}
        onPageSizeChange={(newSize) => {
          patchParams({ size: newSize === 10 ? null : newSize, page: null });
        }}
      />

      {/* Dataset Preview Dialog */}
      {previewDatasetId !== null && (
        <DatasetPreviewSheet
          datasetId={previewDatasetId}
          datasetName={previewDatasetName}
          open={previewOpen}
          onOpenChange={(open) => {
            setPreviewOpen(open);
            if (!open) setPreviewDatasetId(null);
          }}
        />
      )}

      {/* Export Dialog (refs #95) — 호버 액션 '내보내기' 버튼에서 트리거 */}
      {exportDatasetId !== null && (
        <ExportDialog
          datasetId={exportDatasetId}
          datasetName={exportDatasetName}
          open={exportOpen}
          onOpenChange={(open) => {
            setExportOpen(open);
            if (!open) setExportDatasetId(null);
          }}
        />
      )}
    </div>
  );
}
