import { client } from './client';

export interface ReportTemplate {
  id: number;
  name: string;
  description: string | null;
  sections: TemplateSection[];
  style: string | null;
  builtin: boolean;
  createdAt: string;
  updatedAt: string;
}

export type SectionType =
  | 'text'
  | 'cards'
  | 'list'
  | 'table'
  | 'comparison'
  | 'alert'
  | 'timeline'
  | 'chart'
  | 'recommendation'
  | 'group'
  | 'divider';

export interface TemplateSection {
  key: string;
  type: SectionType;
  label: string;
  description?: string;     // UI 가이드용 (AI에 미전달)
  instruction?: string;     // AI에 전달되는 섹션별 지시
  required?: boolean;
  static?: boolean;         // true이면 AI가 채우지 않는 정적 콘텐츠
  content?: string;         // 정적 섹션의 고정 텍스트 (변수 치환 지원)
  children?: TemplateSection[];  // 하위 섹션 (group 타입만)
}

// === Anomaly Detection Types ===

export type TriggerType = 'SCHEDULE' | 'ANOMALY' | 'BOTH';

export type MetricSource = 'system' | 'dataset';
export type Sensitivity = 'low' | 'medium' | 'high';

export interface AnomalyMetricConfig {
  id: string;
  name: string;
  source: MetricSource;
  metricKey?: string;       // for system metrics
  datasetId?: number;       // for dataset metrics
  query?: string;           // for dataset metrics
  pollingInterval: number;  // seconds
}

export interface AnomalyConfig {
  enabled: boolean;
  metrics: AnomalyMetricConfig[];
  sensitivity: Sensitivity;
  cooldownMinutes: number;
}

export const SYSTEM_METRICS = [
  { key: 'pipeline_failure_rate', label: '파이프라인 실패율' },
  { key: 'pipeline_execution_count', label: '파이프라인 실행 건수' },
  { key: 'dataset_total_count', label: '데이터셋 수' },
  { key: 'active_user_count', label: '활성 사용자 수' },
] as const;

/** 이상 탐지 이벤트 이력 레코드 — 백엔드 /proactive/jobs/:id/anomaly-events 응답 */
export interface AnomalyEventRecord {
  id: number;
  jobId: number;
  metricId: string;
  metricName: string;
  currentValue: number;
  mean: number;
  stddev: number;
  deviation: number;
  sensitivity: string;
  detectedAt: string;
}

export interface ProactiveJobExecution {
  id: number;
  jobId: number;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  result: Record<string, unknown> | null;
  deliveredChannels: string[];
  errorMessage: string | null;
  startedAt: string;
  completedAt: string | null;
}

export interface ProactiveJob {
  id: number;
  userId: number;
  templateId: number | null;
  templateName: string | null;
  name: string;
  prompt: string;
  cronExpression: string;
  timezone: string;
  enabled: boolean;
  triggerType?: TriggerType;
  config: Record<string, unknown>;
  lastExecutedAt: string | null;
  nextExecuteAt: string | null;
  createdAt: string;
  updatedAt: string;
  lastExecution: ProactiveJobExecution | null;
}

export interface ProactiveMessage {
  id: number;
  userId: number;
  /** 실행이 속한 잡 ID — execution JOIN으로 채워지며, executionId가 없으면 null */
  jobId: number | null;
  executionId: number | null;
  jobName: string | null;
  title: string;
  content: Record<string, unknown>;
  messageType: string;
  read: boolean;
  createdAt: string;
}

export interface RecipientResponse {
  userId: number;
  name: string;
  email: string;
}

export interface CreateProactiveJobRequest {
  name: string;
  prompt: string;
  templateId?: number | null;
  cronExpression: string;
  timezone?: string;
  triggerType?: TriggerType;
  config?: Record<string, unknown>;
}

export interface UpdateProactiveJobRequest {
  name?: string;
  prompt?: string;
  templateId?: number | null;
  cronExpression?: string;
  timezone?: string;
  enabled?: boolean;
  triggerType?: TriggerType;
  config?: Record<string, unknown>;
}

export interface CreateReportTemplateRequest {
  name: string;
  description?: string;
  sections: TemplateSection[];
  style?: string;
}

export interface UpdateReportTemplateRequest {
  name?: string;
  description?: string;
  sections?: TemplateSection[];
  style?: string;
}

/** 전역 리포트 목록의 행 1건. 본문(htmlContent)은 포함되지 않는다 — 뷰어에서 별도 조회한다. */
export interface ReportListItem {
  executionId: number;
  jobId: number;
  jobName: string;
  title: string;
  summary: string | null;
  completedAt: string;
}

