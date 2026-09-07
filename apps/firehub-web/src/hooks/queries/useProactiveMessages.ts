import { keepPreviousData,useMutation, useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query';

import type {
  CreateProactiveJobRequest,
  CreateReportTemplateRequest,
  ProactiveJob,
  ProactiveJobExecution,
  ReportListItem,
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
  anomalyEvents: (jobId: number) => ['proactive', 'anomaly-events', jobId] as const,
  reports: (params?: { limit?: number; offset?: number }) =>
    ['proactive', 'reports', params] as const,
};

// ── Jobs ──────────────────────────────────────────────────────────────────────

/**
 * 스마트 작업 목록 조회.
 *
 * options.enabled 로 조회를 미룰 수 있다 — 리포트 목록처럼 "잡이 하나라도 있는가"를
 * 빈 상태에서만 알면 되는 화면이 불필요한 요청을 내지 않도록.
 */
export function useProactiveJobs(options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: KEYS.jobs,
    queryFn: () => proactiveApi.getJobs().then((r) => r.data),
    enabled: options?.enabled ?? true,
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
    onSuccess: (_data, id) => {
      queryClient.invalidateQueries({ queryKey: KEYS.jobs });
      // 실행 직후 실행 이력 목록을 즉시 갱신하여 "실행 중" 상태가 바로 보이도록 한다
      queryClient.invalidateQueries({ queryKey: KEYS.executions(id) });
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
  options?: {
    // number | false | 함수형 모두 수용 — RUNNING 상태 기반 동적 폴링 지원
    refetchInterval?: UseQueryOptions<ProactiveJobExecution[]>['refetchInterval'];
  },
) {
  return useQuery<ProactiveJobExecution[]>({
    queryKey: [...KEYS.executions(jobId), params],
    queryFn: () => proactiveApi.getJobExecutions(jobId, params).then((r) => r.data),
    enabled: !!jobId,
    refetchInterval: options?.refetchInterval,
  });
}

/**
 * 잡 횡단 리포트 목록 조회 — 리포트 화면의 목록 탭에서 사용.
 *
 * params가 쿼리 키에 포함되므로 "더 보기"로 limit을 늘리면 새 캐시 엔트리가 된다.
 * keepPreviousData 없이는 그 순간 data가 undefined가 되어 이미 보고 있던 행들이
 * 스켈레톤으로 바뀐다 — 목록이 깜빡이지 않도록 이전 데이터를 유지한다.
 */
export function useReports(params?: { limit?: number; offset?: number }) {
  return useQuery<ReportListItem[]>({
    placeholderData: keepPreviousData,
    queryKey: KEYS.reports(params),
    queryFn: () => proactiveApi.listReports(params).then((r) => r.data),
  });
}

/**
 * 단건 실행 조회 훅 — 실행 상세 페이지에서 사용.
 * RUNNING 상태일 때 5초 간격으로 자동 폴링하여 완료를 감지한다.
 */
export function useExecution(jobId: number, executionId: number) {
  return useQuery({
    queryKey: [...KEYS.executions(jobId), executionId],
    queryFn: () => proactiveApi.getExecution(jobId, executionId).then((r) => r.data),
    enabled: !!jobId && !!executionId,
    refetchInterval: (query) => (query.state.data?.status === 'RUNNING' ? 5000 : false),
  });
}

export function useRecipientSearch(search: string) {
  return useQuery({
    queryKey: KEYS.recipients(search),
    queryFn: () => proactiveApi.searchRecipients(search).then((r) => r.data),
    enabled: search.length > 0,
  });
}

/** 특정 작업의 이상 탐지 이벤트 이력을 조회한다 */
export function useAnomalyEvents(jobId: number) {
  return useQuery({
    queryKey: KEYS.anomalyEvents(jobId),
    queryFn: () => proactiveApi.getAnomalyEvents(jobId).then((r) => r.data),
    enabled: !!jobId,
  });
}

// ── Messages ──────────────────────────────────────────────────────────────────

/**
 * 알림 목록 조회 (#351).
 *
 * queryKey에 params를 포함한다 — 예전에는 `['proactive','messages']` 고정이라
 * 서로 다른 limit을 쓰는 호출자가 생기면 같은 캐시를 덮어써 잘못된 페이지를 보게 된다.
 * 무효화(`markAsRead` 등)는 prefix `['proactive','messages']`로 걸리므로 여전히 전부 갱신된다.
 */
export function useProactiveMessages(params?: {
  limit?: number;
  offset?: number;
  unreadOnly?: boolean;
}) {
  return useQuery({
    queryKey: [...KEYS.messages, params ?? null] as const,
    queryFn: () => proactiveApi.getMessages(params).then((r) => r.data),
    // "더 보기"로 limit이 늘면 새 queryKey라 캐시가 비어 목록이 잠깐 빈 상태로 깜빡인다.
    // 이전 페이지를 유지해 리스트가 끊기지 않게 한다.
    placeholderData: keepPreviousData,
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

// SMTP 조회·저장 훅은 없다. 두 조작 모두 SMTP 전용 엔드포인트가 아니라 <b>일반 설정 경로</b>를
// 쓰기 때문이다(P7-c1): 읽기는 `settingsApi.getByPrefix('smtp')`, 저장은 `settingsApi.update`
// (PUT /settings) 다. SMTP 전용 읽기/쓰기 경로를 되살리면 화이트리스트·마스킹·오버라이드 해석이
// 두 벌이 되고, 실제로 그 두 벌 때문에 "메일은 테넌트 값으로 나가는데 화면은 플랫폼 값"이 생겼다.

export function useTestSmtpSettings() {
  return useMutation({
    mutationFn: () => proactiveApi.testSmtpSettings(),
  });
}
