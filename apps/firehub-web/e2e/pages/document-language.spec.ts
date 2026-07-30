import { expect, test } from '../fixtures/auth.fixture';

/**
 * 문서 언어 선언 E2E 회귀 테스트 (#343)
 * - UI가 전부 한국어인데 <html lang="en">이면 스크린리더가 영어 음성 엔진으로 읽어
 *   화면을 사실상 사용할 수 없다 (WCAG 3.1.1 Language of Page, Level A).
 * - Vite 템플릿 기본값(en)으로 되돌아가는 회귀를 막기 위해 실제 렌더된 문서에서 검증한다.
 */
test.describe('문서 언어 선언', () => {
  test('로그인 후 문서 언어가 ko로 선언된다', async ({ authenticatedPage: page }) => {
    await page.goto('/');

    // 렌더 결과의 html[lang] — 정적 선언이 런타임에 덮어써지지 않는지도 함께 확인된다
    await expect(page.locator('html')).toHaveAttribute('lang', 'ko');
  });

  test('로그인 페이지에서도 문서 언어가 ko로 선언된다', async ({ authMockedPage: page }) => {
    await page.goto('/login');

    // 인증 전 진입점도 동일한 언어 선언을 가져야 한다 (스크린리더 첫 접점)
    await expect(page.locator('html')).toHaveAttribute('lang', 'ko');
  });
});
