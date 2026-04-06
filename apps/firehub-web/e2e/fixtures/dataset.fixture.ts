import type { Page } from '@playwright/test';

import { createCategories, createDatasetDetail, createDatasets } from '../factories/dataset.factory';
import { createPageResponse, mockApi } from './api-mock';

/**
 * 데이터셋 도메인 모킹 헬퍼
 * - 데이터셋 목록/상세 페이지 테스트에서 공통으로 사용하는 API 모킹 함수를 제공한다.
 * - 백엔드 없이 데이터셋 관련 E2E 테스트를 실행할 수 있도록 지원한다.
 */

/**
 * 데이터셋 목록 페이지 API 모킹
 * - 카테고리 목록, 데이터셋 페이지 목록, 태그 목록을 모킹한다.
 */
export async function setupDatasetMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(5)));
  await mockApi(page, 'GET', '/api/v1/datasets/tags', ['sample', 'test', 'production']);
}

/**
 * 데이터셋 상세 페이지 API 모킹
 * - 단일 데이터셋 정보, 데이터 미리보기, 통계, 쿼리 목록을 모킹한다.
 * @param datasetId - 모킹할 데이터셋 ID (기본값: 1)
 */
export async function setupDatasetDetailMocks(page: Page, datasetId = 1) {
  const detail = createDatasetDetail({ id: datasetId });
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}`, detail);
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}/data`, {
    columns: detail.columns,
    rows: [
      { id: 1, name: '항목 1', value: 100 },
      { id: 2, name: '항목 2', value: 200 },
    ],
    page: 0,
    size: 20,
    totalElements: 2,
    totalPages: 1,
  });
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}/stats`, []);
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}/queries`, createPageResponse([]));
}
