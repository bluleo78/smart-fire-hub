import { Zap } from 'lucide-react';
import { toast } from 'sonner';

import type { ProactiveJobAdminSummary } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import {
  useAdminDeleteProactiveJob,
  useAdminDisableProactiveJob,
  useWorkspaceProactiveJobs,
} from '@/hooks/queries/useProactiveMessages';
import { useMinuteNow } from '@/hooks/useNowMs';
import { offlineListMessage, useOfflinePaused } from '@/hooks/useOnlineStatus';
import { handleApiError } from '@/lib/api-error';
import { parseUtcDate } from '@/lib/formatters';
import { formatNextRunShortWithZone } from '@/lib/next-run';

import { OwnerIneligibleBadge } from './OwnerIneligibleBadge';

/** 마지막 실행 상태 → 화면 문구. 관리자 목록은 오류 문구를 받지 않으므로(작업 내용 보호) 상태만 보인다. */
const LAST_STATUS_LABELS: Record<NonNullable<ProactiveJobAdminSummary['lastExecutionStatus']>, string> = {
  RUNNING: '실행 중',
  COMPLETED: '성공',
  FAILED: '실패',
};

/**
 * WorkspaceJobsTable — 워크스페이스 전체 스마트 작업 (WD-192, proactive:admin 전용).
 *
 * 작성자가 정지·제거되거나 쓰기 권한을 잃은 작업은 「활성」인 채 실행 때마다 건너뛰는데, 그 작성자는 로그인도 못 할 수 있어 아무도
 * 정리할 수 없었다. 관리자는 여기서 남의 작업을 끄거나 삭제만 할 수 있다.
 * - 서버가 최소 정보(이름·작성자·활성·실행 자격·다음 실행·마지막 실행 상태)만 준다 — 프롬프트·설정·수신자·리포트는 없다.
 * - 행을 눌러 상세로 가지 않는다 — 남의 작업 상세는 서버가 404 로 막는다(열람·편집·켜기·지금 실행 불가).
 * - 켜기는 없다 — 자격 없는 사람이 남의 이름으로 자동 실행을 늘리면 안 된다.
 */
