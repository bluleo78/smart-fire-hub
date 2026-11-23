/**
 * 이력 로딩 중 보내기 차단 E2E (WD-1004)
 *
 * 대화를 열어 이력을 불러오는 동안 보낸 글이 이력 도착과 함께 사라지던 문제를 막는다.
 * 로딩 중에는 Enter·보내기 버튼으로 보내지지 않고 쓴 글이 입력창에 남아야 하며,
 * 이력이 오면 같은 글을 보낼 수 있고 이력 아래에 남아야 한다.
 */

import { createMessage } from '../../factories/ai-insight.factory';
import { createAIMessage, createAISession } from '../../factories/ai-session.factory';
import { expect, test } from '../../fixtures/auth.fixture';

const SESSIONS = [createAISession({ title: '이전 대화 1' })];

const HISTORY = [
  createAIMessage({ id: 'user-001', role: 'user', content: '이력 질문입니다', timestamp: '2026-04-12T00:30:00Z' }),
  createAIMessage({ id: 'assistant-001', role: 'assistant', content: '이력 답변입니다', timestamp: '2026-04-12T00:30:05Z' }),
];

/** 패널을 열고 대화를 선택한다. 이력 응답은 release() 를 부를 때까지 미룬다. chat 요청 본문은 chatBodies 에 모은다. */
async function setup(page: import('@playwright/test').Page) {
  let release: () => void = () => {};
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const chatBodies: unknown[] = [];

  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) =>
      route.request().method() === 'GET'
        ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SESSIONS) })
        : route.continue(),
  );
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions/session-1/messages',
    async (route) => {
      await gate;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(HISTORY) });
    },
  );
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    (route) => {
      chatBodies.push(route.request().postDataJSON());
      return route.fulfill({
        status: 200,
        headers: { 'content-type': 'text/event-stream' },
        body: 'data: {"type":"done","inputTokens":1,"outputTokens":1}\n\n',
      });
    },
  );

  await page.goto('/', { waitUntil: 'commit' });
  await page.getByRole('button', { name: /AI 상태/ }).click();
  const input = page.getByPlaceholder('메시지를 입력하세요...');
  await input.waitFor({ state: 'visible', timeout: 5000 });

  await page.getByRole('button', { name: /대화 선택|이전 대화/ }).first().click();
  await page.getByRole('dialog', { name: '대화 선택' }).getByRole('button', { name: '이전 대화 1', exact: true }).click();
  return { input, chatBodies, release };
}

