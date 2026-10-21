import type { Page } from '@playwright/test';

import {
  createCategories,
  createDatasetDetail,
  createDatasetReferences,
  createDatasets,
  createFileDatasetDetail,
} from '../factories/dataset.factory';
import { createPageResponse, mockApi } from './api-mock';

/**
 * 데이터셋 도메인 모킹 헬퍼
 * - 데이터셋 목록/상세 페이지 테스트에서 공통으로 사용하는 API 모킹 함수를 제공한다.
 * - 백엔드 없이 데이터셋 관련 E2E 테스트를 실행할 수 있도록 지원한다.
 */

/**
 * 데이터셋 상세의 쓰기 진입점이 서버에서 요구하는 권한 전체 (WD-139).
 * 정의·메타 변경 dataset:write, 데이터셋 삭제 dataset:delete(WD-144), 행 추가·수정·임포트·SQL 실행 data:import, 행 삭제 data:delete, API 가져오기 pipeline:write.
 * 기본 인증 fixture 의 권한은 빈 목록이라(조회 전용과 같다) 편집 흐름을 검증하는 spec 은 이 권한을 명시적으로 준다.
 */
export const DATASET_WRITE_PERMISSIONS = [
  'dataset:read',
  'dataset:write',
  // 데이터셋 자체 삭제·카테고리 삭제(WD-144) — 목록 휴지통·카테고리 삭제 흐름을 검증하는 spec 이 같은 묶음을 쓴다
  'dataset:delete',
  'data:read',
  'data:import',
  'data:delete',
  'pipeline:write',
];

/**
 * 내 권한 코드(/auth/me/permissions)를 데이터셋 쓰기 권한으로 덮어쓴다.
 * 픽스처 안에 숨기지 않고 spec 이 직접 부르게 한 이유: 라우트는 나중에 등록한 것이 이기므로, 상세 모킹 헬퍼가 권한까지 덮으면
 * 그보다 먼저 좁은 권한을 준 테스트가 조용히 넓은 권한으로 바뀐다.
 * @param permissions - 줄 권한 코드(기본: DATASET_WRITE_PERMISSIONS). 부분 권한 검증 시 일부만 넘긴다.
 */
export async function grantDatasetPermissions(page: Page, permissions: string[] = DATASET_WRITE_PERMISSIONS) {
  await mockApi(page, 'GET', '/api/v1/auth/me/permissions', permissions);
}

/**
 * 데이터셋 참조 집계(GET /datasets/{id}/references, WD-143) 모킹 — 어느 데이터셋 id 든 같은 응답을 준다.
 * 삭제 확인 창이 열릴 때 부르므로, 모킹이 없으면 창이 실패 안내로 빠진다. 라우트는 나중에 등록한 것이 이기므로 spec 이 덮어쓸 수 있다.
 * @param body - 응답 본문(기본: 쓰는 곳 없음)
 * @param status - 응답 상태(실패 안내 검증 시 500 등)
 */
export async function mockDatasetReferences(page: Page, body = createDatasetReferences(), status = 200) {
  await page.route(
    (url) => /^\/api\/v1\/datasets\/\d+\/references$/.test(url.pathname),
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    },
  );
}

/**
 * 데이터셋 목록 페이지 API 모킹
 * - 카테고리 목록, 데이터셋 페이지 목록, 태그 목록, 삭제 확인 창의 참조 집계(쓰는 곳 없음)를 모킹한다.
 */
export async function setupDatasetMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(5)));
  await mockApi(page, 'GET', '/api/v1/datasets/tags', ['sample', 'test', 'production']);
  await mockDatasetReferences(page);
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

/**
 * FILE 데이터셋 생성 플로우 API 모킹
 * - POST /api/v1/datasets 생성 응답과, 생성 후 자동 이동하는 상세 페이지 API를 모킹한다.
 * - capture 옵션으로 반환되는 MockApiCapture를 통해 테스트에서 실제 전송된 payload를 검증할 수 있다.
 * @param datasetId - 생성 응답으로 반환할 데이터셋 ID (기본값: 20)
 */
export async function setupFileDatasetCreateMocks(page: Page, datasetId = 20) {
  const detail = createFileDatasetDetail({ id: datasetId });
  const capture = await mockApi(
    page,
    'POST',
    '/api/v1/datasets',
    { id: datasetId, name: detail.name },
    { capture: true },
  );
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}`, detail);
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}/data`, {
    columns: detail.columns,
    rows: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  });
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}/stats`, []);
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}/queries`, createPageResponse([]));
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  return capture;
}
