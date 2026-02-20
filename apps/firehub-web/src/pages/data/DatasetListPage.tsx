import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useDatasets, useCategories, useDeleteDataset, useToggleFavorite } from '../../hooks/queries/useDatasets';
import { useRecentDatasets } from '../../hooks/useRecentDatasets';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Badge } from '../../components/ui/badge';
import { Skeleton } from '../../components/ui/skeleton';
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
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '../../components/ui/alert-dialog';
import { Plus, Trash2, ChevronLeft, ChevronRight, Search, History, Star, Eye, BarChart3, Download } from 'lucide-react';
import { toast } from 'sonner';
import type { ErrorResponse } from '../../types/auth';
import axios from 'axios';
import { formatDateShort } from '../../lib/formatters';
import { DatasetPreviewSheet } from './components/DatasetPreviewSheet';
import { datasetsApi } from '../../api/datasets';

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

export default function DatasetListPage() {
  const navigate = useNavigate();
  const [categoryId, setCategoryId] = useState<number | undefined>();
  const [datasetType, setDatasetType] = useState<string>('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [favoriteOnly, setFavoriteOnly] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string>('');
  const size = 10;

  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewDatasetId, setPreviewDatasetId] = useState<number | null>(null);
  const [previewDatasetName, setPreviewDatasetName] = useState('');

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
  const datasets = datasetsData?.content || [];
  const totalPages = datasetsData?.totalPages || 0;

  const noFiltersActive = !search && !categoryId && !datasetType && !favoriteOnly && !statusFilter;

  const handleDelete = async (id: number, name: string) => {
    try {
      await deleteDataset.mutateAsync(id);
      toast.success(`데이터셋 "${name}"이(가) 삭제되었습니다.`);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '데이터셋 삭제에 실패했습니다.');
      } else {
        toast.error('데이터셋 삭제에 실패했습니다.');
      }
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

  const handleExport = async (e: React.MouseEvent, id: number, name: string) => {
    e.stopPropagation();
    try {
      const response = await datasetsApi.getDatasetData(id, { size: 10000, page: 0 });
      const { columns, rows } = response.data;
      const header = columns.map(c => c.displayName || c.columnName).join(',');
      const body = rows.map(row =>
        columns.map(c => {
          const val = row[c.columnName];
          if (val == null) return '';
          const str = String(val);
          return str.includes(',') || str.includes('"') || str.includes('\n')
            ? `"${str.replace(/"/g, '""')}"` : str;
        }).join(',')
      ).join('\n');
      const csv = `${header}\n${body}`;
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${name}.csv`;
      a.click();
      URL.revokeObjectURL(url);
      toast.success(`"${name}" CSV 다운로드 완료`);
    } catch {
      toast.error('CSV 내보내기에 실패했습니다.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">데이터셋 관리</h1>
        <Button asChild>
          <Link to="/data/datasets/new">
            <Plus className="mr-2 h-4 w-4" />
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
          <div className="relative flex-1 max-w-sm">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="데이터셋 검색..."
              value={search}
              onChange={(e) => {
                setSearch(e.target.value);
                setPage(0);
              }}
              className="pl-9"
            />
          </div>
          <Select
            value={datasetType || '__all__'}
            onValueChange={(value) => {
              setDatasetType(value === '__all__' ? '' : value);
              setPage(0);
            }}
          >
            <SelectTrigger className="w-[160px]">
              <SelectValue placeholder="전체 유형" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__all__">전체 유형</SelectItem>
              <SelectItem value="SOURCE">원본</SelectItem>
              <SelectItem value="DERIVED">파생</SelectItem>
            </SelectContent>
          </Select>

          {/* Status filter */}
          <Select
            value={statusFilter || '__all__'}
            onValueChange={(value) => {
              setStatusFilter(value === '__all__' ? '' : value);
              setPage(0);
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
              setFavoriteOnly((v) => !v);
              setPage(0);
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
              setCategoryId(undefined);
              setPage(0);
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
                setCategoryId(categoryId === cat.id ? undefined : cat.id);
                setPage(0);
              }}
            >
              {cat.name}
            </Badge>
          ))}
        </div>
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-[32px]" />
              <TableHead>이름</TableHead>
              <TableHead>태그</TableHead>
              <TableHead>유형</TableHead>
              <TableHead>카테고리</TableHead>
              <TableHead>생성일</TableHead>
              <TableHead className="w-[60px]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <TableRow key={i}>
                  <TableCell><Skeleton className="h-4 w-4" /></TableCell>
                  <TableCell><Skeleton className="h-4 w-32" /></TableCell>
                  <TableCell><Skeleton className="h-4 w-24" /></TableCell>
                  <TableCell><Skeleton className="h-5 w-16" /></TableCell>
                  <TableCell><Skeleton className="h-4 w-20" /></TableCell>
                  <TableCell><Skeleton className="h-4 w-24" /></TableCell>
                  <TableCell><Skeleton className="h-8 w-8" /></TableCell>
                </TableRow>
              ))
            ) : datasets.length > 0 ? (
              datasets.map((dataset) => (
                <TableRow
                  key={dataset.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors group relative"
                  onClick={() => navigate(`/data/datasets/${dataset.id}`)}
                >
                  {/* Favorite star */}
                  <TableCell onClick={(e) => e.stopPropagation()} className="pr-0">
                    <button
                      className="p-1 rounded hover:bg-muted transition-colors"
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
                        <Badge className="bg-green-100 text-green-800 text-xs border-0">
                          ✓ Certified
                        </Badge>
                      )}
                      {dataset.status === 'DEPRECATED' && (
                        <Badge className="bg-red-100 text-red-800 text-xs border-0">
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
                    <Badge variant={dataset.datasetType === 'SOURCE' ? 'default' : 'secondary'}>
                      {dataset.datasetType === 'SOURCE' ? '원본' : '파생'}
                    </Badge>
                  </TableCell>
                  <TableCell>{dataset.category?.name || '-'}</TableCell>
                  <TableCell>{formatDateShort(dataset.createdAt)}</TableCell>
                  <TableCell className="relative">
                    {/* Hover action buttons */}
                    <div className="absolute right-10 top-1/2 -translate-y-1/2 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity bg-background/90 backdrop-blur-sm rounded-md p-1 shadow-sm z-10">
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-7 w-7 p-0"
                        title="미리보기"
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
                        title="CSV 내보내기"
                        onClick={(e) => handleExport(e, dataset.id, dataset.name)}
                      >
                        <Download size={14} />
                      </Button>
                    </div>

                    {/* Delete button */}
                    <AlertDialog>
                      <AlertDialogTrigger asChild>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={(e) => e.stopPropagation()}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </AlertDialogTrigger>
                      <AlertDialogContent>
                        <AlertDialogHeader>
                          <AlertDialogTitle>데이터셋 삭제</AlertDialogTitle>
                          <AlertDialogDescription>
                            &quot;{dataset.name}&quot; 데이터셋을 정말 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.
                          </AlertDialogDescription>
                        </AlertDialogHeader>
                        <AlertDialogFooter>
                          <AlertDialogCancel>취소</AlertDialogCancel>
                          <AlertDialogAction onClick={() => handleDelete(dataset.id, dataset.name)}>
                            삭제
                          </AlertDialogAction>
                        </AlertDialogFooter>
                      </AlertDialogContent>
                    </AlertDialog>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={7} className="text-center text-muted-foreground">
                  데이터셋이 없습니다.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>

      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
          >
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <span className="text-sm">
            {page + 1} / {totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
            disabled={page >= totalPages - 1}
          >
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      )}

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
    </div>
  );
}
