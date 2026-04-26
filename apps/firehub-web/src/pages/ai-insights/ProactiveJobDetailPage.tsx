import { zodResolver } from '@hookform/resolvers/zod';
import { Activity, ArrowLeft, Copy } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
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

  const { data: job, isLoading, isError } = useProactiveJob(jobId);
  const { data: templates = [] } = useProactiveTemplates();

  const [isEditing, setIsEditing] = useState(isNew);

  // 사용자 상호작용 후 변경 여부 추적 (이슈 #59)
  // - 초기 로드(values 동기화)는 변경으로 간주하지 않기 위해 명시적으로 markDirty()를 호출
  // - 작업명/프롬프트/템플릿/트리거/주기/타임존/채널/이상 탐지 설정 등 편집 모드 입력 시 dirty=true
  const [isDirty, setIsDirty] = useState(false);
  const markDirty = useCallback(() => setIsDirty(true), []);

  // 이탈 확인 다이얼로그 상태 — 뒤로가기/취소 클릭 시 dirty면 오픈 (이슈 #59)
  const [leaveDialogOpen, setLeaveDialogOpen] = useState(false);
  // 이탈 다이얼로그 확인 후 실행할 동작 — 'back'(목록 이동) 또는 'cancel-edit'(편집 취소)
  const [leaveAction, setLeaveAction] = useState<'back' | 'cancel-edit'>('back');

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

  // 브라우저 탭 닫기·새로고침 시 이탈 경고 (이슈 #59 — #56/#57/#58과 동일 패턴)
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (isDirty) e.preventDefault();
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [isDirty]);

  // 뒤로가기 버튼 클릭 핸들러 — dirty면 다이얼로그 표시, 아니면 즉시 이동 (이슈 #59)
  const handleBackClick = () => {
    if (isDirty) {
      setLeaveAction('back');
      setLeaveDialogOpen(true);
    } else {
      navigate('/ai-insights/jobs');
    }
  };

  // 편집 취소 본 동작 — 폼 초기화 후 읽기 모드로 복귀 (이슈 #59)
  const performCancelEdit = useCallback(() => {
    if (job) {
      form.reset(buildDefaultValues(job));
    }
    setIsEditing(false);
    setIsDirty(false);
  }, [form, job]);

  // 취소 버튼 클릭 — dirty면 다이얼로그 표시, 아니면 즉시 취소 (이슈 #59)
  const handleCancelEdit = () => {
    if (isDirty) {
      setLeaveAction('cancel-edit');
      setLeaveDialogOpen(true);
    } else {
      performCancelEdit();
    }
  };

  // 다이얼로그에서 '이탈' 클릭 — leaveAction에 따라 분기 (이슈 #59)
  const handleLeaveConfirm = () => {
    setLeaveDialogOpen(false);
    setIsDirty(false);
    if (leaveAction === 'back') {
      navigate('/ai-insights/jobs');
    } else {
      performCancelEdit();
    }
  };

  const handleSave = form.handleSubmit(async (values) => {
    const payload = {
      name: values.name,
      prompt: values.prompt,
      templateId: values.templateId ?? null,
      cronExpression: values.cronExpression ?? '',
      timezone: values.timezone,
      triggerType: values.triggerType,
      config: values.config,
    };

    if (isNew) {
      createMutation.mutate(payload, {
        onSuccess: (created) => {
          toast.success('작업이 생성되었습니다.');
          // 저장 성공 → dirty 해제 후 신규 작업 상세로 이동 (이슈 #59)
          setIsDirty(false);
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
            // 저장 성공 → dirty 해제 후 읽기 모드로 복귀 (이슈 #59)
            setIsDirty(false);
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
  // 더블클릭 중복 실행 방지용 ref — disabled 상태 전파 지연 대응 (#49)
  const isExecutingRef = useRef(false);

  const handleExecuteSafe = () => {
    if (isExecutingRef.current) return;
    isExecutingRef.current = true;
    handleExecute();
    setTimeout(() => { isExecutingRef.current = false; }, 1000);
  };

  if (!isNew && isLoading) {
    return (
      <div className="space-y-6">
        <div className="h-8 w-64 bg-muted animate-pulse rounded" />
        <div className="h-96 bg-muted animate-pulse rounded" />
      </div>
    );
  }

  // 존재하지 않는 작업 ID 접근 시 에러 상태 처리 (#47)
  if (!isNew && isError) {
    return (
      <div className="space-y-6">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate('/ai-insights/jobs')} aria-label="목록으로">
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">작업을 찾을 수 없습니다</h1>
        </div>
        <p className="text-muted-foreground text-sm">요청하신 작업이 존재하지 않거나 삭제되었습니다.</p>
        <Button variant="outline" size="sm" onClick={() => navigate('/ai-insights/jobs')}>
          <ArrowLeft className="h-4 w-4 mr-1" />
          목록으로 돌아가기
        </Button>
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
            onClick={handleBackClick}
            aria-label="목록으로"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">
              {isNew ? '새 스마트 작업' : (job?.name ?? '-')}
            </h1>
            <div className="flex items-center gap-2 mt-1">
              {!isNew && job && (
                <Badge variant={job.enabled ? 'default' : 'secondary'}>
                  {job.enabled ? '활성' : '비활성'}
                </Badge>
              )}
              {/* 미저장 변경사항 표시 — #56/#57/#58과 동일 시각 패턴 (이슈 #59) */}
              {isDirty && (
                <span className="text-xs text-muted-foreground flex items-center gap-1">
                  <span className="text-muted-foreground">●</span>
                  미저장 변경사항
                </span>
              )}
            </div>
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
                onClick={handleExecuteSafe}
                disabled={executeMutation.isPending}
              >
                지금 실행
              </Button>
              <Button variant="outline" size="sm" onClick={() => setIsEditing(true)}>
                편집
              </Button>
              {/* 삭제 확인 다이얼로그 (#37) */}
              <DeleteConfirmDialog
                entityName="스마트 작업"
                itemName={job?.name ?? ''}
                onConfirm={handleDelete}
                trigger={
                  <Button
                    variant="destructive"
                    size="sm"
                    disabled={deleteMutation.isPending}
                  >
                    삭제
                  </Button>
                }
              />
            </>
          )}
          {isEditing && (
            <>
              {!isNew && (
                <Button variant="outline" size="sm" onClick={handleCancelEdit}>
                  취소
                </Button>
              )}
              {/* 폼이 유효하지 않으면(필수 필드 미입력 등) 저장/생성 버튼 비활성화 — #52 */}
              <Button size="sm" onClick={handleSave} disabled={isSaving || !form.formState.isValid}>
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
            onChange={markDirty}
          />
        </TabsContent>

        <TabsContent value="monitoring">
          <JobMonitoringTab
            form={form}
            isEditing={isEditing}
            jobId={jobId}
            onChange={markDirty}
          />
        </TabsContent>

        {!isNew && (
          <TabsContent value="executions">
            <JobExecutionsTab jobId={jobId} />
          </TabsContent>
        )}
      </Tabs>

      {/*
        이탈 확인 다이얼로그 — 뒤로가기/취소 클릭 시 dirty면 표시 (이슈 #59)
        취소 시 머무름, 확인 시 변경사항 버리고 이탈/취소 동작 실행
      */}
      <AlertDialog open={leaveDialogOpen} onOpenChange={setLeaveDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>저장하지 않은 변경사항</AlertDialogTitle>
            <AlertDialogDescription>
              저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={handleLeaveConfirm}>이탈</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
