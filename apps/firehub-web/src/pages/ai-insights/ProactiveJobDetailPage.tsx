import { zodResolver } from '@hookform/resolvers/zod';
import { Activity, ArrowLeft, Copy } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  useCloneProactiveJob,
  useCreateProactiveJob,
  useDeleteProactiveJob,
  useExecuteProactiveJob,
  useProactiveJob,
  useProactiveTemplates,
  useUpdateProactiveJob,
} from '@/hooks/queries/useProactiveMessages';
import { handleApiError } from '@/lib/api-error';
import { type ProactiveJobFormValues,proactiveJobSchema } from '@/lib/validations/proactive-job';

import JobExecutionsTab from './tabs/JobExecutionsTab';
import JobMonitoringTab from './tabs/JobMonitoringTab';
import JobOverviewTab from './tabs/JobOverviewTab';

function buildDefaultValues(job?: {
  name: string;
  prompt: string;
  templateId: number | null;
  cronExpression: string;
  timezone: string;
  triggerType?: string;
  config: Record<string, unknown>;
}): ProactiveJobFormValues {
  if (!job) {
    return {
      name: '',
      prompt: '',
      templateId: null,
      cronExpression: '0 9 * * *',
      timezone: 'Asia/Seoul',
      triggerType: 'SCHEDULE',
      config: { channels: [{ type: 'CHAT', recipientUserIds: [], recipientEmails: [] }] },
    };
  }

  // Normalize config.channels to new format
  const rawChannels = Array.isArray(job.config?.channels) ? job.config.channels : [];
  const channels = rawChannels.map((ch) => {
    if (typeof ch === 'string') {
      return { type: ch as 'CHAT' | 'EMAIL', recipientUserIds: [], recipientEmails: [] };
    }
    const c = ch as { type?: string; recipientUserIds?: number[]; recipientEmails?: string[]; attachPdf?: boolean };
    return {
      type: (c.type ?? 'CHAT') as 'CHAT' | 'EMAIL',
      recipientUserIds: c.recipientUserIds ?? [],
      recipientEmails: c.recipientEmails ?? [],
      ...(c.attachPdf !== undefined && { attachPdf: c.attachPdf }),
    };
  });

  // Normalize anomaly config
  const rawAnomaly = job.config?.anomaly as Record<string, unknown> | undefined;
  const anomaly = rawAnomaly
    ? {
        enabled: (rawAnomaly.enabled as boolean) ?? false,
        metrics: Array.isArray(rawAnomaly.metrics) ? rawAnomaly.metrics : [],
        sensitivity: ((rawAnomaly.sensitivity as string) ?? 'medium') as 'low' | 'medium' | 'high',
        cooldownMinutes: (rawAnomaly.cooldownMinutes as number) ?? 30,
      }
    : undefined;

  return {
    name: job.name,
    prompt: job.prompt,
    templateId: job.templateId ?? null,
    cronExpression: job.cronExpression,
    timezone: job.timezone ?? 'Asia/Seoul',
    triggerType: (job.triggerType as 'SCHEDULE' | 'ANOMALY' | 'BOTH') ?? 'SCHEDULE',
    config: { channels, anomaly },
  };
}

