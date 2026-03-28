import { client } from './client';

export interface ReportTemplate {
  id: number;
  name: string;
  description: string | null;
  structure: Record<string, unknown>;
  builtin: boolean;
  createdAt: string;
  updatedAt: string;
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
  executionId: number | null;
  jobName: string | null;
  title: string;
  content: Record<string, unknown>;
  messageType: string;
  read: boolean;
  createdAt: string;
}

export interface CreateProactiveJobRequest {
  name: string;
  prompt: string;
  templateId?: number | null;
  cronExpression: string;
  timezone?: string;
  config?: Record<string, unknown>;
}

export interface UpdateProactiveJobRequest {
  name?: string;
  prompt?: string;
  templateId?: number | null;
  cronExpression?: string;
  timezone?: string;
  enabled?: boolean;
  config?: Record<string, unknown>;
}

export interface CreateReportTemplateRequest {
  name: string;
  description?: string;
  structure: Record<string, unknown>;
}

export interface UpdateReportTemplateRequest {
  name?: string;
  description?: string;
  structure?: Record<string, unknown>;
}

export interface SmtpSettingsRequest {
  'smtp.host'?: string;
  'smtp.port'?: string;
  'smtp.username'?: string;
  'smtp.password'?: string;
  'smtp.starttls'?: string;
  'smtp.from_address'?: string;
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

  // Messages (3 methods)
  getMessages: (params?: { limit?: number; offset?: number }) =>
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

  // SMTP (3 methods)
  getSmtpSettings: () =>
    client.get<Array<{ key: string; value: string; description: string | null; updatedAt: string }>>(
      '/settings/smtp',
    ),
  updateSmtpSettings: (data: SmtpSettingsRequest) =>
    client.put('/settings/smtp', data),
  testSmtpSettings: () => client.post('/settings/smtp/test'),
};
