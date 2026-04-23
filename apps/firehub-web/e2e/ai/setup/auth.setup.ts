// e2e/ai/setup/auth.setup.ts
import { test as setup } from '@playwright/test';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const AI_AUTH_FILE = path.join(__dirname, '../.auth/user.json');

/** 실제 로그인 후 storageState 저장 — AI 테스트 전 1회만 실행 */
setup('AI 테스트 인증 설정', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('아이디 (이메일)').fill('bluleo78@gmail.com');
  await page.getByLabel('비밀번호').fill('ehdgml88');
  await page.getByRole('button', { name: '로그인' }).click();
  await page.waitForURL(/^http:\/\/localhost:5173\/(?!login)/, { timeout: 30_000 });
  await page.context().storageState({ path: AI_AUTH_FILE });
});
