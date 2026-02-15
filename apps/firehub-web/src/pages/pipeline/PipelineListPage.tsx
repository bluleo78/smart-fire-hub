import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { usePipelines, useDeletePipeline } from '../../hooks/queries/usePipelines';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Skeleton } from '../../components/ui/skeleton';
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
import { Plus, Trash2, ChevronLeft, ChevronRight } from 'lucide-react';
import { toast } from 'sonner';
import type { ErrorResponse } from '../../types/auth';
import axios from 'axios';
import { formatDateShort } from '../../lib/formatters';

export default function PipelineListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const size = 10;

  const { data: pipelinesData, isLoading } = usePipelines({ page, size });
  const deletePipeline = useDeletePipeline();

  const pipelines = pipelinesData?.content || [];
  const totalPages = pipelinesData?.totalPages || 0;

  const handleDelete = async (id: number, name: string) => {
    try {
      await deletePipeline.mutateAsync(id);
      toast.success(`파이프라인 "${name}"이(가) 삭제되었습니다.`);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '파이프라인 삭제에 실패했습니다.');
      } else {
        toast.error('파이프라인 삭제에 실패했습니다.');
      }
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">파이프라인 관리</h1>
        <Button asChild>
          <Link to="/pipelines/new">
            <Plus className="mr-2 h-4 w-4" />
            파이프라인 추가
          </Link>
        </Button>
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>상태</TableHead>
              <TableHead>스텝 수</TableHead>
              <TableHead>생성자</TableHead>
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
                  <TableCell><Skeleton className="h-4 w-12" /></TableCell>
                  <TableCell><Skeleton className="h-4 w-20" /></TableCell>
                  <TableCell><Skeleton className="h-4 w-24" /></TableCell>
                  <TableCell><Skeleton className="h-8 w-8" /></TableCell>
                </TableRow>
              ))
            ) : pipelines.length > 0 ? (
              pipelines.map((pipeline) => (
                <TableRow
                  key={pipeline.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors"
                  onClick={() => navigate(`/pipelines/${pipeline.id}`)}
                >
                  <TableCell className="font-medium">{pipeline.name}</TableCell>
                  <TableCell>
                    <Badge variant={pipeline.isActive ? 'default' : 'secondary'}>
                      {pipeline.isActive ? '활성' : '비활성'}
                    </Badge>
                  </TableCell>
                  <TableCell>{pipeline.stepCount}</TableCell>
                  <TableCell>{pipeline.createdBy}</TableCell>
                  <TableCell>{formatDateShort(pipeline.createdAt)}</TableCell>
                  <TableCell>
                    <AlertDialog>
                      <AlertDialogTrigger asChild>
                        <Button variant="outline" size="sm" onClick={(e) => e.stopPropagation()}>
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </AlertDialogTrigger>
                      <AlertDialogContent>
                        <AlertDialogHeader>
                          <AlertDialogTitle>파이프라인 삭제</AlertDialogTitle>
                          <AlertDialogDescription>
                            &quot;{pipeline.name}&quot; 파이프라인을 정말 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.
                          </AlertDialogDescription>
                        </AlertDialogHeader>
                        <AlertDialogFooter>
                          <AlertDialogCancel>취소</AlertDialogCancel>
                          <AlertDialogAction onClick={() => handleDelete(pipeline.id, pipeline.name)}>
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
                <TableCell colSpan={6} className="text-center text-muted-foreground">
                  파이프라인이 없습니다.
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
