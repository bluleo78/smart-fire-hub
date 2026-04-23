// apps/firehub-web/e2e/ai/helpers/ai-chat.ts
import { expect,type Page } from '@playwright/test';

/** 실제 서버에 로그인 — 로그인 페이지 완전 로드 후 submit */
export async function loginWithRealCredentials(page: Page) {
  await page.goto('/login');
  await page.waitForSelector('button:has-text("로그인")', { state: 'visible', timeout: 15_000 });
  await page.getByLabel('아이디 (이메일)').fill('bluleo78@gmail.com');
  await page.getByLabel('비밀번호').fill('ehdgml88');
  await page.getByRole('button', { name: '로그인' }).click();
  await page.waitForURL(/^http:\/\/localhost:5173\/(?!login)/, { timeout: 60_000 });
}

/** AI 채팅 패널 열기 (AIStatusChip 클릭) */
export async function openAIChat(page: Page) {
  const chip = page.locator('[role="button"][aria-label*="AI 상태"]');
  await chip.waitFor({ state: 'visible', timeout: 10_000 });
  await chip.click();
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 10_000 });
}

/** 메시지 전송 (Enter 키 사용 — 버튼 셀렉터보다 안정적) */
export async function sendMessage(page: Page, text: string) {
  const input = page.getByPlaceholder('메시지를 입력하세요...');
  await input.fill(text);
  await input.press('Enter');
}

/** 스트리밍 완료 대기 (input 비활성→활성 전환 감지 — ThinkingIndicator보다 신뢰성 높음) */
export async function waitForResponse(page: Page, timeout = 90_000) {
  const input = page.getByPlaceholder('메시지를 입력하세요...');
  // 전송 후 input이 잠시 비활성화됨 — enabled가 될 때까지 대기
  await expect(input).toBeDisabled({ timeout: 5_000 }).catch(() => {});
  await expect(input).toBeEnabled({ timeout });
}

/** UI에 렌더링된 tool call 레이블 목록 반환 */
export async function getToolCallLabels(page: Page): Promise<string[]> {
  await page.waitForTimeout(500);
  // ToolCallDisplay: "my-1 flex items-center gap-1.5 rounded border border-border/50 bg-background/50 ..."
  // 컨테이너 내부의 span.font-medium이 레이블 텍스트를 담음
  const containers = page.locator('.rounded.border.border-border\\/50.bg-background\\/50');
  const count = await containers.count();
  const results: string[] = [];
  for (let i = 0; i < count; i++) {
    const label = containers.nth(i).locator('.font-medium').first();
    if (await label.count() > 0) {
      const text = (await label.textContent())?.trim() ?? '';
      if (text) results.push(text);
    }
  }
  return results;
}

/** 마지막 assistant 메시지 텍스트 반환 */
export async function getLastResponseText(page: Page): Promise<string> {
  // MessageBubble: assistant 메시지는 bg-muted 배경의 rounded-lg div
  // user 메시지(bg-primary)와 구분됨
  const assistantBubbles = page.locator('.rounded-lg.bg-muted');
  const count = await assistantBubbles.count();
  if (count === 0) {
    // 폴백: max-w- 클래스를 가진 마지막 버블
    return (await page.locator('[class*="max-w-"]').last().textContent()) ?? '';
  }
  return (await assistantBubbles.last().textContent()) ?? '';
}

/** 에러 없음 검증 */
export async function assertNoError(page: Page) {
  const text = await getLastResponseText(page);
  const errorKeywords = ['오류가 발생', '실패했습니다', '처리할 수 없', '서버 오류', 'Error:'];
  for (const kw of errorKeywords) {
    expect(text, `응답에 에러 키워드 "${kw}" 포함됨`).not.toContain(kw);
  }
}

/** 특정 tool 레이블이 호출됐는지 검증 */
export async function assertToolCalled(page: Page, expectedLabel: string) {
  const labels = await getToolCallLabels(page);
  expect(labels, `tool "${expectedLabel}" 이 호출되지 않음. 실제 호출: ${labels.join(', ')}`).toContain(expectedLabel);
}

/** 응답 텍스트가 비어있지 않음 검증 */
export async function assertResponseNotEmpty(page: Page) {
  const text = await getLastResponseText(page);
  expect(text.trim().length, '응답 텍스트가 비어있음').toBeGreaterThan(10);
}

/** 응답이 특정 텍스트를 포함하는지 검증 */
export async function assertResponseContains(page: Page, keyword: string) {
  const text = await getLastResponseText(page);
  expect(text, `응답에 "${keyword}" 미포함`).toContain(keyword);
}

/** 응답이 질문(추가 정보 요청)인지 검증 */
export async function assertResponseIsQuestion(page: Page) {
  const text = await getLastResponseText(page);
  const questionIndicators = ['?', '어떤', '무엇', '알려주', '입력해', '선택해', '어느', '몇', '어떻게', '원하시', '필요한'];
  const hasQuestion = questionIndicators.some(q => text.includes(q));
  expect(hasQuestion, `응답이 질문 형태가 아님: "${text.slice(0, 150)}"`).toBe(true);
}

/** 새 세션 시작 */
export async function startNewSession(page: Page) {
  const newBtn = page.locator('button[aria-label*="새 채팅"], button[aria-label*="새 세션"], button[title*="새"]');
  if (await newBtn.count() > 0) {
    await newBtn.first().click();
    await page.waitForTimeout(500);
  }
}

/** 로그인 + AI 채팅 열기를 한 번에 처리하는 setup 헬퍼 */
export async function setupAIChat(page: Page) {
  await loginWithRealCredentials(page);
  await openAIChat(page);
}
