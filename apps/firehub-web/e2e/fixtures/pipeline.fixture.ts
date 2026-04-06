import type { Page } from '@playwright/test';

import {
  createExecution,
  createPipelineDetail,
  createPipelines,
  createTrigger,
} from '../factories/pipeline.factory';
import { createPageResponse, mockApi } from './api-mock';

/**
 * 파이프라인 도메인 모킹 헬퍼
 * - 파이프라인 목록/에디터 페이지 테스트에서 공통으로 사용하는 API 모킹 함수를 제공한다.
 * - 백엔드 없이 파이프라인 관련 E2E 테스트를 실행할 수 있도록 지원한다.
 */

/**
 * 파이프라인 목록 페이지 API 모킹
 * - 파이프라인 페이지 목록을 모킹한다.
 * @param count - 목록에 포함할 파이프라인 수 (기본값: 5)
 */
export async function setupPipelineMocks(page: Page, count = 5) {
  await mockApi(
    page,
    'GET',
    '/api/v1/pipelines',
    createPageResponse(createPipelines(count)),
  );
}

/**
 * 파이프라인 에디터 페이지 API 모킹
 * - 파이프라인 상세, 실행 이력, 트리거 목록, 데이터셋 목록을 모킹한다.
 * @param pipelineId - 모킹할 파이프라인 ID (기본값: 1)
 */
export async function setupPipelineEditorMocks(page: Page, pipelineId = 1) {
  const detail = createPipelineDetail({ id: pipelineId });

  // 파이프라인 상세 정보
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}`, detail);

  // 실행 이력 목록 (완료된 실행 2개)
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/executions`, [
    createExecution({ id: 1, pipelineId }),
    createExecution({ id: 2, pipelineId, status: 'FAILED', completedAt: '2024-01-01T00:02:00Z' }),
  ]);

  // 트리거 목록
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/triggers`, [
    createTrigger({ pipelineId }),
  ]);

  // 트리거 이벤트 로그
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/trigger-events`, []);

  // 에디터에서 출력 데이터셋 선택 시 필요한 데이터셋 목록 (빈 목록으로 모킹)
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [],
    page: 0,
    size: 1000,
    totalElements: 0,
    totalPages: 0,
  });
}
