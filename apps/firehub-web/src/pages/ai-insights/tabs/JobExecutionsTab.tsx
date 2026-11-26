/**
 * JobExecutionsTab — 실행 이력 목록 탭.
 *
 * 행 클릭 시 실행 상세 페이지(/ai-insights/jobs/:jobId/executions/:id)로 이동한다.
 * RUNNING 상태의 실행이 있으면 5초 간격으로 자동 폴링한다.
 */
import { Loader2 } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';

import { PendingButton } from '@/components/PendingButton';
import { Badge } from '@/components/ui/badge';
import { LoadMoreFailure, LoadMoreOfflineNotice } from '@/components/ui/load-more-failure';
import { SecurityLevelBadge } from '@/components/ui/SecurityLevelBadge';
import { StatusBadge } from '@/components/ui/status-badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { TableEmptyRow, TableErrorRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useJobExecutions } from '@/hooks/queries/useProactiveMessages';
import { useLoadMoreAnnounce } from '@/hooks/useLoadMoreAnnounce';
import { useLoadMoreLimit } from '@/hooks/useLoadMoreLimit';
import { offlineListMessage, useOfflinePaused } from '@/hooks/useOnlineStatus';
import { isForbiddenError } from '@/lib/api-error';
import { getExecutionDisplayStatus } from '@/lib/error-classifier';
import { formatDuration, getExecutionStatusType, getStatusLabel, timeAgo } from '@/lib/formatters';
import { formatInstantInZone } from '@/lib/next-run';

import { ProactiveReadForbiddenNotice } from '../components/ProactiveReadForbiddenNotice';

interface JobExecutionsTabProps {
  jobId: number;
  /** 작업 시간대 — 시작 시각을 이 시간대+약어로 보인다(WD-1101). 비면 Asia/Seoul. */
  timezone?: string | null;
}

/** 행의 고유 id — 훅 의존성이라 렌더마다 새로 만들지 않게 모듈 상수로 둔다 */
const getExecutionId = (e: { id: number }) => e.id;

/** 행마다 실행 상세 링크가 하나뿐이라 링크 주소의 실행 id 로 첫 새 행을 찾는다 */
const findExecutionRowLink = (tbody: HTMLElement, id: number) =>
  tbody.querySelector<HTMLElement>(`a[href$="/executions/${id}"]`);

