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
import { parseUtcDate, timeAgo } from '@/lib/formatters';
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

/**
 * 이상 탐지가 켜져 있는데 감시할 메트릭이 하나도 없는 상태인지 판정한다 (#362).
 *
 * <p>MetricPollerService는 anomaly.metrics를 순회할 뿐이라 빈 리스트면 enabled=true여도 절대 발화하지 않는다.
 * 목록에도 같은 신호를 노출해, 상세를 열어보지 않고는 알 수 없던 조용한 오설정을 드러낸다.
 * config는 임의 JSON이라 모든 단계를 옵셔널 체이닝으로 방어한다 — 예상과 다른 모양의 행 하나가
 * 목록 전체를 백지로 만든 전례가 있다(94fe44b4). */
function isAnomalyIdle(config: Record<string, unknown>): boolean {
  const anomaly = config?.anomaly as { enabled?: unknown; metrics?: unknown } | undefined;
  if (!anomaly || anomaly.enabled !== true) return false;
  return !Array.isArray(anomaly.metrics) || anomaly.metrics.length === 0;
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
                      {/* 이상 탐지가 켜졌지만 메트릭이 0개면 그 사실을 목록에서 바로 보이게 한다 (#362).
                          '다음 실행' 칸이 아니라 작업명 아래에 두는 이유: 이상 탐지는 cron 스케줄과
                          독립적이라, cron이 정상 등록된 작업의 '다음 실행'을 덮어쓰면 오히려 틀린 정보가 된다. */}
                      {isAnomalyIdle(job.config) && (
                        <span
                          className="text-xs text-destructive font-medium"
                          data-testid="anomaly-idle"
                        >
                          이상 탐지 미동작 — 메트릭 없음
                        </span>
                      )}
                    </div>
                  </TableCell>
                  <TableCell className="text-sm">{cronToLabel(job.cronExpression)}</TableCell>
                  <TableCell className="text-sm text-muted-foreground">
                    {job.lastExecutedAt ? timeAgo(job.lastExecutedAt) : '-'}
                  </TableCell>
                  <TableCell className="text-sm text-muted-foreground">
                    {/* nextExecuteAt은 타임존 표기 없는 UTC 벽시계이므로 parseUtcDate로 해석한다.
                        new Date()를 쓰면 브라우저 로컬(KST)로 파싱돼 9시간 어긋난다 (#348, #349). */}
                    {!job.enabled || !job.cronExpression?.trim() ? (
                      /* 스케줄 없는 작업(triggerType=ANOMALY)은 애초에 등록 대상이 아니므로
                         next_execute_at이 비어 있는 게 정상이다 — 경고 대상에서 제외한다. */
                      '-'
                    ) : job.nextExecuteAt ? (
                      formatNextRunShort(parseUtcDate(job.nextExecuteAt), job.timezone)
                    ) : (
                      /* 활성인데 다음 실행 시각이 없다 = 스케줄러에 등록되지 못한 상태 (#354).
                         next_execute_at은 등록 성공 시에만 채워지므로 미등록의 관측 가능한 신호가 된다.
                         이전에는 그냥 '-'로 보여서, 사용자는 '활성'만 믿고 매일 도는 줄 알았지만
                         실제로는 한 번도 실행되지 않았고 그 사실이 부팅 ERROR 로그에만 남았다.
                         '실패'로 단정하지 않는 이유: cron이 더 이상 발화하지 않는 경우도 같은 상태다. */
                      <TooltipProvider>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <span
                              className="text-destructive font-medium cursor-help"
                              data-testid="schedule-unregistered"
                            >
                              미등록
                            </span>
                          </TooltipTrigger>
                          <TooltipContent>
                            스케줄러에 등록되지 않아 실행되지 않습니다. 실행 주기 설정을 확인하세요.
                          </TooltipContent>
                        </Tooltip>
                      </TooltipProvider>
                    )}
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
