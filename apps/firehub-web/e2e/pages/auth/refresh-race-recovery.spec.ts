/**
 * 갱신 401 재확인으로 로그인을 지키는지 고정한다 (WD-1141).
 *
 * <p>배경: 서버는 refresh 토큰을 갱신마다 회전시키고, 이미 폐기된 토큰이 다시 오면 로그인 패밀리 전체를 폐기한다.
 * 탭 사이 잠금(Web Locks)이 없는 환경(평문 http 비 localhost 배포 등)에서 갱신이 겹치면 회전 경쟁의 패자 쪽에
 * 401 이 난다. 그 401 을 곧바로 세션 종료로 넘기면 storage 이벤트로 이긴 탭까지 로그아웃된다. 그래서 제품은
 * **이긴 탭이 응답을 받은 뒤 쓰는 회전 표식(localStorage: REFRESH_ROTATED_KEY)이 바뀐 뒤에만** 한 번 더 갱신해
 * (postRefresh — WD-1141) 새 쿠키로 성공하는지 확인한 뒤에만 세션을 끝낸다. 표식이 바뀌었다는 것은 그 시점에
 * 쿠키 잼에 새 쿠키가 들어왔다는 뜻이라, 회전 전 옛 쿠키를 다시 보내 재사용 탐지에 걸리지 않는다.
 *
 * <p>지터 대기가 아니라 실제 사건(표식 변화·호출 수)으로 기다리므로 고정 숫자로 기다리지 않는다.
 *
 * <p>이 시험은 그 재확인이 실제로 로그인을 지키는지 브라우저에서 고정한다:
 * ① 목록 조회 첫 요청 401 → ② 갱신 첫 401 → 이긴 탭이 회전 표식을 씀 → 재확인 갱신 정상 → 로그인 화면으로 보내지지 않고 목록이 그려진다.
 * 잠금이 있는 환경(dev 서버의 http://localhost)에서는 재확인이 없으므로, 평문 http 비 localhost 배포를
 * 흉내 내기 위해 `Navigator.prototype.locks` 를 끈 뒤 시험한다.
 */
import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';

/** 갱신 엔드포인트 pathname — 첫 401·재확인 정상을 나누는 자리 */
const REFRESH_PATH = '/api/v1/auth/refresh';

/** 목록 조회 pathname — 첫 401 로 갱신을 일으키는 자리 */
const DATASETS_PATH = '/api/v1/datasets';

/** 이긴 탭이 회전을 마친 뒤 쓰는 표식 키 — client.ts 의 REFRESH_ROTATED_KEY 와 같은 값이어야 한다 */
const ROTATED_KEY = 'refreshRotatedAt';

/**
 * path 로 오는 method 요청은 **첫 건만 401** 로 떨어뜨리고, 다음부터는 픽스처 모킹(fallback)으로 넘긴다 —
 * 갱신·목록 각각의 「첫 요청만 실패」 자리를 같은 모양으로 만든다(WD-1141).
 *
 * <p>호출 수 카운터를 돌려주는데, 시험은 재확인이 두 번째 갱신까지 갔는지 이 것으로 단언한다.
 * `afterFirst` 가 있으면 첫 401 을 보낸 직후에 부른다 — 갱신 경쟁의 이긴 탭이 회전 표식을 쓰는 자리를 흉내 내는 데 쓴다.
 */
async function firstRequestUnauthorized(
  page: Page,
  method: string,
  path: string,
  afterFirst?: () => Promise<void>,
): Promise<() => number> {
  let calls = 0;
  await page.route(
    (url) => url.pathname === path,
    async (route) => {
      if (route.request().method() !== method) return route.fallback();
      calls += 1;
      if (calls === 1) {
        await route.fulfill({ status: 401, contentType: 'application/json', body: '{}' });
        await afterFirst?.();
        return;
      }
      return route.fallback();
    },
  );
  return () => calls;
}

test.describe('갱신 401 재확인 회복 (WD-1141)', () => {
  test('갱신 401 재확인이 이긴 뒤에도 로그인을 유지한다', async ({ authMockedPage: page }) => {
    // 평문 http 비 localhost 배포는 보안 컨텍스트가 아니어 Web Locks 이 없다 — 그 환경을 흉내 내기 위해 잠금을 끈다.
    // 잠금이 없을 때만 재확인이 일어나므로(WD-1141), 이 끔이 이 시험의 전제다.
    await page.addInitScript(() => {
      Object.defineProperty(Navigator.prototype, 'locks', { configurable: true, get: () => undefined });
    });

    // 로그인 화면에서 실제 로그인 흐름으로 들어온다 — 액세스 토큰이 메모리에 채워지고 세션 표식이 생긴다
    await page.goto('/login');
    await page.getByLabel('아이디 (이메일)').fill('test@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('testpassword123');
    await page.getByRole('button', { name: '로그인' }).click();
    await page.waitForURL('/');

    // 목록 페이지 모킹 — 나중에 등록한 라우트가 이기므로, 아래 목록 조회의 첫 401 라우트가 이 모킹을 덮는다
    // (갱신 라우트는 다른 path 라 이 모킹과 겹치지 않는다)
    await setupDatasetMocks(page);

    // 전체 이동으로 목록을 연다 — 이 부팅의 갱신은 픽스처 모킹(정상)으로 가므로 재확인 시험이 섞이지 않는다
    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: '데이터셋 관리' })).toBeVisible();

    // ② 갱신: 첫 요청만 401(회전 경쟁의 패자), 재확인 요청은 픽스처 모킹(정상 토큰 응답)으로 떨어진다.
    // 첫 401 을 보낸 직후 이긴 탭이 회전 표식을 쓴다 — 재확인은 이 표식이 바뀐 뒤에만 재전송하므로(WD-1141 리뷰 1),
    // 표식이 없으면 패자는 재전송하지 않고 세션을 끝낸다(그래서 이 자리를 흉내 내는 것이 이 시험의 전제다).
    const refreshCalls = await firstRequestUnauthorized(page, 'POST', REFRESH_PATH, () =>
      page.evaluate((key) => {
        localStorage.setItem(key, `winner-${Date.now()}`);
      }, ROTATED_KEY),
    );

    // ① 목록 조회: 첫 요청만 401 — 갱신 뒤 인터셉터가 다시 보낼 때는 픽스처 목록 모킹으로 정상 응답한다
    await firstRequestUnauthorized(page, 'GET', DATASETS_PATH);

    // 목록 조회 한 건을 만든다 — 검색어 입력은 **앱의** 디바운스(300ms) 뒤 같은 목록을 다시 조회한다(테스트 대기가 아니라 앱 동작).
    await page.getByPlaceholder('데이터셋 검색...').fill('경쟁');

    // 재확인이 두 번째 갱신까지 간다 — 잠금 있는 환경이었다면 첫 401 에서 세션을 끝내 1회로 멈춘다.
    // 고정 지연이 아니라 실제 호출 수를 기다린다 — 재확인 창(800ms) 안에서 표식을 확인하고 재전송하므로 그 안에 붙는다
    await expect.poll(refreshCalls, { timeout: 10_000 }).toBe(2);
    // 재확인이 이긴 뒤에도 세션은 살아 있어 로그인 화면으로 보내지지 않는다
    await expect(page).not.toHaveURL(/\/login/);
    // 갱신 뒤 재시도된 목록 조회가 화면을 다시 그린다
    await expect(page.getByRole('cell', { name: '데이터셋 1', exact: true })).toBeVisible();
  });
});
