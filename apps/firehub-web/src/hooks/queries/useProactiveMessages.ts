import { keepPreviousData,useMutation, useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query';
import { useCallback } from 'react';

import type {
  CreateProactiveJobRequest,
  CreateReportTemplateRequest,
  ProactiveJob,
  ProactiveJobExecutionSummary,
  ReportListItem,
  UpdateProactiveJobRequest,
  UpdateReportTemplateRequest,
} from '../../api/proactive';
import { proactiveApi } from '../../api/proactive';
import { useMyPermissions } from './useMyPermissions';

const KEYS = {
  jobs: ['proactive', 'jobs'] as const,
  // 워크스페이스 전체(관리자) 목록 — 'jobs' 접두사 아래에 두어 내 작업 변경 시 함께 무효화된다
  workspaceJobs: ['proactive', 'jobs', 'workspace'] as const,
  job: (id: number) => ['proactive', 'jobs', id] as const,
  executions: (jobId: number) => ['proactive', 'executions', jobId] as const,
  recipients: (search: string) => ['proactive', 'recipients', search] as const,
  recipientsByIds: (userIds: number[]) => ['proactive', 'recipients', 'byIds', userIds] as const,
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
 * 스마트 작업·리포트 양식을 만들거나 바꿀 수 있는가 (WD-133).
 *
 * 생성·수정·삭제·지금 실행은 서버가 proactive:write 로 막는다. 권한이 없는데 버튼을 보여 주면 폼을 다 채운 뒤에야
 * 403 을 받으므로 화면은 이 값으로 진입점을 숨긴다. 서버에 관리자 우회가 없으므로(ADMIN 역할이 권한을 가짐) 권한 코드만 본다.
 */
export function useCanWriteProactive() {
  const { permissions, isLoading } = useMyPermissions();
  return { canWrite: permissions.has('proactive:write'), isLoading };
}

/**
 * 워크스페이스 전체 스마트 작업을 보고 끄거나 삭제할 수 있는가 (WD-192, proactive:admin).
 * 이 권한으로도 남의 작업 열람·편집·켜기·지금 실행은 할 수 없다(서버가 막는다) — 화면은 「워크스페이스 전체」 보기 노출에만 쓴다.
 */
export function useCanAdminProactive() {
  const { permissions, isLoading } = useMyPermissions();
  return { canAdmin: permissions.has('proactive:admin'), isLoading };
}

/** 실행 중 폴링 간격 — 실행 이력 탭(JobExecutionsTab)과 같은 5초. */
const RUN_POLL_MS = 5000;

/**
 * 최근 실행이 아직 끝나지 않았는가.
 *
 * 「지금 실행」은 비동기라 직후 조회에는 실행 중 상태가 실린다. 끝날 때까지 다시 읽지 않으면
 * 목록의 실패 표시와 상세 「마지막 실행 상태」가 실행 중이나 이전 결과로 멈춰 있게 된다 (WD-134).
 */
function isRunInFlight(job: ProactiveJob): boolean {
  return job.lastExecution?.status === 'RUNNING';
}

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
    refetchInterval: (query) =>
      query.state.data?.some((job) => isRunInFlight(job)) ? RUN_POLL_MS : false,
  });
}

export function useProactiveJob(id: number) {
  return useQuery({
    queryKey: KEYS.job(id),
    queryFn: () => proactiveApi.getJob(id).then((r) => r.data),
    enabled: !!id,
    refetchInterval: (query) => (query.state.data && isRunInFlight(query.state.data) ? RUN_POLL_MS : false),
  });
}

/**
 * 편집 충돌의 「다시 불러오기」용(WD-364) — 캐시를 무시하고 최신 작업을 서버에서 받아 캐시도 그 값으로 바꾼다.
 * 화면은 돌려받은 값으로 폼과 기준 버전을 직접 채운다(캐시 갱신만으로는 같은 내용일 때 폼이 다시 채워지지 않는다).
 */
export function useFetchLatestProactiveJob() {
  const queryClient = useQueryClient();
  return useCallback(
    (id: number) =>
      queryClient.fetchQuery({
        queryKey: KEYS.job(id),
        queryFn: () => proactiveApi.getJob(id).then((r) => r.data),
        staleTime: 0,
      }),
    [queryClient],
  );
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
    onSuccess: (data, variables) => {
      // 캐시의 수정 시각을 응답의 새 값으로 먼저 바꾼다(WD-364) — 상세 화면은 이 값을 다음 저장의 기준 버전으로 쓴다.
      // 재조회가 끝나기 전에 다시 고쳐 저장하면 낡은 기준으로 자기 저장과 충돌한다. 본문이 없으면(옛 서버·모킹) 그대로 둔다.
      const updatedAt = data?.updatedAt;
      if (updatedAt) {
        queryClient.setQueryData<ProactiveJob>(KEYS.job(variables.id), (old) => (old ? { ...old, updatedAt } : old));
      }
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

/**
 * 내 작업 끄기 (WD-192) — 쓰기 권한이 없는 작성자도 쓸 수 있도록 PUT(쓰기 권한) 대신 끄기 전용 엔드포인트를 쓴다.
 * 쓰기 권한이 있는 사람도 같은 경로로 끈다 — 끄기 경로를 하나로 둬 권한별 분기를 없앤다.
 */
export function useDisableProactiveJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => proactiveApi.disableJob(id),
    onSuccess: (_data, id) => {
      queryClient.invalidateQueries({ queryKey: KEYS.jobs });
      queryClient.invalidateQueries({ queryKey: KEYS.job(id) });
    },
  });
}