export const proactiveApi = {
  // Jobs (6 methods)
  getJobs: () => client.get<ProactiveJob[]>('/proactive/jobs'),
  getJob: (id: number) => client.get<ProactiveJob>(`/proactive/jobs/${id}`),
  createJob: (data: CreateProactiveJobRequest) =>
    client.post<ProactiveJob>('/proactive/jobs', data),
  updateJob: (id: number, data: UpdateProactiveJobRequest) =>
    client.put<ProactiveJob>(`/proactive/jobs/${id}`, data),
  deleteJob: (id: number) => client.delete(`/proactive/jobs/${id}`),
  executeJob: (id: number) =>
    client.post<ProactiveJobExecution>(`/proactive/jobs/${id}/execute`),
  getJobExecutions: (jobId: number, params?: { limit?: number; offset?: number }) =>
    client.get<ProactiveJobExecution[]>(`/proactive/jobs/${jobId}/executions`, { params }),
  /** 특정 작업의 이상 탐지 이벤트 이력 조회 */
  getAnomalyEvents: (jobId: number, limit = 20) =>
    client.get<AnomalyEventRecord[]>(`/proactive/jobs/${jobId}/anomaly-events`, {
      params: { limit },
    }),
  /** 단건 실행 조회 — 실행 상세 페이지용 */
  getExecution: (jobId: number, executionId: number) =>
    client.get<ProactiveJobExecution>(`/proactive/jobs/${jobId}/executions/${executionId}`),
  searchRecipients: (search?: string) =>
    client.get<RecipientResponse[]>('/proactive/jobs/recipients', { params: { search } }),
  /**
   * ID로 수신자 정보를 벌크 조회한다 (#555).
   * UserCombobox가 이미 저장된 selectedUserIds를 마운트 시 이름/이메일로 되살릴 때 사용 —
   * 로컬 검색 캐시(userCache)는 검색을 거쳐야만 채워지므로 검색 없이 직접 조회가 필요하다.
   */
  getRecipientsByIds: (userIds: number[]) =>
    client.get<RecipientResponse[]>('/proactive/jobs/recipients', { params: { userIds } }),

  // Messages (3 methods)
  // #520: unreadOnly=true 로 서버 사이드에서 안 읽은 알림만 필터링해서 받을 수 있다.
  getMessages: (params?: { limit?: number; offset?: number; unreadOnly?: boolean }) =>
    client.get<ProactiveMessage[]>('/proactive/messages', { params }),
  getUnreadCount: () =>
    client.get<{ count: number }>('/proactive/messages/unread-count'),
  markAsRead: (id: number) =>
    client.put(`/proactive/messages/${id}/read`),
  markAllAsRead: () => client.put('/proactive/messages/read-all'),

  // Templates (4 methods)
  getTemplates: () => client.get<ReportTemplate[]>('/proactive/templates'),
  getTemplate: (id: number) =>
    client.get<ReportTemplate>(`/proactive/templates/${id}`),
  createTemplate: (data: CreateReportTemplateRequest) =>
    client.post<ReportTemplate>('/proactive/templates', data),
  updateTemplate: (id: number, data: UpdateReportTemplateRequest) =>
    client.put<ReportTemplate>(`/proactive/templates/${id}`, data),
  deleteTemplate: (id: number) =>
    client.delete(`/proactive/templates/${id}`),

  // Executions
  downloadExecutionPdf: (jobId: number, executionId: number) =>
    client.get(`/proactive/jobs/${jobId}/executions/${executionId}/pdf`, {
      responseType: 'blob',
    }),
  // HTML 리포트 조회 — 뷰어 페이지에서 sanitize 후 렌더링
  getExecutionHtml: (jobId: number, executionId: number) =>
    client.get<string>(`/proactive/jobs/${jobId}/executions/${executionId}/html`),
  /** 잡 횡단 리포트 목록 — 사이드바 "리포트" 화면용 */
  listReports: (params?: { limit?: number; offset?: number }) =>
    client.get<ReportListItem[]>('/proactive/reports', { params }),

  // SMTP 는 연결 테스트 하나만 남았다. 저장(PUT /settings/smtp)은 P7-b 에서, 조회(GET
  // /settings/smtp)는 P7-c1 에서 사라졌다 — 조회는 해석기를 타지 않아 테넌트 오버라이드가 있어도
  // 플랫폼 값을 돌려줬다. 화면은 이제 `settingsApi.getByPrefix('smtp')` 로 해석된 값을 읽는다.
  // 연결 테스트는 값을 노출하지 않는 진단 액션이라 유지한다.
  testSmtpSettings: () => client.post('/settings/smtp/test'),
};
