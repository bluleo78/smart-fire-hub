import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useDatasets, useCategories, useDeleteDataset } from '../../hooks/queries/useDatasets';
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
import { Plus, Trash2, ChevronLeft, ChevronRight, Search } from 'lucide-react';
import { toast } from 'sonner';
import type { ErrorResponse } from '../../types/auth';
import axios from 'axios';

export function DatasetListPage() {
  const navigate = useNavigate();
  const [categoryId, setCategoryId] = useState<number | undefined>();
  const [datasetType, setDatasetType] = useState<string>('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const size = 10;

  const { data: categoriesData } = useCategories();
  const { data: datasetsData, isLoading } = useDatasets({
    categoryId,
    datasetType: datasetType || undefined,
    search: search || undefined,
    page,
    size,
  });
  const deleteDataset = useDeleteDataset();

  const categories = categoriesData || [];
  const datasets = datasetsData?.content || [];
  const totalPages = datasetsData?.totalPages || 0;

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

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleDateString('ko-KR');
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

      <div className="flex items-center gap-3">
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
          value={categoryId?.toString() || '__all__'}
          onValueChange={(value) => {
            setCategoryId(value === '__all__' ? undefined : Number(value));
            setPage(0);
          }}
        >
          <SelectTrigger className="w-[160px]">
            <SelectValue placeholder="전체 카테고리" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="__all__">전체 카테고리</SelectItem>
            {categories.map((cat) => (
              <SelectItem key={cat.id} value={cat.id.toString()}>
                {cat.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
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
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
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
                  <TableCell><Skeleton className="h-4 w-32" /></TableCell>
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
                  className="cursor-pointer hover:bg-muted/50 transition-colors"
                  onClick={() => navigate(`/data/datasets/${dataset.id}`)}
                >
                  <TableCell className="font-medium">{dataset.name}</TableCell>
                  <TableCell>
                    <Badge variant={dataset.datasetType === 'SOURCE' ? 'default' : 'secondary'}>
                      {dataset.datasetType === 'SOURCE' ? '원본' : '파생'}
                    </Badge>
                  </TableCell>
                  <TableCell>{dataset.category?.name || '-'}</TableCell>
                  <TableCell>{formatDate(dataset.createdAt)}</TableCell>
                  <TableCell>
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
                <TableCell colSpan={5} className="text-center text-muted-foreground">
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
    </div>
  );
}
