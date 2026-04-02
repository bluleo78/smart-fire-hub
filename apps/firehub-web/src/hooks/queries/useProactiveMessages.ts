import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import type {
  CreateProactiveJobRequest,
  CreateReportTemplateRequest,
  ProactiveJob,
  SmtpSettingsRequest,
  UpdateProactiveJobRequest,
  UpdateReportTemplateRequest,
} from '../../api/proactive';
import { proactiveApi } from '../../api/proactive';

const KEYS = {
  jobs: ['proactive', 'jobs'] as const,
  job: (id: number) => ['proactive', 'jobs', id] as const,
  executions: (jobId: number) => ['proactive', 'executions', jobId] as const,
  recipients: (search: string) => ['proactive', 'recipients', search] as const,
  messages: ['proactive', 'messages'] as const,
  unreadCount: ['proactive', 'unread-count'] as const,
  templates: ['proactive', 'templates'] as const,
  template: (id: number) => ['proactive', 'templates', id] as const,
  smtp: ['proactive', 'smtp'] as const,
};

// ── Jobs ──────────────────────────────────────────────────────────────────────

export function useProactiveJobs() {
  return useQuery({
    queryKey: KEYS.jobs,
    queryFn: () => proactiveApi.getJobs().then((r) => r.data),
  });
}

export function useProactiveJob(id: number) {
  return useQuery({
    queryKey: KEYS.job(id),
    queryFn: () => proactiveApi.getJob(id).then((r) => r.data),
    enabled: !!id,
  });
}

export function useCreateProactiveJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateProactiveJobRequest) =>
      proactiveApi.createJob(data).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: KEYS.jobs });
    },
  });
}

export function useUpdateProactiveJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateProactiveJobRequest }) =>
      proactiveApi.updateJob(id, data).then((r) => r.data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: KEYS.jobs });
      queryClient.invalidateQueries({ queryKey: KEYS.job(variables.id) });
    },
  });
}

export function useDeleteProactiveJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => proactiveApi.deleteJob(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: KEYS.jobs });
    },
  });
}

export function useExecuteProactiveJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => proactiveApi.executeJob(id).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: KEYS.jobs });
    },
  });
}

export function useCloneProactiveJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (job: Pick<ProactiveJob, 'name' | 'prompt' | 'templateId' | 'cronExpression' | 'timezone' | 'config'>) =>
      proactiveApi.createJob({
        name: `${job.name} (복사본)`,
        prompt: job.prompt,
        templateId: job.templateId,
        cronExpression: job.cronExpression,
        timezone: job.timezone,
        config: job.config,
      }).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: KEYS.jobs });
    },
  });
}

export function useJobExecutions(
  jobId: number,
  params?: { limit?: number; offset?: number },
  options?: { refetchInterval?: number | false },
) {
  return useQuery({
    queryKey: [...KEYS.executions(jobId), params],
    queryFn: () => proactiveApi.getJobExecutions(jobId, params).then((r) => r.data),
    enabled: !!jobId,
    refetchInterval: options?.refetchInterval,
  });
}

export function useRecipientSearch(search: string) {
  return useQuery({
    queryKey: KEYS.recipients(search),
    queryFn: () => proactiveApi.searchRecipients(search).then((r) => r.data),
    enabled: search.length > 0,
  });
}

// ── Messages ──────────────────────────────────────────────────────────────────

export function useProactiveMessages(params?: { limit?: number; offset?: number }) {
  return useQuery({
    queryKey: KEYS.messages,
    queryFn: () => proactiveApi.getMessages(params).then((r) => r.data),
  });
}

export function useUnreadCount() {
  return useQuery({
    queryKey: KEYS.unreadCount,
    queryFn: () => proactiveApi.getUnreadCount().then((r) => r.data.count),
    refetchInterval: 60_000,
  });
}

export function useMarkAsRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => proactiveApi.markAsRead(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: KEYS.messages });
      queryClient.invalidateQueries({ queryKey: KEYS.unreadCount });
    },
  });
}

export function useMarkAllAsRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => proactiveApi.markAllAsRead(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: KEYS.messages });
      queryClient.invalidateQueries({ queryKey: KEYS.unreadCount });
    },
  });
}

// ── Templates ─────────────────────────────────────────────────────────────────

export function useProactiveTemplates() {
  return useQuery({
    queryKey: KEYS.templates,
    queryFn: () => proactiveApi.getTemplates().then((r) => r.data),
  });
}

export function useProactiveTemplate(id: number) {
  return useQuery({
    queryKey: KEYS.template(id),
    queryFn: () => proactiveApi.getTemplate(id).then((r) => r.data),
    enabled: !!id,
  });
}

export function useCreateProactiveTemplate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateReportTemplateRequest) =>
      proactiveApi.createTemplate(data).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: KEYS.templates });
    },
  });
}

export function useUpdateProactiveTemplate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateReportTemplateRequest }) =>
      proactiveApi.updateTemplate(id, data).then((r) => r.data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: KEYS.templates });
      queryClient.invalidateQueries({ queryKey: KEYS.template(variables.id) });
    },
  });
}

export function useDeleteProactiveTemplate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => proactiveApi.deleteTemplate(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: KEYS.templates });
    },
  });
}

// ── SMTP ──────────────────────────────────────────────────────────────────────

export function useSmtpSettings() {
  return useQuery({
    queryKey: KEYS.smtp,
    queryFn: () => proactiveApi.getSmtpSettings().then((r) => r.data),
  });
}

export function useUpdateSmtpSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: SmtpSettingsRequest) =>
      proactiveApi.updateSmtpSettings(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: KEYS.smtp });
    },
  });
}

export function useTestSmtpSettings() {
  return useMutation({
    mutationFn: () => proactiveApi.testSmtpSettings(),
  });
}
