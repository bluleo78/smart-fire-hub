import { expect, test } from '../fixtures/auth.fixture';

/**
 * AI 채팅 파일 첨부 사이즈 제한 E2E 테스트
 * - ChatInput 컴포넌트의 SIZE_LIMITS (DATA 256MB, 그 외 10MB) 검증
 * - 파일 크기가 제한 이내면 첨부 성공, 초과 시 toast 에러 표시 확인
 * - 백엔드 API 불필요 — 파일 크기 검증 로직은 클라이언트 사이드에서 수행됨
 */

test.describe('AI 채팅 파일 첨부 사이즈 제한', () => {
  /**
   * 테스트 1: IMAGE 파일 7MB → 첨부 성공 (한도 10MB 이내)
   */
  test('IMAGE 파일 7MB 첨부가 허용된다 (한도 10MB)', async ({ authenticatedPage: page }) => {
    await page.goto('/');

    const fileInput = page.locator('input[type="file"]').first();

    // 7MB 이미지 파일 (10MB 제한 이내 — 변경 전 5MB 제한 초과, 변경 후 허용)
    await fileInput.setInputFiles({
      name: 'test-image.png',
      mimeType: 'image/png',
      buffer: Buffer.alloc(7 * 1024 * 1024),
    });

    // 에러 토스트가 나타나지 않아야 한다 (사이즈 제한 에러 없음)
    await expect(page.getByText(/파일이 너무 큽니다/)).not.toBeVisible({ timeout: 2000 });
    // 파일 pill이 렌더링되어야 한다 — 입력→처리→출력 파이프라인 검증
    await expect(page.getByText('test-image.png')).toBeVisible({ timeout: 3000 });
  });

  /**
   * 테스트 2: IMAGE 파일 12MB → 에러 토스트 표시 (최대 10MB 메시지 확인)
   * - 12MB 이미지를 첨부하면 새 제한값 10MB 기준으로 에러가 표시되어야 한다
   * - 에러 메시지에서 "최대 10.0MB"가 보이는지 확인 → SIZE_LIMITS['IMAGE'] 검증
   */
  test('IMAGE 파일 12MB 초과 시 에러 토스트에 10MB 제한이 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/');

    const fileInput = page.locator('input[type="file"]').first();

    // 12MB 이미지 파일 (10MB 제한 초과)
    await fileInput.setInputFiles({
      name: 'large-image.png',
      mimeType: 'image/png',
      buffer: Buffer.alloc(12 * 1024 * 1024),
    });

    // 에러 토스트 — "파일이 너무 큽니다: large-image.png (최대 10.0MB)"
    // SIZE_LIMITS['IMAGE'] = 10MB임을 에러 메시지에서 직접 검증
    await expect(page.getByText(/파일이 너무 큽니다/)).toBeVisible({ timeout: 3000 });
    await expect(page.getByText(/최대 10\.0MB/)).toBeVisible({ timeout: 3000 });
  });

  /**
   * 테스트 3: DATA 파일(CSV) 6MB → 첨부 성공 (한도 256MB)
   * - 256MB 전체 검증은 Playwright buffer 한도로 불가 — 6MB 문턱 통과로 대체
   */
  test('DATA 파일(CSV) 6MB 첨부가 허용된다 (한도 256MB)', async ({ authenticatedPage: page }) => {
    await page.goto('/');

    const fileInput = page.locator('input[type="file"]').first();

    // 6MB CSV 파일 (256MB 제한 이내 — 변경 전 5MB 제한 초과, 변경 후 허용)
    await fileInput.setInputFiles({
      name: 'data.csv',
      mimeType: 'text/csv',
      buffer: Buffer.alloc(6 * 1024 * 1024),
    });

    // 에러 토스트가 나타나지 않아야 한다
    await expect(page.getByText(/파일이 너무 큽니다/)).not.toBeVisible({ timeout: 2000 });
    // 파일 pill이 렌더링되어야 한다 — 첨부 성공 검증
    await expect(page.getByText('data.csv')).toBeVisible({ timeout: 3000 });
  });

  /**
   * 테스트 4: PDF 파일 8MB → 첨부 성공 (한도 10MB 이내)
   */
  test('PDF 파일 8MB 첨부가 허용된다 (한도 10MB)', async ({ authenticatedPage: page }) => {
    await page.goto('/');

    const fileInput = page.locator('input[type="file"]').first();

    // 8MB PDF (한도 10MB 이내)
    await fileInput.setInputFiles({
      name: 'document.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.alloc(8 * 1024 * 1024),
    });

    // 에러 토스트가 나타나지 않아야 한다
    await expect(page.getByText(/파일이 너무 큽니다/)).not.toBeVisible({ timeout: 2000 });
    // 파일 pill이 렌더링되어야 한다
    await expect(page.getByText('document.pdf')).toBeVisible({ timeout: 3000 });
  });
});
