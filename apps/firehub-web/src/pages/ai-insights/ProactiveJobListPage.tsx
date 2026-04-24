import { Copy, Play, Plus, Zap } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import type { ProactiveJob } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';
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

function jobStatusVariant(job: ProactiveJob): 'default' | 'secondary' | 'destructive' | 'outline' {
  if (!job.enabled) return 'secondary';
  const lastStatus = job.lastExecution?.status;
  if (lastStatus === 'FAILED') return 'destructive';
  if (lastStatus === 'RUNNING') return 'default';
  return 'outline';
}

function jobStatusLabel(job: ProactiveJob): string {
  if (!job.enabled) return '비활성';
  const lastStatus = job.lastExecution?.status;
  if (lastStatus === 'FAILED') return '실패';
  if (lastStatus === 'RUNNING') return '실행 중';
  if (lastStatus === 'COMPLETED') return '완료';
  return '대기';
}

export default function ProactiveJobListPage() {
  const navigate = useNavigate();
  const { data: jobs = [], isLoading } = useProactiveJobs();
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
              <TableHead>상태</TableHead>
              <TableHead>활성</TableHead>
              <TableHead className="w-[80px]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={7} rows={5} />
            ) : jobs.length > 0 ? (
              jobs.map((job) => (
                <TableRow
                  key={job.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors"
                  onClick={() => navigate(`/ai-insights/jobs/${job.id}`)}
                >
                  <TableCell className="font-medium">
                    <div>
                      <span>{job.name}</span>
                      <span className="ml-2 text-xs text-muted-foreground">
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
                    <Badge variant={jobStatusVariant(job)}>{jobStatusLabel(job)}</Badge>
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
                    <div className="flex items-center gap-1">
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
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={7} message="스마트 작업이 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
