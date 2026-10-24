import type { Page } from '@playwright/test';

import type { PipelineDeleteImpact } from '../../src/types/pipeline';
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
 * 파이프라인 상세의 쓰기 진입점을 모두 쓰는 권한 묶음 (WD-219) — 서버 PipelineController·TriggerController 의 @RequirePermission 과 같다.
 * 수정·증분 재생성 예약 pipeline:write, 실행·재실행·실행 취소 pipeline:execute, 트리거 생성·편집·켜고 끄기 trigger:write, 트리거 삭제 trigger:delete.
 * 기본 인증 fixture 의 권한은 빈 목록이라(조회 전용과 같다) 실행·수정·트리거 편집 흐름을 검증하는 spec 은 이 권한을 명시적으로 준다.
 */
export const PIPELINE_ALL_PERMISSIONS = [
  'pipeline:read',
  'pipeline:write',
  'pipeline:execute',
  'pipeline:delete',
  'trigger:read',
  'trigger:write',
  'trigger:delete',
];

/**
 * 내 권한 코드(/auth/me/permissions)를 파이프라인 권한으로 덮어쓴다 (WD-219).
 * 픽스처(setupPipelineEditorMocks) 안에 숨기지 않고 spec 이 직접 부르게 한 이유는 grantDatasetPermissions 와 같다 —
 * 라우트는 나중에 등록한 것이 이기므로, 상세 모킹 헬퍼가 권한까지 덮으면 그보다 먼저 좁은 권한을 준 테스트가 조용히 넓은 권한으로 바뀐다.
 * 합쳐지지 않고 덮어쓰므로 데이터셋 권한도 필요한 spec 은 합집합 배열을 한 번만 넘긴다.
 * @param permissions - 줄 권한 코드(기본: PIPELINE_ALL_PERMISSIONS). 부분 권한 검증 시 일부만 넘긴다.
 */
export async function grantPipelinePermissions(page: Page, permissions: string[] = PIPELINE_ALL_PERMISSIONS) {
  await mockApi(page, 'GET', '/api/v1/auth/me/permissions', permissions);
}

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
  // 삭제 확인 창이 열릴 때 부르는 삭제 영향 조회(WD-170) — 기본값은 "함께 지울 임시 데이터셋 없음".
  await mockPipelineDeleteImpact(page);
}

/** 파이프라인 삭제 영향 엔드포인트 경로 — GET /api/v1/pipelines/{id}/delete-impact (WD-170) */
const PIPELINE_DELETE_IMPACT_PATH = /^\/api\/v1\/pipelines\/\d+\/delete-impact$/;

/**
 * 파이프라인 삭제 영향 API 모킹(WD-170) — 삭제 확인 창이 열릴 때 부른다.
 * 나중에 등록한 route 가 우선하므로, 목록 셋업의 기본값(0개)을 테스트에서 다시 불러 덮어쓸 수 있다.
 * @param tempDatasetCount - 함께 지워질 자동 생성 임시 데이터셋 개수(기본값: 0)
 * @param status - 응답 상태(실패 경로 검증용)
 */
export async function mockPipelineDeleteImpact(page: Page, tempDatasetCount = 0, status = 200) {
  const impact: PipelineDeleteImpact = { tempDatasetCount };
  await page.route(
    (url) => PIPELINE_DELETE_IMPACT_PATH.test(url.pathname),
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      return route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(status === 200 ? impact : { status, error: 'Internal Server Error', message: '오류' }),
      });
    },
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