export default function ProactiveJobDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const isNew = !id || id === 'new';
  const jobId = isNew ? 0 : Number(id);

  const activeTab = searchParams.get('tab') ?? 'overview';

  const { data: job, isLoading } = useProactiveJob(jobId);
  const { data: templates = [] } = useProactiveTemplates();

  const [isEditing, setIsEditing] = useState(isNew);

  const form = useForm<ProactiveJobFormValues>({
    resolver: zodResolver(proactiveJobSchema),
    values: buildDefaultValues(job),
  });

  const createMutation = useCreateProactiveJob();
  const updateMutation = useUpdateProactiveJob();
  const deleteMutation = useDeleteProactiveJob();
  const executeMutation = useExecuteProactiveJob();
  const cloneMutation = useCloneProactiveJob();

  const handleTabChange = (tab: string) => {
    setSearchParams({ tab });
  };

  const handleSave = form.handleSubmit(async (values) => {
    const payload = {
      name: values.name,
      prompt: values.prompt,
      templateId: values.templateId ?? null,
      cronExpression: values.cronExpression,
      timezone: values.timezone,
      triggerType: values.triggerType,
      config: values.config,
    };

    if (isNew) {
      createMutation.mutate(payload, {
        onSuccess: (created) => {
          toast.success('작업이 생성되었습니다.');
          setIsEditing(false);
          navigate(`/ai-insights/jobs/${created.id}`);
        },
        onError: (err) => handleApiError(err, '작업 생성에 실패했습니다.'),
      });
    } else {
      updateMutation.mutate(
        { id: jobId, data: payload },
        {
          onSuccess: () => {
            toast.success('작업이 저장되었습니다.');
            setIsEditing(false);
          },
          onError: (err) => handleApiError(err, '작업 저장에 실패했습니다.'),
        },
      );
    }
  });

  const handleDelete = () => {
    if (!job) return;
    deleteMutation.mutate(jobId, {
      onSuccess: () => {
        toast.success('작업이 삭제되었습니다.');
        navigate('/ai-insights/jobs');
      },
      onError: (err) => handleApiError(err, '작업 삭제에 실패했습니다.'),
    });
  };

  const handleExecute = () => {
    if (!job) return;
    executeMutation.mutate(jobId, {
      onSuccess: () => {
        toast.success(`"${job.name}" 실행이 시작되었습니다.`, {
          action: {
            label: '결과 보기',
            onClick: () => {
              setSearchParams({ tab: 'executions' });
            },
          },
        });
      },
      onError: (err) => handleApiError(err, '실행에 실패했습니다.'),
    });
  };

  const handleClone = () => {
    if (!job) return;
    cloneMutation.mutate(job, {
      onSuccess: (created) => {
        toast.success(`"${created.name}" 작업이 복제되었습니다.`);
        navigate(`/ai-insights/jobs/${created.id}?tab=overview`);
        setIsEditing(true);
      },
      onError: (err) => handleApiError(err, '작업 복제에 실패했습니다.'),
    });
  };

  const isSaving = createMutation.isPending || updateMutation.isPending;

  if (!isNew && isLoading) {
    return (
      <div className="space-y-6">
        <div className="h-8 w-64 bg-muted animate-pulse rounded" />
        <div className="h-96 bg-muted animate-pulse rounded" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <header className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => navigate('/ai-insights/jobs')}
            aria-label="목록으로"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">
              {isNew ? '새 스마트 작업' : (job?.name ?? '-')}
            </h1>
            {!isNew && job && (
              <Badge
                variant={job.enabled ? 'default' : 'secondary'}
                className="mt-1"
              >
                {job.enabled ? '활성' : '비활성'}
              </Badge>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2">
          {!isNew && !isEditing && (
            <>
              <Button
                variant="outline"
                size="sm"
                onClick={handleClone}
                disabled={cloneMutation.isPending}
              >
                <Copy className="h-3.5 w-3.5 mr-1" />
                복제
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={handleExecute}
                disabled={executeMutation.isPending}
              >
                지금 실행
              </Button>
              <Button variant="outline" size="sm" onClick={() => setIsEditing(true)}>
                편집
              </Button>
              <Button
                variant="destructive"
                size="sm"
                onClick={handleDelete}
                disabled={deleteMutation.isPending}
              >
                삭제
              </Button>
            </>
          )}
          {isEditing && (
            <>
              {!isNew && (
                <Button variant="outline" size="sm" onClick={() => setIsEditing(false)}>
                  취소
                </Button>
              )}
              <Button size="sm" onClick={handleSave} disabled={isSaving}>
                {isSaving ? '저장 중...' : isNew ? '생성' : '저장'}
              </Button>
            </>
          )}
        </div>
      </header>

      {/* 탭 */}
      <Tabs value={activeTab} onValueChange={handleTabChange}>
        <TabsList>
          <TabsTrigger value="overview">개요</TabsTrigger>
          <TabsTrigger value="monitoring">
            <Activity className="h-3.5 w-3.5 mr-1.5" />
            모니터링
          </TabsTrigger>
          {!isNew && <TabsTrigger value="executions">실행 이력</TabsTrigger>}
        </TabsList>

        <TabsContent value="overview">
          <JobOverviewTab
            job={job}
            isNew={isNew}
            isEditing={isEditing}
            form={form}
            templates={templates}
          />
        </TabsContent>

        <TabsContent value="monitoring">
          <JobMonitoringTab
            form={form}
            isEditing={isEditing}
            jobId={jobId}
          />
        </TabsContent>

        {!isNew && (
          <TabsContent value="executions">
            <JobExecutionsTab jobId={jobId} />
          </TabsContent>
        )}
      </Tabs>
    </div>
  );
}
