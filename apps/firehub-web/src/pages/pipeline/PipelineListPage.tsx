import { Clock,Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { SimplePagination } from '@/components/ui/simple-pagination';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { handleApiError } from '@/lib/api-error';
import { iGa } from '@/lib/utils';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { StatusBadge } from '../../components/ui/status-badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { useDeletePipeline,usePipelines } from '../../hooks/queries/usePipelines';
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
      toast.success(`파이프라인 "${name}"${iGa(name)} 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, '파이프라인 삭제에 실패했습니다.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">파이프라인 관리</h1>
        <Button asChild>
          <Link to="/pipelines/new">
            <Plus className="h-4 w-4" />
            파이프라인 추가
          </Link>
        </Button>
      </div>

      <div className="rounded-md border">
        <Table aria-label="파이프라인 목록">
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>상태</TableHead>
              <TableHead>스텝 수</TableHead>
              <TableHead>트리거</TableHead>
              <TableHead>생성자</TableHead>
              <TableHead>생성일</TableHead>
              <TableHead className="w-[60px]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={7} rows={5} />
            ) : pipelines.length > 0 ? (
              pipelines.map((pipeline) => (
                <TableRow
                  key={pipeline.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors row-hover"
                  onClick={() => navigate(`/pipelines/${pipeline.id}`)}
                >
                  <TableCell className="font-medium">{pipeline.name}</TableCell>
                  <TableCell>
                    <StatusBadge type={pipeline.isActive ? 'active' : 'inactive'}>
                      {pipeline.isActive ? '활성' : '비활성'}
                    </StatusBadge>
                  </TableCell>
                  <TableCell className="tabular-nums">{pipeline.stepCount}</TableCell>
                  <TableCell>
                    {pipeline.triggerCount > 0 && (
                      <Badge variant="outline" className="text-xs gap-1">
                        <Clock className="h-3 w-3" />
                        {pipeline.triggerCount}
                      </Badge>
                    )}
                  </TableCell>
                  <TableCell>{pipeline.createdBy}</TableCell>
                  <TableCell>{formatDateShort(pipeline.createdAt)}</TableCell>
                  <TableCell>
                    <DeleteConfirmDialog
                      entityName="파이프라인"
                      itemName={pipeline.name}
                      onConfirm={() => handleDelete(pipeline.id, pipeline.name)}
                      trigger={
                        <Button variant="outline" size="sm" aria-label="삭제">
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      }
                    />
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={7} message="파이프라인이 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>

      <SimplePagination page={page} totalPages={totalPages} onPageChange={setPage} />
    </div>
  );
}