/** 워크스페이스 전체 스마트 작업 목록 (WD-192, proactive:admin 전용) — 권한이 있을 때만 enabled 로 부른다. */
export function useWorkspaceProactiveJobs(options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: KEYS.workspaceJobs,
    queryFn: () => proactiveApi.getWorkspaceJobs().then((r) => r.data),
    enabled: options?.enabled ?? true,
  });
}

/** 관리자 끄기 (WD-192) — 남의 작업이라도 끈다. 켜기는 없다. */
export function useAdminDisableProactiveJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => proactiveApi.adminDisableJob(id),
    onSuccess: () => {
      // 내 작업 목록·상세에도 같은 작업이 있을 수 있어 jobs 접두사 전체를 무효화한다
      queryClient.invalidateQueries({ queryKey: KEYS.jobs });
    },
  });
}

/** 관리자 삭제 (WD-192) — 남의 작업이라도 삭제한다. */
export function useAdminDeleteProactiveJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => proactiveApi.adminDeleteJob(id),
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

/**
 * 복제 성공 안내 — 복사본은 꺼진 채 만들어지므로(WD-300) 켜는 곳(상세에서는 켤 수 없고 목록의 스위치)을 함께 알린다.
 * 상세·목록 두 화면이 같은 문구를 쓰도록 한곳에 둔다. 예약·이상 탐지 모두 꺼져 있으므로 「예약」으로 한정하지 않는다.
 */
export function cloneSuccessMessage(name: string) {
  return `"${name}" 작업을 꺼진 상태로 복제했습니다. 확인한 뒤 작업 목록에서 켜세요.`;
}

export function useCloneProactiveJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (job: Pick<ProactiveJob, 'name' | 'prompt' | 'templateId' | 'cronExpression' | 'timezone' | 'triggerType' | 'config'>) =>
      proactiveApi.createJob({
        name: `${job.name} (복사본)`,
        prompt: job.prompt,
        templateId: job.templateId,
        cronExpression: job.cronExpression,
        timezone: job.timezone,
        // triggerType을 넘기지 않으면 백엔드 기본값(SCHEDULE)으로 복제돼 원본이 이상 탐지
        // 트리거였어도 복사본은 조용히 스케줄 트리거로 바뀌는 문제가 있었다 (#655 관련 수정)
        triggerType: job.triggerType,
        config: job.config,
        // 복사본은 원본 상태와 상관없이 늘 꺼진 채로 만든다 — 기본값(활성)으로 만들면 원본과 같은 시각·같은
        // 수신자로 곧바로 예약돼, 편집하다 취소한 복제 한 번으로 같은 리포트가 두 번 발송된다 (WD-300)
        enabled: false,
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
    refetchInterval?: UseQueryOptions<ProactiveJobExecutionSummary[]>['refetchInterval'];
  },
) {
  return useQuery<ProactiveJobExecutionSummary[]>({
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

/**
 * ID로 수신자 정보를 벌크 조회한다 (#555).
 *
 * UserCombobox가 마운트 시 이미 채워진 selectedUserIds를 이름/이메일로 하이드레이션할 때 사용 —
 * 검색 결과로만 채워지는 로컬 캐시(userCache)와 달리 검색 없이 ID만으로 조회한다.
 */
export function useRecipientsByIds(userIds: number[]) {
  return useQuery({
    queryKey: KEYS.recipientsByIds(userIds),
    queryFn: () => proactiveApi.getRecipientsByIds(userIds).then((r) => r.data),
    enabled: userIds.length > 0,
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

// #632: 응답 타입이 ReportTemplate[] → ReportTemplateSummary[]로 바뀌었다(sections/style 제외).
// 이 훅을 쓰는 4개 화면 중 상세 구조가 필요한 ReportTemplateDetailPage는 별도로
// useProactiveTemplate(id)(단건 조회)를 함께 사용한다.
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

/**
 * 편집 충돌의 「다시 불러오기」용(WD-364) — 캐시를 무시하고 최신 양식을 서버에서 받아 캐시도 그 값으로 바꾼다.
 * 화면은 돌려받은 값으로 폼·섹션·기준 버전을 직접 채운다(편집 중에는 캐시가 바뀌어도 섹션을 다시 채우지 않는다).
 */
export function useFetchLatestProactiveTemplate() {
  const queryClient = useQueryClient();
  return useCallback(
    (id: number) =>
      queryClient.fetchQuery({
        queryKey: KEYS.template(id),
        queryFn: () => proactiveApi.getTemplate(id).then((r) => r.data),
        staleTime: 0,
      }),
    [queryClient],
  );
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
// (PUT /settings) 다. 폼 상태는 `useSmtpSettingsForm` 이 갖는다. SMTP 전용 읽기/쓰기 경로를
// 되살리면 화이트리스트·마스킹 규칙이 두 벌이 되고, 실제로 그 두 벌 때문에 "메일이 나가는 값과
// 화면이 보여주는 값이 다른" 어긋남이 생긴 전례가 있다.

export function useTestSmtpSettings() {
  return useMutation({
    mutationFn: () => proactiveApi.testSmtpSettings(),
  });
}
