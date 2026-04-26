import { Copy, Play, Plus, Zap } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import type { ProactiveJob } from '@/api/proactive';
import { Button } from '@/components/ui/button';
import { Switch } from '@/components/ui/switch';
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
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import {
  useCloneProactiveJob,
  useExecuteProactiveJob,
  useProactiveJobs,
  useUpdateProactiveJob,
} from '@/hooks/queries/useProactiveMessages';
import { handleApiError } from '@/lib/api-error';
import { cronToLabel } from '@/lib/cron-label';
import { timeAgo } from '@/lib/formatters';
import { formatNextRunShort } from '@/lib/next-run';

function channelSummary(config: Record<string, unknown>): string {
  const channels = config?.channels;
  if (!Array.isArray(channels) || channels.length === 0) return '-';

  const parts: string[] = [];

  for (const ch of channels) {
    if (typeof ch === 'string') {
      // 구 형식: channels: ['CHAT', 'EMAIL']
      if (ch === 'CHAT') parts.push('채팅');
      else if (ch === 'EMAIL') parts.push('이메일');
    } else if (ch && typeof ch === 'object') {
      // 신 형식: channels: [{ type: 'CHAT', recipientUserIds: [...], recipientEmails: [...] }]
      const c = ch as { type?: string; recipientUserIds?: unknown[]; recipientEmails?: unknown[] };
      const userCount = (c.recipientUserIds?.length ?? 0) + (c.recipientEmails?.length ?? 0);
      const label = c.type === 'CHAT' ? '채팅' : c.type === 'EMAIL' ? '이메일' : c.type ?? '';
      if (userCount > 0) {
        parts.push(`${label} ${userCount}`);
      } else {
        parts.push(label);
      }
    }
  }

  return parts.join(' / ') || '-';
}

export default function ProactiveJobListPage() {
  const navigate = useNavigate();
  const { data: jobs = [], isLoading, isError } = useProactiveJobs();
  const updateMutation = useUpdateProactiveJob();
  const executeMutation = useExecuteProactiveJob();
  const cloneMutation = useCloneProactiveJob();

  const handleToggle = (job: ProactiveJob, enabled: boolean) => {
    updateMutation.mutate(
      { id: job.id, data: { enabled } },
      {
        onSuccess: () => toast.success(`작업이 ${enabled ? '활성화' : '비활성화'}되었습니다.`),
        onError: () => toast.error('상태 변경에 실패했습니다.'),
      },
    );
  };

  const handleClone = (e: React.MouseEvent, job: ProactiveJob) => {
    e.stopPropagation();
    cloneMutation.mutate(job, {
      onSuccess: (created) => {
        toast.success(`"${created.name}" 작업이 복제되었습니다.`);
        navigate(`/ai-insights/jobs/${created.id}?tab=overview`);
      },
      onError: (err) => handleApiError(err, '작업 복제에 실패했습니다.'),
    });
  };

  const handleExecute = (e: React.MouseEvent, job: ProactiveJob) => {
    e.stopPropagation();
    executeMutation.mutate(job.id, {
      onSuccess: () => {
        toast.success(`"${job.name}" 실행이 시작되었습니다.`, {
          action: {
            label: '결과 보기',
            onClick: () => navigate(`/ai-insights/jobs/${job.id}?tab=executions`),
          },
        });
      },
      onError: () => toast.error('실행에 실패했습니다.'),
    });
  };

  // API 에러 시 빈 상태 대신 에러 메시지 표시 (#46)
  if (isError) {
    return (
      <div className="rounded-lg border border-dashed flex flex-col items-center justify-center py-16 gap-3 text-center">
        <Zap className="h-10 w-10 text-muted-foreground" />
        <div>
          <p className="text-sm font-medium">작업 목록을 불러오지 못했습니다</p>
          <p className="text-xs text-muted-foreground mt-1">잠시 후 다시 시도해 주세요.</p>
        </div>
      </div>
    );
  }

  if (!isLoading && jobs.length === 0) {
    return (
      <div className="rounded-lg border border-dashed flex flex-col items-center justify-center py-16 gap-3 text-center">
        <Zap className="h-10 w-10 text-muted-foreground" />
        <div>
          <p className="text-sm font-medium">스마트 작업 없음</p>
          <p className="text-xs text-muted-foreground mt-1">
            AI가 주기적으로 데이터를 분석하고 리포트를 보내드립니다.
          </p>
        </div>
        <Button size="sm" variant="outline" onClick={() => navigate('/ai-insights/jobs/new')}>
          <Plus className="h-4 w-4" />
          첫 작업 만들기
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          스케줄에 따라 자동으로 AI 분석을 실행하고 결과를 전달합니다.
        </p>
        <Button size="sm" onClick={() => navigate('/ai-insights/jobs/new')}>
          <Plus className="h-4 w-4" />
          작업 추가
        </Button>
      </div>

      <div className="rounded-md border">
        <Table aria-label="스마트 작업 목록">
          <TableHeader>
            <TableRow>
              <TableHead>작업명</TableHead>
              <TableHead>실행 주기</TableHead>
              <TableHead>마지막 실행</TableHead>
              <TableHead>다음 실행</TableHead>
              <TableHead>활성</TableHead>
              <TableHead className="w-[80px]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={6} rows={5} />
            ) : jobs.length > 0 ? (
              jobs.map((job) => (
                <TableRow
                  key={job.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors"
                  onClick={() => navigate(`/ai-insights/jobs/${job.id}`)}
                >
                  <TableCell className="font-medium">
                    {/* 작업명과 채널 요약을 세로로 분리하여 가독성 개선 (#5) */}
                    <div className="flex flex-col gap-0.5">
                      <span>{job.name}</span>
                      <span className="text-xs text-muted-foreground">
                        {channelSummary(job.config)}
                      </span>
                    </div>
                  </TableCell>
                  <TableCell className="text-sm">{cronToLabel(job.cronExpression)}</TableCell>
                  <TableCell className="text-sm text-muted-foreground">
                    {job.lastExecutedAt ? timeAgo(job.lastExecutedAt) : '-'}
                  </TableCell>
                  <TableCell className="text-sm text-muted-foreground">
                    {job.enabled && job.nextExecuteAt
                      ? formatNextRunShort(new Date(job.nextExecuteAt), job.timezone)
                      : '-'}
                  </TableCell>
                  <TableCell>
                    <Switch
                      checked={job.enabled}
                      aria-label={`${job.name} 활성화`}
                      onClick={(e) => e.stopPropagation()}
                      onCheckedChange={(checked) => handleToggle(job, checked)}
                    />
                  </TableCell>
                  <TableCell>
                    {/* 아이콘 버튼에 시각적 툴팁 제공 — aria-label만으로는 마우스 사용자가 기능을 파악하기 어려움 */}
                    <TooltipProvider>
                      <div className="flex items-center gap-1">
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <Button
                              size="icon"
                              variant="ghost"
                              className="h-7 w-7"
                              aria-label="복제"
                              onClick={(e) => handleClone(e, job)}
                              disabled={cloneMutation.isPending}
                            >
                              <Copy className="h-3.5 w-3.5" />
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent>복제</TooltipContent>
                        </Tooltip>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <Button
                              size="icon"
                              variant="ghost"
                              className="h-7 w-7"
                              aria-label="지금 실행"
                              onClick={(e) => handleExecute(e, job)}
                              disabled={executeMutation.isPending}
                            >
                              <Play className="h-3.5 w-3.5" />
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent>지금 실행</TooltipContent>
                        </Tooltip>
                      </div>
                    </TooltipProvider>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={6} message="스마트 작업이 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