export default function JobExecutionsTab({ jobId, timezone }: JobExecutionsTabProps) {
  const navigate = useNavigate();
  const { limit, loadMore, pending, failed, settle } = useLoadMoreLimit();

  // RUNNING 상태 실행이 있으면 5초 폴링, 아니면 비활성화 — state 없이 직접 유도
  const {
    data: executionsData,
    isLoading,
    isPaused,
    isError,
    isSuccess,
    isPlaceholderData,
    isFetching,
    error,
  } = useJobExecutions(
    jobId,
    { limit, offset: 0 },
    {
      refetchInterval: (query) => {
        const data = query.state.data as typeof executions | undefined;
        return data?.some((e) => e.status === 'RUNNING') ? 5000 : false;
      },
    },
  );

  const executions = executionsData ?? [];
  // 데이터 없이 paused(오프라인·재시도 멈춤)면 「실행 이력이 없습니다」 대신 연결 대기로 그린다(WD-971)
  const { waiting, offline } = useOfflinePaused({ data: executionsData, isPaused });

  // 재조회가 아직 끝나지 않음 — 오프라인이면 재조회가 멈춰(paused) isFetching 이 false 라 isPaused 도 함께 본다.
  // 그러지 않으면 오프라인의 낡은 캐시를 성공으로 세어 알리고, 연결 뒤 재조회 실패가 다시 묻힌다 (WD-1019 리뷰)
  const refetchPending = isFetching || isPaused;
  // 「더 보기」 조회가 실패하면 렌더 중에 limit 을 마지막 성공 값으로 되돌려 보던 목록을 남긴다(WD-956) — 오류 렌더가 그려지지 않는다.
  // 돌려주는 값은 「새 limit 이 확정되기 전 재조회 중」 여부다 — 버튼 잠금·오프라인 안내가 이 값을 본다(WD-1038, 리포트 목록과 같은 settle 규칙).
  // settle 은 렌더 중 상태를 갱신하므로 매 렌더 부른다 — 아래 잠금의 `||` 안에 넣어 건너뛰지 않게 따로 받는다.
  const settleLocked = settle({ isError, isSuccess, isPlaceholderData, refetchPending });

  // 「더 보기」·「다시 시도」 응답 뒤 알림·초점 옮기기와 그 응답의 끝 판정은 공용 훅이 맡는다(WD-1032).
  // 「더 보기」 응답 기다림 — 자리표시 데이터로 기다리거나, 낡은 캐시로 먼저 보인 새 limit 의 재조회가 확정되지 않음(settle).
  // 버튼 잠금·보임과 오프라인 안내(WD-1035)가 같은 값을 본다. 기다림이 없을 때(RUNNING 폴링 등)의 isFetching 은 잠그지 않는다.
  const loadMoreLocked = isPlaceholderData || settleLocked;

  const { handleLoadMore, tableWrapRef, bottomRef, statusRef } = useLoadMoreAnnounce({
    items: executions,
    getId: getExecutionId,
    limit,
    failed,
    pending,
    isSuccess,
    isPlaceholderData,
    refetchPending,
    loadMore,
    noun: '실행 이력',
    noneMessage: '더 불러올 실행 이력이 없습니다.',
    findRowTarget: findExecutionRowLink,
  });

  // 조회 실패를 「실행 이력이 없습니다」로 그리지 않는다 (WD-137) — 403 이면 권한 안내, 그 밖은 불러오기 실패 안내.
  // 조회 권한이 없으면 표 대신 권한 안내만 보인다 — 다시 시도해도 같다.
  if (isError && isForbiddenError(error)) {
    return <ProactiveReadForbiddenNotice subject="실행 이력" testId="executions-forbidden" />;
  }

  return (
    <div className="flex flex-col" style={{ height: 'calc(100vh - 280px)' }}>
      {/* 실행 목록 테이블 — 전체 높이 사용 */}
      <div ref={tableWrapRef} className="flex-1 overflow-auto border rounded-md">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-[45%]">실행 시간</TableHead>
              <TableHead className="w-[12%] text-center">상태</TableHead>
              <TableHead className="w-[18%] text-center">소요 시간</TableHead>
              <TableHead className="w-[25%] text-center">전달 채널</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading || waiting ? (
              <TableSkeletonRows columns={4} rows={5} />
            ) : offline ? (
              <TableEmptyRow colSpan={4} message={offlineListMessage('실행 이력')} />
            ) : executions.length > 0 ? (
              executions.map((exec) => (
                <TableRow
                  key={exec.id}
                  className="cursor-pointer hover:bg-muted/50"
                  onClick={() => navigate(`/ai-insights/jobs/${jobId}/executions/${exec.id}`)}
                >
                  <TableCell className="text-sm">
                    <div className="flex items-center gap-2">
                      {/* 키보드 접근성(WD-436): 행 클릭은 마우스로만 열리므로 시작 시각을 실행 상세 링크로 둔다 — Tab 으로 닿고
                          Enter 로 열린다. 클릭은 행 onClick 과 겹쳐 두 번 이동하지 않게 번지지 않게 막는다. */}
                      <Link
                        to={`/ai-insights/jobs/${jobId}/executions/${exec.id}`}
                        className="rounded-sm hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                        onClick={(e) => e.stopPropagation()}
                      >
                        {/* 브라우저 시간대가 아니라 작업 시간대+약어로 — 개요 「마지막 실행」과 같은 표기(WD-1101) */}
                        {formatInstantInZone(exec.startedAt, timezone)} ({timeAgo(exec.startedAt)})
                      </Link>
                      {/* 리포트 등급 배지(WD-198) — 실행 동안 읽은 데이터셋의 최고 등급.
                          목록 칸이라 말줄임 + title(WD-658), 상한은 데이터셋 목록과 같게 둬 시작 시각 링크 폭을 남긴다 (WD-864) */}
                      {exec.securityLevel && (
                        <SecurityLevelBadge level={exec.securityLevel} truncate className="max-w-[5.25rem]" />
                      )}
                    </div>
                  </TableCell>
                  <TableCell className="text-center">
                    {/* 의도된 건너뜀(서버는 FAILED 로 남김)은 실행 상세와 같게 회색 「건너뜀」으로 보인다 (WD-191) */}
                    {(() => {
                      const displayStatus = getExecutionDisplayStatus(exec.status, exec.errorMessage);
                      return (
                        <StatusBadge type={getExecutionStatusType(displayStatus)}>
                          {getStatusLabel(displayStatus)}
                        </StatusBadge>
                      );
                    })()}
                  </TableCell>
                  <TableCell className="text-sm text-center">
                    {exec.completedAt ? formatDuration(exec.startedAt, exec.completedAt) : (
                      exec.status === 'RUNNING' ? (
                        <Loader2 className="h-3.5 w-3.5 animate-spin inline" />
                      ) : '-'
                    )}
                  </TableCell>
                  <TableCell>
                    <div className="flex gap-1 flex-wrap justify-center">
                      {exec.deliveredChannels?.map((ch) => (
                        <Badge key={ch} variant="outline" className="text-xs">
                          {ch}
                        </Badge>
                      ))}
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : isError ? (
              <TableErrorRow colSpan={4} className="py-12" message="실행 이력을 불러오지 못했습니다." />
            ) : (
              <TableEmptyRow colSpan={4} message="실행 이력이 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>

      <div ref={bottomRef}>
        {failed ? (
          <LoadMoreFailure onRetry={handleLoadMore} pending={pending} />
        ) : (
          // 응답 대기 중(isPlaceholderData)에도 버튼을 남기고 pending 으로만 잠근다 — 사라지면 키보드 초점이 body 로 떨어진다(WD-965).
          // 낡은 캐시로 먼저 보인 새 limit 은 행이 limit 보다 적어도 재조회가 확정될 때까지 잠긴 채 남긴다 — 사라지면 초점이 body 로 빠진다(WD-1038).
          // 잠금은 리포트 목록과 같은 settle 규칙이다.
          // isPlaceholderData 를 「더 보기 로딩 중」으로 읽는 것은 이 탭에서 키를 바꾸는 입력이 limit 뿐이기 때문이다 — 필터 등이 생기면 다시 검토한다.
          (executions.length >= limit || loadMoreLocked) && (
            <div className="py-2 flex justify-center">
              {/* 낡은 캐시로 먼저 보이는 재조회 중에도 잠근다 — 그 사이 다시 누르면 기다림의 마지막 행·행 수가 낡은 값으로 덮인다(WD-1019). */}
              <PendingButton
                variant="ghost"
                size="sm"
                pending={loadMoreLocked}
                onClick={handleLoadMore}
              >
                더 보기
              </PendingButton>
            </div>
          )
        )}
        <LoadMoreOfflineNotice waiting={loadMoreLocked} isPaused={isPaused} label="실행 이력" />
      </div>
      {/* 「더 보기」 결과 알림 — 문구가 바뀔 때 읽히도록 늘 그려 둔다(WD-965 리뷰 후속). */}
      <p ref={statusRef} role="status" className="sr-only" data-testid="executions-load-status" />
    </div>
  );
}
