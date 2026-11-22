import type { Locator, Page } from '@playwright/test';

import { expect, test } from './auth.fixture';

/**
 * 첫 진입 준비 대기 시간(ms).
 * WD-878: 병렬 부하 중에는 vite dev 서버의 첫 페이지 변환 + 응답 뒤 렌더가 기본 액션 타임아웃(15초)을
 * 넘겨 첫 조작이 실패했다. 그래서 준비 신호를 30초까지 기다리고, 그만큼 테스트 예산(기본 30초)도 늘린다.
 */
export const READY_WAIT_MS = 30_000;

/**
 * path 로 이동한 뒤 준비 신호(ready)가 보일 때까지 기다린다.
 *
 * WD-903: 지식 모델 spec 안에만 있던 헬퍼를 다른 spec 도 재사용하도록 공용 fixture 로 옮겼다.
 * 대기를 늘리면 테스트 예산을 같이 늘려야 대기 중 테스트 타임아웃에 걸리지 않으므로,
 * 호출(진입)마다 한 번 예산을 READY_WAIT_MS 만큼 늘린 뒤 기다린다.
 * `expect`·`test` 는 spec 들과 같은 인스턴스를 쓰도록 auth.fixture 에서 가져온다.
 */
export async function gotoUntilReady(page: Page, path: string, ready: Locator): Promise<void> {
  // timeout 0 은 무제한(--timeout=0·디버그)이라, 더하면 오히려 30초 제한이 되므로 0 보다 클 때만 늘린다.
  const current = test.info().timeout;
  if (current > 0) test.info().setTimeout(current + READY_WAIT_MS);
  await page.goto(path);
  await expect(ready).toBeVisible({ timeout: READY_WAIT_MS });
}
