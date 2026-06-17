import type { Page } from '@playwright/test';

import { createCategories, createDatasetDetail } from '../factories/dataset.factory';
import { createDocuments } from '../factories/document.factory';
import { mockApi } from './api-mock';

/**
 * DOCUMENT 데이터셋 상세 + 문서 목록 모킹.
 * - useDataset, useDocuments 외에 DatasetDetailPage가 무조건 호출하는
 *   useCategories(dataset-categories)·useTags(datasets/tags)도 함께 모킹한다.
 * @param documents 모킹할 문서 목록(기본 3건)
 */
export async function setupDocumentDatasetMocks(
  page: Page,
  datasetId = 1,
  documents = createDocuments(3),
) {
  // 백엔드는 DOCUMENT 데이터셋에 대해 동적 테이블이 없어 rowCount: null을 반환한다 — 프로덕션 실제값 반영.
  const detail = createDatasetDetail({ id: datasetId, storageType: 'DOCUMENT', originType: 'SOURCE', columns: [], rowCount: null });
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}`, detail);
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}/documents`, documents);
  // DatasetDetailPage 헤더 영역에서 항상 호출되는 API (탭 무관)
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
}
