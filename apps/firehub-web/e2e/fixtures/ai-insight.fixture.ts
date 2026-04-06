import type { Page } from '@playwright/test';

import {
  createAnomalyEvent,
  createJob,
  createJobExecution,
  createJobs,
  createTemplate,
  createTemplates,
} from '../factories/ai-insight.factory';
import { mockApi } from './api-mock';

/**
 * AI 인사이트(Proactive) 도메인 모킹 헬퍼
 * - 작업(Jobs), 템플릿(Templates), 실행(Executions) 페이지 테스트에서
 *   공통으로 사용하는 API 모킹 함수를 제공한다.
 * - 백엔드 없이 AI 인사이트 관련 E2E 테스트를 실행할 수 있도록 지원한다.
 */

/**
 * 작업 목록 페이지 API 모킹
 * - 프로액티브 잡 목록과 읽지 않은 메시지 수를 모킹한다.
 * @param count - 목록에 포함할 잡 수 (기본값: 3)
 */
export async function setupJobListMocks(page: Page, count = 3) {
  await mockApi(page, 'GET', '/api/v1/proactive/jobs', createJobs(count));
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
}

/**
 * 작업 상세 페이지 API 모킹
 * - 단건 잡 조회, 실행 이력, 템플릿 목록을 모킹한다.
 * @param jobId - 모킹할 잡 ID (기본값: 1)
 */
export async function setupJobDetailMocks(page: Page, jobId = 1) {
  const job = createJob({ id: jobId });
  await mockApi(page, 'GET', `/api/v1/proactive/jobs/${jobId}`, job);
  await mockApi(page, 'GET', `/api/v1/proactive/jobs/${jobId}/executions`, []);
  await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  // 이상 탐지 이력 API 모킹 — 모니터링 탭의 AnomalyHistorySection에서 사용
  await mockApi(
    page,
    'GET',
    `/api/v1/proactive/jobs/${jobId}/anomaly-events`,
    [
      createAnomalyEvent(),
      createAnomalyEvent({
        id: 2,
        metricName: '데이터셋 수',
        currentValue: 150,
        mean: 100,
        deviation: 3.2,
        detectedAt: '2026-04-06T09:15:00',
      }),
    ],
  );
}

/**
 * 새 작업 생성 페이지 API 모킹
 * - 템플릿 목록만 모킹한다 (새 잡이므로 잡 상세 불필요).
 */
export async function setupNewJobMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
}

/**
 * 실행 이력이 포함된 작업 상세 API 모킹
 * - 단건 잡 조회, 실행 이력(여러 건), 템플릿 목록을 모킹한다.
 * @param jobId - 모킹할 잡 ID (기본값: 1)
 * @param executionCount - 실행 이력 수 (기본값: 3)
 */
export async function setupJobDetailWithExecutionsMocks(
  page: Page,
  jobId = 1,
  executionCount = 3,
) {
  const job = createJob({ id: jobId });
  const executions = Array.from({ length: executionCount }, (_, i) =>
    createJobExecution({
      id: i + 1,
      jobId,
      status: i === 0 ? 'FAILED' : 'COMPLETED',
    }),
  );
  await mockApi(page, 'GET', `/api/v1/proactive/jobs/${jobId}`, job);
  await mockApi(page, 'GET', `/api/v1/proactive/jobs/${jobId}/executions`, executions);
  await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
}

/**
 * 템플릿 목록 페이지 API 모킹
 * - 기본/커스텀 템플릿 목록을 모킹한다.
 */
export async function setupTemplateListMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
}

/**
 * 템플릿 상세 페이지 API 모킹
 * - 단건 템플릿 조회와 목록을 모킹한다.
 * @param templateId - 모킹할 템플릿 ID (기본값: 1)
 */
export async function setupTemplateDetailMocks(page: Page, templateId = 1) {
  const template = createTemplate({ id: templateId });
  await mockApi(page, 'GET', `/api/v1/proactive/templates/${templateId}`, template);
  await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
}

/**
 * 실행 상세 페이지 API 모킹
 * - 단건 실행 조회를 모킹한다.
 * @param jobId - 잡 ID (기본값: 1)
 * @param executionId - 실행 ID (기본값: 1)
 * @param status - 실행 상태 (기본값: 'COMPLETED')
 */
export async function setupExecutionDetailMocks(
  page: Page,
  jobId = 1,
  executionId = 1,
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' = 'COMPLETED',
) {
  const execution = createJobExecution({ id: executionId, jobId, status });
  await mockApi(
    page,
    'GET',
    `/api/v1/proactive/jobs/${jobId}/executions/${executionId}`,
    execution,
  );
  // HTML 리포트 조회 — COMPLETED 상태에서 활성화되는 API
  if (status === 'COMPLETED') {
    await mockApi(
      page,
      'GET',
      `/api/v1/proactive/jobs/${jobId}/executions/${executionId}/html`,
      '',
      { status: 404 },
    );
  }
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
}
