import { Clock, Info, Plus, Trash2 } from 'lucide-react';
import { useMemo,useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { SimplePagination } from '@/components/ui/simple-pagination';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { handleApiError, isForbiddenError } from '@/lib/api-error';
import { cn, iGa } from '@/lib/utils';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { StatusBadge } from '../../components/ui/status-badge';
import {
  SortableHeader,
  type SortDirection,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { useDeletePipeline,usePipelines } from '../../hooks/queries/usePipelines';
import { formatDateOnly, formatDateTimeMinute } from '../../lib/formatters';
import { getPageAfterDelete } from '../../lib/pagination';
import { PipelineDeleteConfirmDialog } from './components/PipelineDeleteConfirmDialog';

export default function PipelineListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const size = 10;

  const { data: pipelinesData, isLoading, isError, error } = usePipelines({ page, size });
  // 조회 실패를 빈 목록으로 그리지 않는다 (WD-122) — 403 이면 권한 안내, 그 밖은 불러오기 실패 안내.
  const isForbidden = isError && isForbiddenError(error);
  const deletePipeline = useDeletePipeline();

  /**
   * 클라이언트 사이드 정렬 상태 — 이슈 #80 1차 대응.
   * - 현재 페이지 내 정렬만 수행한다. 백엔드 sort 파라미터 연동은 후속 작업.
   */
  type SortKey = 'name' | 'stepCount' | 'createdAt';
  const [sortKey, setSortKey] = useState<SortKey | null>(null);
  const [sortOrder, setSortOrder] = useState<SortDirection>('none');

  const toggleSort = (key: SortKey) => {
    if (sortKey !== key) {
      setSortKey(key);
      setSortOrder('asc');
      return;
    }
    if (sortOrder === 'asc') setSortOrder('desc');
    else if (sortOrder === 'desc') {
      setSortKey(null);
      setSortOrder('none');
    } else setSortOrder('asc');
  };

  const rawPipelines = pipelinesData?.content || [];
  const totalPages = pipelinesData?.totalPages || 0;

  /**
   * 정렬이 적용된 파이프라인 목록 (현재 페이지 내).
   * - 원본 배열을 변형하지 않기 위해 slice() 후 정렬.
   */
  const pipelines = useMemo(() => {
    if (!sortKey || sortOrder === 'none') return rawPipelines;
    const sorted = rawPipelines.slice();
    sorted.sort((a, b) => {
      let cmp = 0;
      if (sortKey === 'name') cmp = a.name.localeCompare(b.name, 'ko');
      else if (sortKey === 'stepCount') cmp = a.stepCount - b.stepCount;
      else if (sortKey === 'createdAt') cmp = a.createdAt.localeCompare(b.createdAt);
      return sortOrder === 'asc' ? cmp : -cmp;
    });
    return sorted;
  }, [rawPipelines, sortKey, sortOrder]);

  const handleDelete = async (id: number, name: string) => {
    try {
      await deletePipeline.mutateAsync(id);
      // 현재 페이지의 마지막 항목을 삭제한 경우, 페이지를 앞으로 보정해
      // "검색 결과 없음" 오표시(#549)를 방지한다.
      setPage((prev) => getPageAfterDelete(rawPipelines.length, prev));
      toast.success(`파이프라인 "${name}"${iGa(name)} 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, '파이프라인 삭제에 실패했습니다.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">파이프라인 관리</h1>
        {/* 조회 권한이 없으면 만들어도 볼 수 없으니 추가 버튼을 숨긴다 (WD-122). */}
        {!isForbidden && (
          <Button asChild>
            <Link to="/pipelines/new">
              <Plus className="h-4 w-4" />
              파이프라인 추가
            </Link>
          </Button>
        )}
      </div>

      {/* 클라이언트 사이드 정렬 범위 안내 — 정렬 활성 시에만 표시하여 혼동 방지 (이슈 #165) */}
      {sortKey && (
        <div className="flex items-center gap-1.5 text-sm text-muted-foreground">
          <Info className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          <span>현재 페이지 내 정렬이 적용됩니다. 전체 정렬은 페이지를 이동하면 초기화됩니다.</span>
        </div>
      )}

      <div className="rounded-md border">
        <Table aria-label="파이프라인 목록">
          <TableHeader>
            <TableRow>
              <SortableHeader
                direction={sortKey === 'name' ? sortOrder : 'none'}
                onSort={() => toggleSort('name')}
                title="현재 페이지 내에서만 정렬됩니다"
              >
                이름
              </SortableHeader>
              <TableHead>상태</TableHead>
              <SortableHeader
                direction={sortKey === 'stepCount' ? sortOrder : 'none'}
                onSort={() => toggleSort('stepCount')}
                title="현재 페이지 내에서만 정렬됩니다"
              >
                스텝 수
              </SortableHeader>
              <TableHead>트리거</TableHead>
              <TableHead>생성자</TableHead>
              <SortableHeader
                direction={sortKey === 'createdAt' ? sortOrder : 'none'}
                onSort={() => toggleSort('createdAt')}
                title="현재 페이지 내에서만 정렬됩니다"
              >
                생성일
              </SortableHeader>
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
                  <TableCell className="tabular-nums" title={formatDateTimeMinute(pipeline.createdAt)}>
                    {formatDateOnly(pipeline.createdAt)}
                  </TableCell>
                  <TableCell>
                    {/* WD-170: 함께 삭제될 자동 생성 임시 데이터셋 개수를 안내하는 파이프라인 전용 확인 창 */}
                    <PipelineDeleteConfirmDialog
                      pipelineId={pipeline.id}
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
            ) : isError ? (
              <TableRow>
                <TableCell
                  colSpan={7}
                  className={cn('py-12 text-center', isForbidden ? 'text-muted-foreground' : 'text-destructive')}
                >
                  {isForbidden ? '파이프라인을 볼 권한이 없습니다. 관리자에게 권한을 요청하세요.' : '파이프라인 목록을 불러오지 못했습니다.'}
                </TableCell>
              </TableRow>
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
