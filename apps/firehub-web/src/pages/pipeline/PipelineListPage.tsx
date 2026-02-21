import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { usePipelines, useDeletePipeline } from '../../hooks/queries/usePipelines';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import {
  Table,
  TableBody,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { SimplePagination } from '@/components/ui/simple-pagination';
import { Plus, Trash2, Clock } from 'lucide-react';
import { toast } from 'sonner';
import { handleApiError } from '@/lib/api-error';
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
      handleApiError(error, '파이프라인 삭제에 실패했습니다.');
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
                  className="cursor-pointer hover:bg-muted/50 transition-colors"
                  onClick={() => navigate(`/pipelines/${pipeline.id}`)}
                >
                  <td className="p-4 font-medium">{pipeline.name}</td>
                  <td className="p-4">
                    <Badge variant={pipeline.isActive ? 'default' : 'secondary'}>
                      {pipeline.isActive ? '활성' : '비활성'}
                    </Badge>
                  </td>
                  <td className="p-4">{pipeline.stepCount}</td>
                  <td className="p-4">
                    {pipeline.triggerCount > 0 && (
                      <Badge variant="outline" className="text-xs gap-1">
                        <Clock className="h-3 w-3" />
                        {pipeline.triggerCount}
                      </Badge>
                    )}
                  </td>
                  <td className="p-4">{pipeline.createdBy}</td>
                  <td className="p-4">{formatDateShort(pipeline.createdAt)}</td>
                  <td className="p-4">
                    <DeleteConfirmDialog
                      entityName="파이프라인"
                      itemName={pipeline.name}
                      onConfirm={() => handleDelete(pipeline.id, pipeline.name)}
                      trigger={
                        <Button variant="outline" size="sm">
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      }
                    />
                  </td>
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
