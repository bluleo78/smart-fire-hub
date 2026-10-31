import type { SecurityLevelSummary } from '../types/security-level';
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

/**
 * 리포트 양식 목록(list) 조회 전용 요약 타입 (#632).
 * `ReportTemplate`과 달리 sections/style 전체 구조를 포함하지 않고 섹션 개수(sectionCount)만
 * 제공한다 — 목록 조회 payload가 템플릿/섹션 규모에 비례해 커지는 문제를 없애기 위함.
 * 상세 구조가 필요하면 `getTemplate(id)`(단건 조회)를 사용해야 한다.
 */
export interface ReportTemplateSummary {
  id: number;
  name: string;
  description: string | null;
  sectionCount: number;
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

/** 목표 기반 섹션 구성 생성 응답(WD-455) — 서버가 양식 섹션 검사(WD-298)를 통과시킨 구성만 담는다. */
export interface GenerateTemplateResponse {
  sections: TemplateSection[];
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
  /** 리포트 등급(WD-198) — 실행 동안 읽은 데이터셋의 최고 등급. 없으면(null) 등급 데이터를 읽지 않았거나 이전 리포트. */
  securityLevel?: SecurityLevelSummary | null;
}

/**
 * 실행 이력 목록(GET /proactive/jobs/{jobId}/executions)의 행 1건 — 경량 뷰 (#604).
 *
 * 백엔드 `ProactiveJobExecutionSummaryResponse`와 대응한다. `result`(리포트 본문)를 포함하지 않는다 —
 * 실행 건수가 많거나 리포트가 길면 목록 응답이 비대해지기 때문. 리포트 본문은 getExecution(단건, 위 타입)으로 조회한다.
 */
export interface ProactiveJobExecutionSummary {
  id: number;
  jobId: number;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  deliveredChannels: string[];
  errorMessage: string | null;
  startedAt: string;
  completedAt: string | null;
  /** 리포트 등급(WD-198) — 목록 배지용. */
  securityLevel?: SecurityLevelSummary | null;
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
  /**
   * 작성자가 지금 실행 자격이 있는가 (WD-192). 작성자가 정지·제거되거나 스마트 작업 쓰기 권한을 잃으면 작업은 「활성」인 채
   * 실행 때마다 건너뛴다(자동으로 꺼지지 않는다) — 화면은 false 일 때 「작성자 권한 없음 — 실행되지 않음」을 보인다.
   * 판정하지 않은 응답(구버전 서버 등)은 값이 없으므로 false 일 때만 안내한다.
   */
  ownerEligible?: boolean | null;
  /** 자격이 없는 사유 코드 — 계정·멤버십 비활성(OWNER_INACTIVE) / 쓰기 권한 없음(OWNER_NO_PERMISSION) */
  ownerIneligibleReason?: OwnerIneligibleReason | null;
}

/** 작성자 실행 자격이 없는 사유 코드 (WD-192, 서버 ProactiveOwnerEligibility.CODE_*) */
export type OwnerIneligibleReason = 'OWNER_INACTIVE' | 'OWNER_NO_PERMISSION';

/**
 * 워크스페이스 전체 스마트 작업 목록의 행 (WD-192, proactive:admin 전용).
 * 남의 작업 내용(프롬프트·설정·수신자·리포트)은 서버가 싣지 않는다 — 관리자는 끄기·삭제만 할 수 있다.
 */
export interface ProactiveJobAdminSummary {
  id: number;
  name: string;
  ownerName: string | null;
  enabled: boolean;
  ownerEligible: boolean;
  ownerIneligibleReason: OwnerIneligibleReason | null;
  nextExecuteAt: string | null;
  timezone: string | null;
  lastExecutionStatus: 'RUNNING' | 'COMPLETED' | 'FAILED' | null;
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
  /**
   * 이 메시지를 받은 내가 리포트 전문(실행 상세·html)을 열 수 있는가 (WD-162).
   * 서버는 작업 작성자에게만 리포트 전문을 열어 주므로, 작성자가 아닌 수신자에게는 false 다 —
   * 화면은 「리포트 보기」 대신 요약과 안내 문구를 보인다. 404 를 받고 나서 추측하지 않도록 서버가 사실을 실어 준다.
   */
  canViewReport: boolean;
  /**
   * 연결된 리포트의 등급이 내(수신자) 현재 보안 등급보다 높아 서버가 요약을 비우고 리포트 열기를 막았는가 (WD-198).
   * true 면 「현재 보안 등급으로 열 수 없는 리포트입니다」 안내를 보인다. 이전 서버 응답에는 없다.
   */
  reportLevelBlocked?: boolean;
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
  /** 생략하면 서버 기본값(활성). 복제처럼 예약을 바로 걸면 안 되는 생성은 false 로 보낸다 (WD-300) */
  enabled?: boolean;
}

export interface UpdateProactiveJobRequest {
  name?: string;
  prompt?: string;
  templateId?: number | null;
  /** true 면 templateId 가 없어도 지정된 템플릿을 해제한다 — 빠뜨리면 서버는 템플릿을 그대로 둔다 (WD-136) */
  clearTemplate?: boolean;
  cronExpression?: string;
  timezone?: string;
  enabled?: boolean;
  triggerType?: TriggerType;
  config?: Record<string, unknown>;
  /**
   * 편집을 시작할 때 읽은 상세의 updatedAt(WD-364 낙관적 잠금) — 서버 값과 다르면 409 EDIT_CONFLICT.
   * 서버가 준 문자열을 그대로 보낸다(Date 로 바꾸면 마이크로초가 잘려 매번 충돌한다). 빼면 비교하지 않는다(덮어쓰기).
   */
  expectedUpdatedAt?: string | null;
}

/** 스마트 작업 수정 응답 — 새 updatedAt 을 다음 저장의 기준 버전으로 쓴다(WD-364). */
export interface UpdateProactiveJobResponse {
  updatedAt: string | null;
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
  /**
   * 편집을 시작할 때 읽은 상세의 updatedAt(WD-364 낙관적 잠금) — 서버 값과 다르면 409 EDIT_CONFLICT.
   * 서버가 준 문자열을 그대로 보낸다(Date 로 바꾸면 마이크로초가 잘려 매번 충돌한다). 빼면 비교하지 않는다(덮어쓰기).
   */
  expectedUpdatedAt?: string | null;
}

/** 리포트 양식 수정 응답 — 새 updatedAt 을 다음 저장의 기준 버전으로 쓴다(WD-364). */
export interface UpdateReportTemplateResponse {
  updatedAt: string | null;
}

/** 전역 리포트 목록의 행 1건. 본문(htmlContent)은 포함되지 않는다 — 뷰어에서 별도 조회한다. */
export interface ReportListItem {
  executionId: number;
  jobId: number;
  jobName: string;
  title: string;
  summary: string | null;
  completedAt: string;
  /** 리포트 등급(WD-198) — 목록 배지용. 작성자의 현재 등급으로 열 수 없는 리포트는 summary 가 비어 온다. */
  securityLevel?: SecurityLevelSummary | null;
}

export const proactiveApi = {
  // Jobs (6 methods)
  getJobs: () => client.get<ProactiveJob[]>('/proactive/jobs'),
  getJob: (id: number) => client.get<ProactiveJob>(`/proactive/jobs/${id}`),
  createJob: (data: CreateProactiveJobRequest) =>
    client.post<ProactiveJob>('/proactive/jobs', data),
  updateJob: (id: number, data: UpdateProactiveJobRequest) =>
    // 응답은 새 수정 시각뿐이다(WD-364) — 작업 전체가 필요하면 getJob 으로 다시 읽는다
    client.put<UpdateProactiveJobResponse>(`/proactive/jobs/${id}`, data),
  deleteJob: (id: number) => client.delete(`/proactive/jobs/${id}`),
  /** 내 작업 끄기 — 쓰기 권한 없이 읽기 권한만으로 된다(WD-192). 켜기는 updateJob(쓰기 권한)으로만 한다. */
  disableJob: (id: number) => client.post(`/proactive/jobs/${id}/disable`),
  // 워크스페이스 관리(proactive:admin, WD-192) — 목록(최소 정보)·끄기·삭제만 있다
  getWorkspaceJobs: () => client.get<ProactiveJobAdminSummary[]>('/proactive/admin/jobs'),
  adminDisableJob: (id: number) => client.post(`/proactive/admin/jobs/${id}/disable`),
  adminDeleteJob: (id: number) => client.delete(`/proactive/admin/jobs/${id}`),
  executeJob: (id: number) =>
    client.post<ProactiveJobExecution>(`/proactive/jobs/${id}/execute`),
  getJobExecutions: (jobId: number, params?: { limit?: number; offset?: number }) =>
    client.get<ProactiveJobExecutionSummary[]>(`/proactive/jobs/${jobId}/executions`, { params }),
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
  // #632: 목록 조회 응답이 요약(ReportTemplateSummary[], sections/style 제외)으로 축소됐다.
  // page/size 미지정 시 서버 기본값(size=50)이 적용되어 현재 UI(전체 목록 렌더링)와 호환된다.
  // 응답 자체는 기존과 동일하게 바로 배열이다(엔벨로프 아님).
  getTemplates: (params?: { page?: number; size?: number }) =>
    client.get<ReportTemplateSummary[]>('/proactive/templates', { params }),
  getTemplate: (id: number) =>
    client.get<ReportTemplate>(`/proactive/templates/${id}`),
  createTemplate: (data: CreateReportTemplateRequest) =>
    client.post<ReportTemplate>('/proactive/templates', data),
  updateTemplate: (id: number, data: UpdateReportTemplateRequest) =>
    // 응답은 새 수정 시각뿐이다(WD-364) — 예전 타입(ReportTemplate)은 실제 응답(204 빈 본문)과 맞지 않았다
    client.put<UpdateReportTemplateResponse>(`/proactive/templates/${id}`, data),
  deleteTemplate: (id: number) =>
    client.delete(`/proactive/templates/${id}`),
  // 목표 기반 섹션 구성 AI 생성(WD-455). 저장하지 않고 구성만 돌려준다 — 저장·연결은 작업 생성 때(WD-297).
  // 화면이 무한정 멈추지 않게 상한을 둔다: 서버의 ai-agent 호출 상한(75초)보다 조금 길게 잡아 서버 쪽 사유가 먼저 오게 한다.
  generateTemplate: (question: string) =>
    client.post<GenerateTemplateResponse>('/proactive/templates/generate', { question }, { timeout: 90_000 }),

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

  // SMTP 는 연결 테스트 하나만 남았다. 전용 저장(PUT /settings/smtp)은 P7-b 에서, 전용 조회(GET
  // /settings/smtp)는 P7-c1 에서 사라졌다. 화면은 일반 설정 경로(`settingsApi.getByPrefix('smtp')`
  // ·`update`·`clearSmtp`)로 워크스페이스 값을 읽고 쓴다. 연결 테스트는 값을 노출하지 않는 진단
  // 액션이라 유지한다 — 워크스페이스에 <b>저장된</b> 설정으로 접속한다.
  testSmtpSettings: () => client.post('/settings/smtp/test'),
};
