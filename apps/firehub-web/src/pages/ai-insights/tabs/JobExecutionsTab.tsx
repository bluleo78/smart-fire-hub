/**
 * JobExecutionsTab — 실행 이력 목록 탭.
 *
 * 행 클릭 시 실행 상세 페이지(/ai-insights/jobs/:jobId/executions/:id)로 이동한다.
 * RUNNING 상태의 실행이 있으면 5초 간격으로 자동 폴링한다.
 */
import { Loader2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
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
import { formatDate, formatDuration, getStatusBadgeVariant, getStatusLabel, timeAgo } from '@/lib/formatters';

interface JobExecutionsTabProps {
  jobId: number;
}

export default function JobExecutionsTab({ jobId }: JobExecutionsTabProps) {
  const navigate = useNavigate();
  const [limit, setLimit] = useState(20);

  const [refetchInterval, setRefetchInterval] = useState<number | false>(false);
  const { data: executions = [], isLoading } = useJobExecutions(
    jobId,
    { limit, offset: 0 },
    { refetchInterval },
  );

  const hasRunning = executions.some((e) => e.status === 'RUNNING');
  useEffect(() => {
    setRefetchInterval(hasRunning ? 5000 : false);
  }, [hasRunning]);

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
                    <Badge variant={getStatusBadgeVariant(exec.status)}>
                      {getStatusLabel(exec.status)}
                    </Badge>
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
