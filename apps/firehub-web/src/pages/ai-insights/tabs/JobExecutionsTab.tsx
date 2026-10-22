/**
 * JobExecutionsTab — 실행 이력 목록 탭.
 *
 * 행 클릭 시 실행 상세 페이지(/ai-insights/jobs/:jobId/executions/:id)로 이동한다.
 * RUNNING 상태의 실행이 있으면 5초 간격으로 자동 폴링한다.
 */
import { Loader2 } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/ui/status-badge';
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
import { useJobExecutions } from '@/hooks/queries/useProactiveMessages';
import { isForbiddenError } from '@/lib/api-error';
import { getExecutionDisplayStatus } from '@/lib/error-classifier';
import { formatDate, formatDuration, getExecutionStatusType, getStatusLabel, timeAgo } from '@/lib/formatters';

import { ProactiveReadForbiddenNotice } from '../components/ProactiveReadForbiddenNotice';

interface JobExecutionsTabProps {
  jobId: number;
}

export default function JobExecutionsTab({ jobId }: JobExecutionsTabProps) {
  const navigate = useNavigate();
  const [limit, setLimit] = useState(20);

  // RUNNING 상태 실행이 있으면 5초 폴링, 아니면 비활성화 — state 없이 직접 유도
  const { data: executions = [], isLoading, isError, error } = useJobExecutions(
    jobId,
    { limit, offset: 0 },
    {
      refetchInterval: (query) => {
        const data = query.state.data as typeof executions | undefined;
        return data?.some((e) => e.status === 'RUNNING') ? 5000 : false;
      },
    },
  );

  // 조회 실패를 「실행 이력이 없습니다」로 그리지 않는다 (WD-137) — 403 이면 권한 안내, 그 밖은 불러오기 실패 안내.
  // 조회 권한이 없으면 표 대신 권한 안내만 보인다 — 다시 시도해도 같다.
  if (isError && isForbiddenError(error)) {
    return <ProactiveReadForbiddenNotice subject="실행 이력" testId="executions-forbidden" />;
  }

  return (
    <div className="flex flex-col" style={{ height: 'calc(100vh - 280px)' }}>
      {/* 실행 목록 테이블 — 전체 높이 사용 */}
      <div className="flex-1 overflow-auto border rounded-md">
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
            {isLoading ? (
              <TableSkeletonRows columns={4} rows={5} />
            ) : executions.length > 0 ? (
              executions.map((exec) => (
                <TableRow
                  key={exec.id}
                  className="cursor-pointer hover:bg-muted/50"
                  onClick={() => navigate(`/ai-insights/jobs/${jobId}/executions/${exec.id}`)}
                >
                  <TableCell className="text-sm">
                    {formatDate(exec.startedAt)} ({timeAgo(exec.startedAt)})
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
              <TableRow>
                <TableCell colSpan={4} className="py-12 text-center text-destructive">
                  실행 이력을 불러오지 못했습니다.
                </TableCell>
              </TableRow>
            ) : (
              <TableEmptyRow colSpan={4} message="실행 이력이 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>

      {executions.length >= limit && (
        <div className="py-2 flex justify-center">
          <Button variant="ghost" size="sm" onClick={() => setLimit((l) => l + 20)}>
            더 보기
          </Button>
        </div>
      )}
    </div>
  );
}
