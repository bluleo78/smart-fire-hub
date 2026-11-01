import type { Page } from '@playwright/test';

import type { CronPreviewResponse } from '../../src/types/common';
import { mockApi, type MockApiCapture } from './api-mock';

/**
 * cron 미리보기 API 모킹 (WD-391).
 * - 트리거 편집 창 「다음 5회」와 스마트 작업 편집 창 「다음 실행」은 이제 서버가 계산한 값을 그대로 보인다.
 *   화면이 이 API 를 부르므로, 편집 창을 여는 spec 은 기본 응답을 모킹해 두어야 백엔드 없이 미리보기가 그려진다.
 * - 라우트는 나중에 등록한 것이 이기므로, 기본 모킹 뒤에 테스트 본문에서 다시 불러 응답(서머타임 시각·오류)을 덮어쓸 수 있다.
 */

/** 파이프라인 트리거 미리보기 경로 — 권한 trigger:read */
export const TRIGGER_CRON_PREVIEW_PATH = '/api/v1/triggers/cron-preview';
/** 스마트 작업 미리보기 경로 — 권한 proactive:read */
export const JOB_CRON_PREVIEW_PATH = '/api/v1/proactive/jobs/cron-preview';

/** 기본 미리보기 시각 — 서울 09:00(UTC 00:00) 5일치 */
export const DEFAULT_CRON_PREVIEW_TIMES = [
  '2026-01-02T00:00:00Z',
  '2026-01-03T00:00:00Z',
  '2026-01-04T00:00:00Z',
  '2026-01-05T00:00:00Z',
  '2026-01-06T00:00:00Z',
];

/** 미리보기 응답 객체 생성 — CronPreviewResponse 타입으로 서버 계약과 맞춘다. */
export function createCronPreview(
  nextFireTimes: string[] = DEFAULT_CRON_PREVIEW_TIMES,
  overrides?: Partial<CronPreviewResponse>,
): CronPreviewResponse {
  return { cron: '0 0 9 * * *', timezone: 'Asia/Seoul', nextFireTimes, ...overrides };
}

/**
 * 미리보기 엔드포인트를 모킹하고 요청(쿼리 파라미터)을 캡처한다.
 * @param path - TRIGGER_CRON_PREVIEW_PATH 또는 JOB_CRON_PREVIEW_PATH
 * @param nextFireTimes - 돌려줄 다음 발화 시각(UTC ISO)
 * @param error - 지정하면 그 상태·문구의 오류 응답을 돌려준다(서버 400 문구 표시 검증용)
 */
export async function mockCronPreview(
  page: Page,
  path: string,
  nextFireTimes: string[] = DEFAULT_CRON_PREVIEW_TIMES,
  error?: { status: number; message: string },
): Promise<MockApiCapture> {
  const body = error
    ? { status: error.status, error: 'Bad Request', message: error.message }
    : createCronPreview(nextFireTimes);
  return mockApi(page, 'GET', path, body, { capture: true, status: error?.status ?? 200 });
}
