/**
 * JobExecutionsTab — 실행 이력 목록 탭.
 *
 * 행 클릭 시 실행 상세 페이지(/ai-insights/jobs/:jobId/executions/:id)로 이동한다.
 * RUNNING 상태의 실행이 있으면 5초 간격으로 자동 폴링한다.
 */
import { Loader2 } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { PendingButton } from '@/components/PendingButton';
import { Badge } from '@/components/ui/badge';
import { LoadMoreFailure } from '@/components/ui/load-more-failure';
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
import { useLoadMoreLimit } from '@/hooks/useLoadMoreLimit';
import { offlineListMessage, useOfflinePaused } from '@/hooks/useOnlineStatus';
import { isForbiddenError } from '@/lib/api-error';
import { getExecutionDisplayStatus } from '@/lib/error-classifier';
import { isFocusLost } from '@/lib/focus-lost';
import { formatDate, formatDuration, getExecutionStatusType, getStatusLabel, timeAgo } from '@/lib/formatters';

import { ProactiveReadForbiddenNotice } from '../components/ProactiveReadForbiddenNotice';

interface JobExecutionsTabProps {
  jobId: number;
}

export default function JobExecutionsTab({ jobId }: JobExecutionsTabProps) {
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
  settle({ isError, isSuccess, isPlaceholderData, refetchPending });

  // 「더 보기」·「다시 시도」 응답 뒤 알림·초점 옮기기 (WD-965 리뷰 후속)
  const tableWrapRef = useRef<HTMLDivElement>(null);
  // 목록 아래 「더 보기」·실패 블록을 감싸는 자리 — 누른 때 초점이 여기 있었는지 보고, 응답 뒤 남은 「더 보기」를 찾는다
  const bottomRef = useRef<HTMLDivElement>(null);
  /**
   * 누른 때의 기다림 — null 이면 기다리는 「더 보기」가 없다.
   * - lastId: 누른 때 마지막 행의 실행 id. 새 행은 그 행 뒤의 행이다 — RUNNING 폴링으로 맨 위에 새 실행이 끼어
   *   위치가 밀려도 id 로 가려 개수·첫 새 행이 맞게.
   * - from: 누른 때 행 수(마지막 행 id 를 못 찾을 때의 대비).
   * - hadFocus: 누른 때 초점이 아래 버튼 자리에 있었는지 — 마우스로 눌러 초점이 body 인 경우(Safari 등)에는 옮기지 않는다.
   */
  const pendingLoadRef = useRef<{ lastId: number | undefined; from: number; hadFocus: boolean } | null>(null);
  // 알림 문구는 상태가 아니라 status 영역 DOM 에 직접 쓴다 — 응답 뒤 effect 에서 setState 하면 렌더가 한 번 더 돈다.
  const statusRef = useRef<HTMLParagraphElement>(null);
  const statusFrameRef = useRef<number | null>(null);
  // 「더 보기」 응답의 끝 — 실패 확정(재시도 중이 아님), 또는 자리표시·재시도·백그라운드 재조회가 모두 끝난 성공 (WD-1019)
  const loadFailed = failed && !pending;
  const loadSettled = isSuccess && !isPlaceholderData && !pending && !refetchPending;
  // 「더 보기」 응답을 기다리는 중인지 — 렌더에서 읽어 버튼을 잠그므로 ref(pendingLoadRef)가 아닌 state 로도 둔다(WD-1019).
  const [loadMoreWaiting, setLoadMoreWaiting] = useState(false);
  // 응답이 끝나면 렌더 중에 접는다 — effect 에서 setState 하면 렌더가 한 번 더 돈다. 값이 바뀔 때만 set 하므로 무한 렌더가 없다.
  if (loadMoreWaiting && (loadFailed || loadSettled)) {
    setLoadMoreWaiting(false);
  }

  // 화면을 떠나면 아직 쓰지 않은 알림을 버린다
  useEffect(
    () => () => {
      if (statusFrameRef.current !== null) cancelAnimationFrame(statusFrameRef.current);
    },
    [],
  );

  /** 「더 보기」·「다시 시도」 공용 — 지금 자리를 기억하고 지난 알림을 비운 뒤 limit 을 늘린다(같은 문구도 다시 읽히게). */
  const handleLoadMore = () => {
    pendingLoadRef.current = {
      lastId: executions.at(-1)?.id,
      from: executions.length,
      hadFocus: !!bottomRef.current?.contains(document.activeElement),
    };
    // 앞 응답의 알림 프레임이 아직 안 돌았으면 취소한다 — 그대로 두면 새 응답 전에 지난 문구가 다시 쓰인다 (WD-1019)
    if (statusFrameRef.current !== null) {
      cancelAnimationFrame(statusFrameRef.current);
      statusFrameRef.current = null;
    }
    if (statusRef.current) statusRef.current.textContent = '';
    setLoadMoreWaiting(true);
    loadMore();
  };

  /**
   * 응답이 오면 새로 받은 행 수를 status 영역으로 알리고, 버튼이 사라져 초점을 잃었으면 옮긴다.
   * 왜: 목록 끝에 닿으면 「더 보기」가, 다시 시도가 성공하면 실패 블록이 사라져 키보드 초점이 body 로 빠지고,
   *     새 행이 붙은 것도 보조기술에 알려지지 않았다.
   * 초점은 같은 자리의 「더 보기」 → 첫 새 행 → (새 행이 없으면) 마지막 행 순으로 옮긴다 — 읽던 자리에서 이어 가게.
   * 알림은 초점을 옮긴 다음 프레임에 쓴다 — 같은 때 쓰면 새 초점의 낭독에 polite 알림이 묻힐 수 있다.
   * 실패는 LoadMoreFailure 가 alert 로 알리고 「다시 시도」로 초점을 지키므로 여기서는 기다림만 접는다.
   */
  useEffect(() => {
    const load = pendingLoadRef.current;
    if (load === null) return;
    // 실패면 기다림만 접는다 — 남겨 두면 나중의 성공(폴링 등)이 사용자 동작 없이 지난 개수를 알리고 초점을 옮긴다 (WD-1019)
    if (loadFailed) {
      pendingLoadRef.current = null;
      return;
    }
    // 낡은 캐시로 먼저 보인 새 limit 은 백그라운드 재조회가 끝나야 최종 행이다 — 그때 알리고 초점을 정한다 (WD-1019)
    if (!loadSettled) return;
    pendingLoadRef.current = null;
    const lastIndex = executions.findIndex((e) => e.id === load.lastId);
    const firstNew = lastIndex >= 0 ? lastIndex + 1 : load.from;
    const added = executions.length - firstNew;
    const hasMore = executions.length >= limit;
    const message =
      added <= 0
        ? '더 불러올 실행 이력이 없습니다.'
        : `실행 이력 ${added}건을 더 불러왔습니다.${hasMore ? '' : ' 마지막 실행 이력입니다.'}`;
    if (load.hadFocus && isFocusLost()) {
      // 행마다 실행 상세 링크가 하나뿐이라 링크 주소의 실행 id 로 첫 새 행을 찾는다
      const tbody = tableWrapRef.current?.querySelector('tbody');
      const firstNewId = executions[firstNew]?.id;
      const target =
        bottomRef.current?.querySelector<HTMLElement>('button') ??
        (firstNewId !== undefined ? tbody?.querySelector<HTMLElement>(`a[href$="/executions/${firstNewId}"]`) : null) ??
        tbody?.querySelector<HTMLElement>('tr:last-child a');
      target?.focus();
    }
    // 걸려 있는 지난 프레임이 있으면 먼저 취소한다 — 새 알림을 지난 알림이 덮지 않게 (WD-1019)
    if (statusFrameRef.current !== null) cancelAnimationFrame(statusFrameRef.current);
    statusFrameRef.current = requestAnimationFrame(() => {
      statusFrameRef.current = null;
      if (statusRef.current) statusRef.current.textContent = message;
    });
  }, [executions, loadFailed, loadSettled, limit]);

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
                        {formatDate(exec.startedAt)} ({timeAgo(exec.startedAt)})
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
          // isPlaceholderData 를 「더 보기 로딩 중」으로 읽는 것은 이 탭에서 키를 바꾸는 입력이 limit 뿐이기 때문이다 — 필터 등이 생기면 다시 검토한다.
          (executions.length >= limit || isPlaceholderData) && (
            <div className="py-2 flex justify-center">
              {/* 낡은 캐시로 먼저 보이는 재조회 중(isFetching·오프라인 멈춤)에도 잠근다 — 그 사이 다시 누르면 기다림의 마지막 행·행 수가 낡은 값으로 덮인다(WD-1019).
                  기다림이 없을 때(RUNNING 폴링 등)의 isFetching 은 잠그지 않는다. */}
              <PendingButton
                variant="ghost"
                size="sm"
                pending={isPlaceholderData || (loadMoreWaiting && refetchPending)}
                onClick={handleLoadMore}
              >
                더 보기
              </PendingButton>
            </div>
          )
        )}
      </div>
      {/* 「더 보기」 결과 알림 — 문구가 바뀔 때 읽히도록 늘 그려 둔다(WD-965 리뷰 후속). */}
      <p ref={statusRef} role="status" className="sr-only" data-testid="executions-load-status" />
    </div>
  );
}
