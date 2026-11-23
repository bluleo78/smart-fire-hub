import type { Page } from '@playwright/test';

import { expectTableErrorAlert } from './table-error-alert';

/** 목록 API 가 돌려줄 응답 */
export interface ListOutcome {
  status: number;
  body: unknown;
}

/**
 * 목록 API 응답을 테스트 중에 바꿀 수 있게 가로채고, 500 으로 목록 페이지에 진입한다 (WD-987 「다시 시도」 공용).
 *
 * - GET 만 가로채고 나머지 메서드는 그대로 흘려 보낸다.
 * - 첫 조회 실패 안내(「다시 시도」 버튼 포함)가 보일 때까지 기다린 뒤 돌려준다.
 * - 앱이 첫 조회를 한 번 더 재시도하는 목록(API 연결 등)도 있으므로 setOutcome 을 부르기 전까지는 실패 응답을 계속 돌려 준다.
 * - 이후 setOutcome 으로 다음 응답(성공·재실패)을 바꿔 「다시 시도」 결과를 만든다.
 */
export async function enterWithListError(
  page: Page,
  { apiPath, pagePath }: { apiPath: string; pagePath: string },
) {
  let current: ListOutcome = { status: 500, body: { message: '서버 오류' } };
  // 목록 GET 을 몇 번 불렀는지 — 누르지 않은 재조회(다시 연결 등)가 실제로 일어났는지 확인할 때 쓴다
  let calls = 0;
  await page.route(
    (url) => url.pathname === apiPath,
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      calls += 1;
      return route.fulfill({ status: current.status, contentType: 'application/json', body: JSON.stringify(current.body) });
    },
  );
  await page.goto(pagePath);
  await expectTableErrorAlert(page, '서버 오류', { action: '다시 시도' });
  return {
    setOutcome: (next: ListOutcome) => {
      current = next;
    },
    calls: () => calls,
  };
}
