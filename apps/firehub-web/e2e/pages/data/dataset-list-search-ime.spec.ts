import { createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 목록 — 검색 입력 한글 IME composition 회귀 테스트
 *
 * 배경:
 * - DatasetListPage 의 검색어는 URL searchParams(`q`)로 동기화되어, 매 입력마다 부모에서
 *   `value` 가 round-trip 된다.
 * - 부모에서 외부 value 가 매 입력마다 덮어쓰이면, 한글 IME 조합(composition) 중에
 *   조합 중인 글자가 깨져 자모(예: "ㄱ", "ㅏ")가 분리되어 표시되는 버그가 있었다.
 *
 * 검증 항목:
 * 1. compositionstart ~ compositionend 사이에는 부모 onChange 가 발화되지 않아
 *    URL `q` 파라미터가 변경되지 않고 /datasets API 가 조합 중간값으로 호출되지 않는다.
 * 2. compositionend 이후에는 최종 조합 글자("가")로 URL 과 API 호출이 정확히 갱신된다.
 * 3. 입력 box 의 표시 값은 조합 중에도 깨지지 않고 사용자가 본 글자 그대로 유지된다.
 */
test.describe('데이터셋 목록 — 검색 한글 IME 입력', () => {
  const datasets = [
    createDataset({ id: 1, name: '가스 누출 데이터셋' }),
    createDataset({ id: 2, name: '나무 화재 데이터셋' }),
  ];

  async function setup(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    const urls: URL[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        urls.push(new URL(route.request().url()));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(datasets)),
        });
      },
    );
    return urls;
  }

  test('한글 IME 조합 중에는 부모 onChange 가 발화되지 않고 조합 종료 시 최종값으로 검색된다', async ({
    authenticatedPage: page,
  }) => {
    const urls = await setup(page);
    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const searchInput = page.getByRole('textbox', { name: '데이터셋 검색...' });
    await searchInput.focus();

    // 초기 호출 후 누적된 URL 개수를 기준점으로 잡는다.
    const initialCallCount = urls.length;

    // 한글 "가" 입력 시뮬레이션:
    //   1) compositionstart  — 조합 시작
    //   2) "ㄱ" 표시 + compositionupdate
    //   3) "가" 로 결합 + compositionupdate
    //   4) compositionend    — 조합 종료
    // React 의 onChange 는 native input 의 value 변경 + input 이벤트로 발화된다.
    await searchInput.evaluate((el) => {
      const input = el as HTMLInputElement;
      const fire = (type: string, data?: string) => {
        input.dispatchEvent(
          type.startsWith('composition')
            ? new CompositionEvent(type, { bubbles: true, data: data ?? '' })
            : new Event(type, { bubbles: true }),
        );
      };

      // setter 우회로 React 가 변경을 감지하도록 native setter 사용
      const nativeSetter = Object.getOwnPropertyDescriptor(
        window.HTMLInputElement.prototype,
        'value',
      )!.set!;

      fire('compositionstart');
      nativeSetter.call(input, 'ㄱ');
      fire('input');
      fire('compositionupdate', 'ㄱ');

      nativeSetter.call(input, '가');
      fire('input');
      fire('compositionupdate', '가');

      fire('compositionend', '가');
    });

    // 조합 종료 직후 URL 의 q 파라미터가 "가" 로 갱신될 때까지 대기
    await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe('가');

    // 입력 box 의 표시 값은 자모로 깨지지 않고 "가" 가 그대로 유지된다
    await expect(searchInput).toHaveValue('가');

    // /datasets API 는 q=ㄱ 같은 조합 중간값으로는 호출되지 않았다
    const intermediateCalls = urls
      .slice(initialCallCount)
      .filter((u) => {
        const q = u.searchParams.get('search') ?? u.searchParams.get('q');
        return q === 'ㄱ';
      });
    expect(intermediateCalls).toHaveLength(0);

    // 최종 "가" 로는 호출되었다 (URL searchParams 키는 백엔드 측 'search')
    await expect
      .poll(() => urls.some((u) => u.searchParams.get('search') === '가'))
      .toBeTruthy();
  });

  test('한글이 아닌 영문 입력은 매 키 입력마다 그대로 onChange 가 발화된다', async ({
    authenticatedPage: page,
  }) => {
    const urls = await setup(page);
    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const searchInput = page.getByRole('textbox', { name: '데이터셋 검색...' });
    await searchInput.fill('gas');

    // URL 의 q 파라미터가 'gas' 로 갱신
    await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe('gas');
    // API 도 search=gas 로 호출됨
    await expect
      .poll(() => urls.some((u) => u.searchParams.get('search') === 'gas'))
      .toBeTruthy();
  });
});