export function WorkspaceJobsTable() {
  const { data: jobsData, isLoading, isError, isPaused } = useWorkspaceProactiveJobs();
  const jobs = jobsData ?? [];
  // 행이 있으면 1분마다 「지남」을 다시 판정한다(WD-1167) — 훅이라 early return 보다 앞에서 부른다
  const now = useMinuteNow(jobs.length > 0);
  // 데이터 없이 paused(오프라인·재시도 멈춤)면 「스마트 작업이 없습니다」 대신 연결 대기로 그린다(WD-971)
  const { waiting, offline } = useOfflinePaused({ data: jobsData, isPaused });
  const disableMutation = useAdminDisableProactiveJob();
  const deleteMutation = useAdminDeleteProactiveJob();

  const handleDisable = (job: ProactiveJobAdminSummary) => {
    disableMutation.mutate(job.id, {
      onSuccess: () => toast.success(`"${job.name}" 작업을 껐습니다.`),
      onError: (err) => handleApiError(err, '작업을 끄지 못했습니다.'),
    });
  };

  const handleDelete = (job: ProactiveJobAdminSummary) => {
    deleteMutation.mutate(job.id, {
      onSuccess: () => toast.success(`"${job.name}" 작업을 삭제했습니다.`),
      onError: (err) => handleApiError(err, '작업 삭제에 실패했습니다.'),
    });
  };

  if (isError) {
    return (
      <div
        className="rounded-lg border border-dashed flex flex-col items-center justify-center py-16 gap-3 text-center"
        data-testid="workspace-jobs-error"
      >
        <Zap className="h-10 w-10 text-muted-foreground" />
        <div>
          <p className="text-sm font-medium">워크스페이스 작업 목록을 불러오지 못했습니다</p>
          <p className="text-xs text-muted-foreground mt-1">잠시 후 다시 시도해 주세요.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">
        워크스페이스의 모든 스마트 작업입니다. 다른 사람의 작업은 끄거나 삭제만 할 수 있습니다.
      </p>
      <div className="rounded-md border">
        <Table aria-label="워크스페이스 전체 스마트 작업">
          <TableHeader>
            <TableRow>
              <TableHead>작업명</TableHead>
              <TableHead>작성자</TableHead>
              <TableHead>실행 자격</TableHead>
              <TableHead>다음 실행</TableHead>
              <TableHead>마지막 실행</TableHead>
              <TableHead>활성</TableHead>
              <TableHead className="w-[140px]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading || waiting ? (
              <TableSkeletonRows columns={7} rows={5} />
            ) : offline ? (
              <TableEmptyRow colSpan={7} message={offlineListMessage('워크스페이스 스마트 작업')} />
            ) : jobs.length > 0 ? (
              jobs.map((job) => (
                <TableRow key={job.id} data-testid={`workspace-job-${job.id}`}>
                  {/* 긴 작업명이 칸을 늘려 활성·끄기 칸을 화면 밖으로 밀지 않도록 셀 폭을 따라 말줄임한다 —
                      전체 이름은 title 툴팁으로 본다 (WD-558, 내 작업 목록과 같은 패턴) */}
                  <TableCell className="font-medium max-w-0 min-w-[180px]">
                    <div className="truncate" title={job.name}>
                      {job.name}
                    </div>
                  </TableCell>
                  <TableCell className="text-sm">{job.ownerName ?? '-'}</TableCell>
                  <TableCell>
                    {job.ownerEligible ? (
                      <span className="text-sm text-muted-foreground">정상</span>
                    ) : (
                      <OwnerIneligibleBadge
                        reason={job.ownerIneligibleReason}
                        variant="badge"
                        testId={`workspace-job-${job.id}-ineligible`}
                      />
                    )}
                  </TableCell>
                  <TableCell className="text-sm text-muted-foreground">
                    {/* nextExecuteAt 은 타임존 표기 없는 UTC 벽시계다 — 내 작업 목록과 같은 해석을 쓴다 (#348).
                        WD-1092: 시각 뒤에 작업 시간대 이름표(KST 등)를 붙여 다른 시간대 작업과 헷갈리지 않게 하고,
                        Intl 이 거부하는 시간대 값이 와도 표 전체가 깨지지 않도록 공용 포매터(브라우저 시간대로 대체)를 쓴다 */}
                    {job.enabled && job.nextExecuteAt
                      ? formatNextRunShortWithZone(parseUtcDate(job.nextExecuteAt), job.timezone ?? 'Asia/Seoul', now)
                      : '-'}
                  </TableCell>
                  <TableCell className="text-sm text-muted-foreground">
                    {job.lastExecutionStatus ? LAST_STATUS_LABELS[job.lastExecutionStatus] : '-'}
                  </TableCell>
                  <TableCell>
                    <Badge variant={job.enabled ? 'default' : 'secondary'}>
                      {job.enabled ? '활성' : '비활성'}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-1">
                      {job.enabled && (
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`${job.name} 끄기`}
                          onClick={() => handleDisable(job)}
                          disabled={disableMutation.isPending}
                        >
                          끄기
                        </Button>
                      )}
                      <DeleteConfirmDialog
                        entityName="스마트 작업"
                        itemName={job.name}
                        extraDetail={job.ownerName ? `작성자: ${job.ownerName}` : undefined}
                        onConfirm={() => handleDelete(job)}
                        trigger={
                          <Button
                            size="sm"
                            variant="ghost"
                            className="text-destructive"
                            aria-label={`${job.name} 삭제`}
                            disabled={deleteMutation.isPending}
                          >
                            삭제
                          </Button>
                        }
                      />
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={7} message="워크스페이스에 스마트 작업이 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
