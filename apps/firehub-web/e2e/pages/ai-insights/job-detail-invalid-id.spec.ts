import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 이슈 #545: 스마트 작업 상세 — 비숫자 jobId(/ai-insights/jobs/abc) 접근 시
 * "작업을 찾을 수 없습니다" 대신 모든 필드가 빈 유령 작업 화면이 렌더링되던 버그.
 *
 * 근본 원인: `Number('abc')` = NaN이 jobId로 그대로 쓰이고,
 * useProactiveJob의 `enabled: !!id`(!!NaN === false)로 쿼리 자체가 실행되지 않아
 * isLoading/isError가 영구히 false로 남아 기존 404 처리(#47)를 우회했다.
 * ProactiveJobDetailPage에 isInvalidId 플래그를 추가해 에러 뷰로 합류시켜 수정.
 */
test.describe('스마트 작업 상세 — 비숫자 ID 처리 (#545)', () => {
  test('비숫자 jobId(/ai-insights/jobs/abc) 접근 시 "작업을 찾을 수 없습니다" 화면이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    // 핵심 검증: 비숫자 ID이므로 상세 조회 API가 아예 호출되지 않아야 한다
    let jobDetailRequested = false;
    await page.route('**/api/v1/proactive/jobs/abc', () => {
      jobDetailRequested = true;
    });

    await page.goto('/ai-insights/jobs/abc');

    // "작업을 찾을 수 없습니다" 에러 뷰가 표시되어야 한다 (유령 상세 화면 X)
    await expect(page.getByRole('heading', { name: '작업을 찾을 수 없습니다' })).toBeVisible({
      timeout: 5000,
    });
    await expect(page.getByText('요청하신 작업이 존재하지 않거나 삭제되었습니다.')).toBeVisible();

    // 유령 상세 화면의 흔적(제목 placeholder '-', 활성 상태의 삭제/실행 버튼)이 없어야 한다
    await expect(page.getByRole('button', { name: '삭제' })).not.toBeVisible();
    await expect(page.getByRole('button', { name: '지금 실행' })).not.toBeVisible();

    expect(jobDetailRequested).toBe(false);

    // 목록으로 돌아가기 버튼으로 정상 이동 가능
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs$/);
  });
});

/**
 * 이슈 WD-568: 스마트 작업 상세 — 공백(`%20`)·`0` jobId 접근 시 이름이 `-` 인 빈 상세와 실행·삭제 버튼이 보이던 버그.
 *
 * `Number(' ')`·`Number('0')` 은 0 이라 #545 의 `Number.isFinite` 검사를 통과했고,
 * jobId=0 이면 useProactiveJob 의 `enabled: !!id` 가 false 라 쿼리가 돌지 않아 유령 상세가 그려졌다.
 * parseRouteId 로 읽어 기존 「작업을 찾을 수 없습니다」 화면을 타게 했다.
 */
test.describe('스마트 작업 상세 — 공백·0 ID 처리 (WD-568)', () => {
  for (const rawId of ['%20', '0']) {
    test(`jobId "${rawId}"(/ai-insights/jobs/${rawId}) 접근 시 "작업을 찾을 수 없습니다" 화면이 표시된다`, async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

      // 잘못된 id 로 상세(및 하위) API 를 부르지 않아야 한다
      const detailPath = new RegExp(`^/api/v1/proactive/jobs/${rawId}(/|$)`);
      let jobDetailRequested = false;
      await page.route(
        (url) => detailPath.test(url.pathname),
        (route) => {
          jobDetailRequested = true;
          return route.abort();
        },
      );

      await page.goto(`/ai-insights/jobs/${rawId}`);

      await expect(page.getByRole('heading', { name: '작업을 찾을 수 없습니다' })).toBeVisible({
        timeout: 5000,
      });
      await expect(page.getByText('요청하신 작업이 존재하지 않거나 삭제되었습니다.')).toBeVisible();

      // 유령 상세 화면의 위험 버튼(삭제·지금 실행)이 없어야 한다
      await expect(page.getByRole('button', { name: '삭제' })).not.toBeVisible();
      await expect(page.getByRole('button', { name: '지금 실행' })).not.toBeVisible();

      expect(jobDetailRequested).toBe(false);
    });
  }
});
