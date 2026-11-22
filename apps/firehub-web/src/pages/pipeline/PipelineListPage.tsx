import { Clock, Info, Plus, Trash2 } from 'lucide-react';
import { useMemo,useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { PageHeading } from '@/components/PageHeading';
import { PendingButton } from '@/components/PendingButton';
import { TruncatedLink } from '@/components/TruncatedLink';
import { SimplePagination } from '@/components/ui/simple-pagination';
import { TableEmptyRow, TableErrorRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { TruncatedText } from '@/components/ui/truncated-text';
import { handleApiError, isForbiddenError } from '@/lib/api-error';
import { iGa } from '@/lib/utils';

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
import { useDeletePipeline,usePipelinePermissions,usePipelines } from '../../hooks/queries/usePipelines';
import { formatDateOnly, formatDateTimeMinute } from '../../lib/formatters';
import { getPageAfterDelete } from '../../lib/pagination';
import { PipelineDeleteConfirmDialog } from './components/PipelineDeleteConfirmDialog';

export default function PipelineListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const size = 10;

  const { data: pipelinesData, isLoading, isError, error, refetch } = usePipelines({ page, size });
  // 「다시 시도」 수동 재시도 중 여부(WD-956). 실패한 쪽은 data 가 없어 재조회하면 v5 가 pending(isLoading)으로 되돌려
  // 스켈레톤이 오류 행을 대신하고 초점 잡힌 버튼이 언마운트되므로, 재시도 중에는 오류 행과 버튼을 그대로 둔다.
  const [retrying, setRetrying] = useState(false);
  const handleRetry = () => {
    // 재시도 중 겹침 클릭은 PendingButton 이 막는다(aria-disabled + 클릭 가드).
    setRetrying(true);
    // 조회가 끝나면(성공·실패 모두) 해제한다 — 성공이면 행이, 실패면 오류 행이 그대로 그려진다.
    void refetch().finally(() => setRetrying(false));
  };
  // 조회 실패를 빈 목록으로 그리지 않는다 (WD-122) — 403 이면 권한 안내, 그 밖은 불러오기 실패 안내.
  const isForbidden = isError && isForbiddenError(error);
  const deletePipeline = useDeletePipeline();
  // 추가(pipeline:write)·삭제(pipeline:delete) 버튼은 서버 권한과 같은 기준으로 숨긴다(WD-219) — 예전엔 조회 전용 사용자에게도 보여 눌러야 403 을 받았다.
  const { canWritePipeline, canDeletePipeline } = usePipelinePermissions();

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
  // 다음 쪽 조회가 실패하면 data 가 비어 totalPages 가 0 이 돼 쪽 이동이 사라진다(WD-956).
  // 마지막 성공 응답의 totalPages 를 기억해 실패 중에도 이전 쪽·다른 쪽으로 이동하게 한다.
  const [lastTotalPages, setLastTotalPages] = useState(0);
  if (pipelinesData && pipelinesData.totalPages !== lastTotalPages) {
    setLastTotalPages(pipelinesData.totalPages);
  }
  const totalPages = pipelinesData ? pipelinesData.totalPages : lastTotalPages;
  // 불러오기 실패 안내 문구 — 권한 없음 / 다음 쪽 실패(쪽 이동이 그려지면 이동 권유 포함) / 첫 쪽 실패.
  let errorMessage = '파이프라인 목록을 불러오지 못했습니다.';
  if (isForbidden) {
    errorMessage = '파이프라인을 볼 권한이 없습니다. 관리자에게 권한을 요청하세요.';
  } else if (page > 0 && totalPages > 1) {
    errorMessage = '이 쪽을 불러오지 못했습니다. 다시 시도하거나 다른 쪽으로 이동하세요.';
  } else if (page > 0) {
    errorMessage = '이 쪽을 불러오지 못했습니다. 다시 시도하세요.'; // 쪽 이동이 그려지지 않으면 이동 권유를 뺀다
  }

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
        <PageHeading>파이프라인 관리</PageHeading>
        {/* 조회 권한이 없으면 만들어도 볼 수 없으니 추가 버튼을 숨긴다 (WD-122). 생성 권한(pipeline:write)이 없어도 숨긴다 (WD-219). */}
        {!isForbidden && canWritePipeline && (
          <Button asChild>
            <Link to="/pipelines/new">
              <Plus className="h-4 w-4" />
              새 파이프라인
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

      {/* WD-634: 긴 값 상한 = (표 폭 100cqw − --k) × 몫. --k = 짧은 칸 선언 폭(상태·스텝 수·트리거·생성일·삭제) + 긴 값 칸 여백
          WD-650: 상태 칸은 가장 넓은 배지 「비활성」(약 65px, 칸 여백 포함)이 들어가도록 4.5rem — 선언 폭보다 넓으면 --k 가 모자라 표가 넘친다 */}
      <div className="@container [--k:calc(4.5rem_+_5.25rem_+_3.75rem_+_7rem_+_3.75rem_+_2rem)] rounded-md border">
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
              <TableHead className="w-[4.5rem]">상태</TableHead>
              <SortableHeader
                className="w-[5.25rem]"
                direction={sortKey === 'stepCount' ? sortOrder : 'none'}
                onSort={() => toggleSort('stepCount')}
                title="현재 페이지 내에서만 정렬됩니다"
              >
                스텝 수
              </SortableHeader>
              <TableHead className="w-[3.75rem]">트리거</TableHead>
              <TableHead>생성자</TableHead>
              <SortableHeader
                className="w-[7rem]"
                direction={sortKey === 'createdAt' ? sortOrder : 'none'}
                onSort={() => toggleSort('createdAt')}
                title="현재 페이지 내에서만 정렬됩니다"
              >
                생성일
              </SortableHeader>
              <TableHead className="w-[3.75rem]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading && !retrying ? (
              <TableSkeletonRows columns={7} rows={5} />
            ) : pipelines.length > 0 ? (
              pipelines.map((pipeline) => (
                <TableRow
                  key={pipeline.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors row-hover"
                  onClick={() => navigate(`/pipelines/${pipeline.id}`)}
                >
                  {/* WD-559·WD-634: 긴 값은 표 컨테이너 비례 상한(cqw)으로 말줄임 — 이유는 TruncatedText 설명 */}
                  <TableCell className="font-medium">
                    {/* 키보드 접근성(WD-396): 행 클릭은 마우스 전용이라 이름을 상세 링크로 둔다 */}
                    <TruncatedLink to={`/pipelines/${pipeline.id}`} value={pipeline.name} className="max-w-[min(24rem,calc((100cqw_-_var(--k))_*_0.7))]" />
                  </TableCell>
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
                  <TableCell>
                    <TruncatedText value={pipeline.createdBy} className="max-w-[min(12rem,calc((100cqw_-_var(--k))_*_0.3))]" />
                  </TableCell>
                  <TableCell className="tabular-nums" title={formatDateTimeMinute(pipeline.createdAt)}>
                    {formatDateOnly(pipeline.createdAt)}
                  </TableCell>
                  <TableCell>
                    {/* WD-170: 함께 삭제될 자동 생성 임시 데이터셋 개수를 안내하는 파이프라인 전용 확인 창.
                        삭제 권한(pipeline:delete)이 없으면 버튼을 그리지 않는다(WD-219) — 열 자체는 두어 표 모양을 유지한다. */}
                    {canDeletePipeline && (
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
                    )}
                  </TableCell>
                </TableRow>
              ))
            ) : isError || retrying ? (
              <TableErrorRow
                colSpan={7}
                className="py-12"
                tone={isForbidden ? 'muted' : 'error'}
                message={errorMessage}
                // 403 은 다시 해도 같아 버튼을 두지 않는다. 요청 중에는 disabled 대신 aria-disabled 로 잠가(초점 유지) 중복 요청을 막는다(WD-956).
                action={
                  isForbidden ? undefined : (
                    <PendingButton pending={retrying} variant="outline" size="sm" className="mt-3" onClick={handleRetry}>
                      다시 시도
                    </PendingButton>
                  )
                }
              />
            ) : (
              <TableEmptyRow
                colSpan={7}
                message="파이프라인이 없습니다."
                // 빈 목록에서 바로 만들 수 있게 다른 목록과 같은 버튼을 둔다(WD-621). 머리말 버튼과 같은 권한(pipeline:write)으로만 보인다.
                emptyAction={
                  canWritePipeline ? (
                    <Button asChild size="sm" variant="outline">
                      <Link to="/pipelines/new">
                        <Plus className="h-4 w-4" />
                        새 파이프라인 만들기
                      </Link>
                    </Button>
                  ) : undefined
                }
              />
            )}
          </TableBody>
        </Table>
      </div>

      <SimplePagination page={page} totalPages={totalPages} onPageChange={setPage} />
    </div>
  );
}