test.describe('AI 이력 로딩 중 보내기 차단 (WD-1004)', () => {
  test('이력을 불러오는 중에는 Enter·보내기 버튼으로 보내지지 않고 쓴 글이 남는다', async ({
    authenticatedPage: page,
  }) => {
    const { input, chatBodies, release } = await setup(page);
    const sendButton = page.getByRole('button', { name: '메시지 전송' });

    // 막힌 동안에는 이유를 알리는 안내가 보이고 입력창과 aria-describedby 로 이어진다
    const hint = page.getByText('대화 이력을 불러오는 중이라 잠시 뒤에 보낼 수 있습니다.');
    await expect(hint).toBeVisible();
    const hintId = await hint.getAttribute('id');
    expect(hintId).toBeTruthy();
    await expect(input).toHaveAttribute('aria-describedby', new RegExp(`(^| )${hintId}( |$)`));

    await input.fill('새 질문');
    await expect(sendButton).toBeDisabled();
    await input.press('Enter');

    await expect(input).toHaveValue('새 질문');
    await expect(input).toBeEnabled();

    // 이력이 도착한 뒤에도 늦은 chat 요청이 없어야 한다 — 도착을 기다린 뒤 확인해야 늦은 요청을 잡는다
    release();
    await expect(page.getByText('이력 답변입니다')).toBeVisible();
    expect(chatBodies).toHaveLength(0);
    await expect(input).toHaveValue('새 질문');
    // 로딩이 끝나면 안내가 사라지고 aria-describedby 도 걷힌다
    await expect(hint).toHaveCount(0);
    await expect(input).not.toHaveAttribute('aria-describedby', /.+/);
  });

  test('빠른 입력은 이력 로딩 중 보내지 않고 글을 남긴다', async ({ authenticatedPage: page }) => {
    const { chatBodies, release } = await setup(page);
    // 빠른 입력은 AI 패널이 닫혀 있을 때만 있다 — 패널을 닫아도 이력 로딩은 이어진다
    // Ctrl+K 로 패널을 닫는다(칩 클릭은 모드만 돌린다)
    await page.keyboard.press('Control+k');
    await page.mouse.move(0, 0);
    await page.getByRole('button', { name: /AI 상태/ }).hover();
    // 드롭다운은 호버 뒤 늦게 열린다 — 고정 대기 대신 빠른 입력이 보일 때까지 기다린다
    const quick = page.getByPlaceholder('AI에게 질문하기...');
    await quick.waitFor({ state: 'visible', timeout: 5000 });
    await quick.fill('빠른 질문');
    await expect(page.getByRole('button', { name: '질문 보내기' })).toBeDisabled();
    await quick.press('Enter');
    await expect(quick).toHaveValue('빠른 질문');
    await expect(quick).toBeEnabled();
    // 막힌 동안 안내가 보이고 입력란과 aria-describedby 로 이어진다
    // 패널(닫힘 상태로 남아 있음)에도 같은 문구가 있어 칩 드롭다운 안으로 범위를 좁힌다
    const hint = page.getByRole('dialog', { name: 'AI 상태 및 제어' }).getByText('대화 이력을 불러오는 중이라 잠시 뒤에 보낼 수 있습니다.');
    await expect(hint).toBeVisible();
    await expect(quick).toHaveAttribute('aria-describedby', (await hint.getAttribute('id')) ?? '');

    release();
    // 이력이 도착해 로딩이 끝나면(안내가 사라지면) 그 뒤에 chat 요청이 없는지 확인한다 — 도착 전에 세면 늦은 요청을 놓친다
    await expect(hint).toHaveCount(0);
    await expect(quick).not.toHaveAttribute('aria-describedby', /.+/);
    expect(chatBodies).toHaveLength(0);
    await expect(quick).toHaveValue('빠른 질문');
  });

  test('AI 에게 묻기는 이력 로딩 중이면 이력이 온 뒤 한 번만 보낸다', async ({ authenticatedPage: page }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/messages',
      (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([createMessage({ title: '일일 리포트' })]) }),
    );
    const { chatBodies, release } = await setup(page);
    await page.getByRole('button', { name: 'AI 인사이트 알림' }).click();
    await page.getByText('일일 리포트').first().click();
    await page.getByRole('button', { name: 'AI에게 물어보기' }).click();
    // 질문을 보관하면 알림 패널이 닫힌다 — 이 닫힘을 기다린 뒤, 로딩 중에는 보내지 않았는지 확인한다
    await expect(page.getByRole('button', { name: 'AI에게 물어보기' })).toHaveCount(0);
    expect(chatBodies).toHaveLength(0);

    release();
    await expect.poll(() => chatBodies.length).toBe(1);
    expect(chatBodies[0]).toMatchObject({ message: expect.stringContaining('일일 리포트') });
    // 이력이 화면에 반영된 뒤에도 한 번만 보냈는지 확인한다
    await expect(page.getByText('이력 답변입니다')).toBeVisible();
    expect(chatBodies).toHaveLength(1);
  });

  test('AI 에게 묻기 질문은 로딩 중 패널에서 새 대화를 시작하면 보내지 않는다', async ({ authenticatedPage: page }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/messages',
      (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([createMessage({ title: '일일 리포트' })]) }),
    );
    const { chatBodies, release } = await setup(page);
    await page.getByRole('button', { name: 'AI 인사이트 알림' }).click();
    await page.getByText('일일 리포트').first().click();
    await page.getByRole('button', { name: 'AI에게 물어보기' }).click();
    await expect(page.getByRole('button', { name: 'AI에게 물어보기' })).toHaveCount(0);

    // 로딩 중 패널의 대화 선택 목록에서 「새 대화」를 누른다
    await page.getByRole('button', { name: '새 대화', exact: true }).first().click();
    // 새 대화로 바뀌면 로딩 표시가 꺼져 보내기 막힘 안내가 사라진다
    await expect(page.getByText('대화 이력을 불러오는 중이라 잠시 뒤에 보낼 수 있습니다.')).toHaveCount(0);
    // 보관했던 질문을 버렸다고 알린다 (WD-1004)
    await expect(page.getByText('대화가 바뀌어 「AI 에게 물어보기」 질문을 보내지 않았습니다.')).toBeVisible();

    // 늦게 이력이 와도 보관했던 질문은 새 대화로 나가지 않는다
    // 응답 기다리기를 먼저 걸어 둔다 — release() 뒤에 걸면 그 사이 응답을 놓칠 수 있다
    const historyResponse = page.waitForResponse((res) => res.url().includes('/ai/sessions/session-1/messages'));
    release();
    await historyResponse;
    // 잘못 보내는 요청은 응답 뒤 effect 에서 나가므로, 화면이 자리 잡을 시간을 두고(요청 0 유지) 확인한다
    await page.waitForTimeout(600);
    expect(chatBodies).toHaveLength(0);
    await expect(page.getByText('이력 답변입니다')).toHaveCount(0);
  });

  test('이력이 오면 보낼 수 있고 보낸 글이 이력 아래에 남는다', async ({ authenticatedPage: page }) => {
    const { input, chatBodies, release } = await setup(page);
    const sendButton = page.getByRole('button', { name: '메시지 전송' });

    await input.fill('새 질문');
    release();
    await expect(page.getByText('이력 답변입니다')).toBeVisible();
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    await expect.poll(() => chatBodies.length).toBe(1);
    expect(chatBodies[0]).toMatchObject({ message: '새 질문' });
    await expect(page.getByText('이력 질문입니다')).toBeVisible();
    await expect(page.getByText('새 질문', { exact: true })).toBeVisible();
  });
});
