/**
 * SessionSwitcher(사이드·플로팅 패널 헤더의 대화 선택 목록) — 키보드 삭제 회귀 테스트 (WD-951)
 *
 * 수정 전 결함: 대화 줄이 DropdownMenuItem 이고 그 안에 삭제 버튼·확인 창이 들어 있어, 메뉴가
 * 초점을 항목으로만 옮기고 Enter·Space 를 항목 선택으로 먹었다 — 키보드로는 삭제할 수 없었다.
 *
 * 키보드 시험은 여는 버튼에 초점을 둔 뒤 page.keyboard 로 진행한다. 마우스 시험(줄 전체 클릭 영역·
 * 마우스로 열었을 때의 강조·목록을 닫은 뒤의 초점)은 각자 describe 에서 마우스를 직접 쓴다.
 * 초점은 toBeFocused 로, 서버 쪽 결과는 요청 수·종류로 직접 센다.
 */

import type { Page } from '@playwright/test';

import type { AISession } from '../../../src/types/ai';
import { createAISession } from '../../factories/ai-session.factory';
import { expect, test } from '../../fixtures/auth.fixture';

const MOCK_SESSIONS: AISession[] = [
  createAISession({ id: 1, sessionId: 'sw-1', title: '첫째 대화', updatedAt: '2026-11-23T03:00:00Z' }),
  createAISession({ id: 2, sessionId: 'sw-2', title: '둘째 대화', updatedAt: '2026-11-23T02:00:00Z' }),
  createAISession({ id: 3, sessionId: 'sw-3', title: '셋째 대화', updatedAt: '2026-11-23T01:00:00Z' }),
];

/** 버튼을 누르는 두 키 — 둘 다 같은 결과여야 한다 */
const ACTIVATE_KEYS = ['Enter', 'Space'];

interface Requests {
  /** GET /sessions/:sessionId/messages 로 조회된 sessionId 들 */
  messages: string[];
  /** DELETE /sessions/:id 로 지워진 id 들 */
  deletes: string[];
  /** 서버가 쥔 세션 목록을 바꾼다(다음 목록 GET 부터 반영) */
  setSessions: (next: AISession[]) => void;
  /** holdRefetch 로 붙잡아 둔 DELETE 뒤 목록 GET 을 풀어 준다 */
  release: () => void;
  /** slowDeleteIds 로 붙잡아 둔 그 id 의 DELETE 응답을 풀어 준다 */
  releaseDelete: (id: string) => void;
}

interface PanelOptions {
  /** DELETE 응답 상태 — 기본 204(성공). 성공이면 서버 목록에서도 그 줄을 뺀다 */
  deleteStatus?: number;
  /** true 면 DELETE 뒤의 목록 GET 을 release() 까지 붙잡아 줄이 사라지는 시점을 시험이 쥔다 */
  holdRefetch?: boolean;
  /** 이 id 들의 DELETE 응답은 releaseDelete(id) 까지 붙잡는다 — 삭제 둘이 겹칠 때 응답 순서를 시험이 쥔다 */
  slowDeleteIds?: string[];
  /** 이 id 들의 DELETE 만 500 으로 실패시킨다(나머지는 deleteStatus) */
  failDeleteIds?: string[];
}

/** 패널을 열고 대화 3개를 모킹한 뒤 요청 기록을 돌려준다 */
async function openPanel(page: Page, sessions: AISession[] = MOCK_SESSIONS, opts: PanelOptions = {}): Promise<Requests> {
  let serverSessions = sessions;
  let gate: Promise<void> | null = null;
  const deleteGates = new Map<string, () => void>();
  const requests: Requests = {
    messages: [],
    deletes: [],
    setSessions: (next) => {
      serverSessions = next;
    },
    release: () => {},
    releaseDelete: (id) => deleteGates.get(id)?.(),
  };
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    async (route) => {
      const method = route.request().method();
      if (method === 'GET') {
        if (gate) await gate;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(serverSessions) });
      }
      if (method === 'POST') {
        // 채팅 첫 전송이 만드는 새 세션 — 서버 목록에 더한다
        const body = route.request().postDataJSON() as { sessionId: string; title: string };
        const created = createAISession({ id: 900, ...body });
        serverSessions = [created];
        return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(created) });
      }
      return route.continue();
    },
  );
  await page.route(
    (url) => /^\/api\/v1\/ai\/sessions\/[^/]+\/messages$/.test(url.pathname),
    (route) => {
      const m = new URL(route.request().url()).pathname.match(/sessions\/([^/]+)\/messages/);
      requests.messages.push(m?.[1] ?? '');
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    },
  );
  await page.route(
    (url) => /^\/api\/v1\/ai\/sessions\/\d+$/.test(url.pathname),
    async (route) => {
      if (route.request().method() === 'DELETE') {
        const id = new URL(route.request().url()).pathname.split('/').pop() ?? '';
        requests.deletes.push(id);
        if (opts.slowDeleteIds?.includes(id)) {
          await new Promise<void>((resolve) => deleteGates.set(id, resolve));
        }
        const status = opts.failDeleteIds?.includes(id) ? 500 : (opts.deleteStatus ?? 204);
        if (status >= 400) {
          return route.fulfill({ status, contentType: 'application/json', body: '{"message":"fail"}' });
        }
        serverSessions = serverSessions.filter((s) => String(s.id) !== id);
        if (opts.holdRefetch) {
          gate = new Promise<void>((resolve) => {
            requests.release = () => {
              gate = null;
              resolve();
            };
          });
        }
        return route.fulfill({ status: 204 });
      }
      return route.continue();
    },
  );
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByRole('button', { name: /AI 상태/ }).click();
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5_000 });
  return requests;
}

/** 여는 버튼에 초점을 두고 Enter 로 펼친다 — 이후는 키보드만 쓴다 */
async function openListByKeyboard(page: Page) {
  const trigger = page.getByRole('button', { name: '대화 선택', exact: true });
  await trigger.focus();
  await page.keyboard.press('Enter');
  const list = page.getByRole('dialog', { name: '대화 선택' });
  await expect(list).toBeVisible();
  return { trigger, list };
}

const titleBtn = (page: Page, title: string) =>
  page.getByRole('dialog', { name: '대화 선택' }).getByRole('button', { name: title, exact: true });
const deleteBtn = (page: Page, title: string) =>
  page.getByRole('dialog', { name: '대화 선택' }).getByRole('button', { name: `${title} 삭제`, exact: true });
/** 초점이 어느 조작부에도 없는가 — 되돌리지 않았음을 보려고 재시도 없이 그 순간 값을 읽는다 */
const focusIsOnBody = (page: Page) => page.evaluate(() => document.activeElement === document.body);
const confirm = (page: Page) => page.getByRole('alertdialog');

/**
 * 시계를 staleTime(30초, main.tsx) 너머로 돌리고 망 끊김→다시 붙음을 보내 대화 목록을 다시 읽게 한다.
 * page.clock.install() 을 한 시험에서만 쓴다 — 삭제 응답을 붙잡은 채 재조회가 먼저 오는 순서를 만든다.
 */
async function refetchStaleSessions(page: Page) {
  await page.clock.fastForward(31_000);
  await page.evaluate(() => {
    window.dispatchEvent(new Event('offline'));
    window.dispatchEvent(new Event('online'));
  });
}

/**
 * 목록을 마우스로 연다 — 열릴 때 zoom-in 애니메이션이 끝나 줄 높이가 32px 로 자리 잡을 때까지 기다린다.
 * 애니메이션 중엔 좌표가 배율로 줄어 보여, 그 사이에 잰 boundingBox 로 누르면 엉뚱한 곳(휴지통 등)을 누르게 된다.
 */
async function openListByMouse(page: Page, firstTitle: string = '첫째 대화') {
  await page.getByRole('button', { name: '대화 선택', exact: true }).click();
  const list = page.getByRole('dialog', { name: '대화 선택' });
  await expect(list).toBeVisible();
  // 소수 px(배율·반올림)로 와도 기다림이 끝나도록 반올림해 비교한다(WD-1010 리뷰 후속)
  await expect.poll(async () => Math.round((await titleBtn(page, firstTitle).boundingBox())?.height ?? 0)).toBe(32);
}

test.describe('SessionSwitcher — 키보드 삭제 (WD-951)', () => {
  test('열면 첫 제목에 초점, Tab 은 제목 → 삭제 → 다음 줄 제목으로 가고 목록 안에서 돈다', async ({
    authenticatedPage: page,
  }) => {
    await openPanel(page);
    const { list } = await openListByKeyboard(page);

    await expect(list.getByRole('list', { name: '최근 대화' }).getByRole('listitem')).toHaveCount(3);
    await expect(titleBtn(page, '첫째 대화')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(deleteBtn(page, '첫째 대화')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(titleBtn(page, '둘째 대화')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(deleteBtn(page, '둘째 대화')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(titleBtn(page, '셋째 대화')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(deleteBtn(page, '셋째 대화')).toBeFocused();

    // 마지막 삭제에서 Tab → 첫 제목(순환), 첫 제목에서 Shift+Tab → 마지막 삭제
    await page.keyboard.press('Tab');
    await expect(titleBtn(page, '첫째 대화')).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(deleteBtn(page, '셋째 대화')).toBeFocused();
  });

  for (const key of ACTIVATE_KEYS) {
    test(`삭제 버튼에서 ${key} 는 삭제 확인 창만 연다 (대화 선택·메시지 조회·삭제 요청 없음)`, async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page);
      await openListByKeyboard(page);
      await page.keyboard.press('Tab');
      await expect(deleteBtn(page, '첫째 대화')).toBeFocused();

      await page.keyboard.press(key);
      await expect(confirm(page)).toBeVisible();
      await expect(confirm(page).getByRole('button', { name: '취소' })).toBeFocused();
      expect(requests.messages).toEqual([]);
      expect(requests.deletes).toEqual([]);
    });
  }

  for (const key of ACTIVATE_KEYS) {
    test(`확인 창 취소를 ${key} 로 누르면 창만 닫히고 그 삭제 버튼에 초점이 돌아온다 (요청 없음)`, async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page);
      await openListByKeyboard(page);
      await page.keyboard.press('Tab');
      await page.keyboard.press('Enter');
      await expect(confirm(page).getByRole('button', { name: '취소' })).toBeFocused();

      await page.keyboard.press(key);
      await expect(confirm(page)).toHaveCount(0);
      await expect(page.getByRole('dialog', { name: '대화 선택' })).toBeVisible();
      await expect(deleteBtn(page, '첫째 대화')).toBeFocused();
      expect(requests.messages).toEqual([]);
      expect(requests.deletes).toEqual([]);
    });

    test(`확인 창 삭제를 ${key} 로 누르면 그 id 의 DELETE 가 정확히 1번 나가고 메시지 조회는 없다`, async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page);
      await openListByKeyboard(page);
      // 둘째 대화의 삭제 버튼까지: 제목1 → 삭제1 → 제목2 → 삭제2
      for (let i = 0; i < 3; i += 1) await page.keyboard.press('Tab');
      await expect(deleteBtn(page, '둘째 대화')).toBeFocused();
      await page.keyboard.press('Enter');
      // 사용자가 가는 길 그대로: 확인 창은 「취소」에 초점이 있고 Tab 한 번이면 「삭제」다
      await expect(confirm(page).getByRole('button', { name: '취소' })).toBeFocused();
      await page.keyboard.press('Tab');
      await expect(confirm(page).getByRole('button', { name: '삭제', exact: true })).toBeFocused();

      await page.keyboard.press(key);
      await expect(confirm(page)).toHaveCount(0);
      await expect.poll(() => requests.deletes).toEqual(['2']);
      expect(requests.messages).toEqual([]);
    });
  }

  for (const key of ACTIVATE_KEYS) {
    test(`제목에서 ${key} 는 그 대화를 열고 목록을 닫으며 초점은 여는 버튼에 둔다`, async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page);
      await openListByKeyboard(page);
      await page.keyboard.press('Tab');
      await page.keyboard.press('Tab');
      await expect(titleBtn(page, '둘째 대화')).toBeFocused();

      await page.keyboard.press(key);
      await expect.poll(() => requests.messages).toEqual(['sw-2']);
      await expect(page.getByRole('dialog', { name: '대화 선택' })).toHaveCount(0);
      const trigger = page.getByRole('button', { name: '둘째 대화', exact: true });
      await expect(trigger).toBeFocused();
    });
  }

  test('Esc 는 확인 창 → 목록 순으로 한 층씩 닫는다', async ({ authenticatedPage: page }) => {
    await openPanel(page);
    const { trigger } = await openListByKeyboard(page);
    await page.keyboard.press('Tab');
    await expect(deleteBtn(page, '첫째 대화')).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(confirm(page)).toBeVisible();

    await page.keyboard.press('Escape');
    await expect(confirm(page)).toHaveCount(0);
    await expect(page.getByRole('dialog', { name: '대화 선택' })).toBeVisible();
    await expect(deleteBtn(page, '첫째 대화')).toBeFocused();

    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog', { name: '대화 선택' })).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });

  test.describe('삭제 뒤 초점 (WD-951)', () => {
    /** 키보드로 해당 줄의 삭제 버튼까지 가서 확인 창의 삭제를 누른다 */
    async function deleteByKeyboard(page: Page, title: string) {
      await deleteBtn(page, title).focus();
      await page.keyboard.press('Enter');
      await expect(confirm(page)).toBeVisible();
      await confirm(page).getByRole('button', { name: '삭제', exact: true }).focus();
      await page.keyboard.press('Enter');
      await expect(confirm(page)).toHaveCount(0);
    }

    test('가운데 줄을 지우면 줄이 사라진 뒤 다음 줄 제목에 초점이 간다', async ({ authenticatedPage: page }) => {
      const requests = await openPanel(page, MOCK_SESSIONS, { slowDeleteIds: ['2'] });
      await openListByKeyboard(page);
      await deleteByKeyboard(page, '둘째 대화');
      await expect.poll(() => requests.deletes).toEqual(['2']);

      // 목록 갱신 전: 줄이 아직 있다. 시험이 갱신을 풀어 줄이 실제로 사라진 다음을 본다.
      await expect(titleBtn(page, '둘째 대화')).toHaveCount(1);
      requests.releaseDelete('2');
      await expect(titleBtn(page, '둘째 대화')).toHaveCount(0);
      await expect(titleBtn(page, '셋째 대화')).toBeFocused();
    });

    test('끝 줄을 지우면 이전 줄 제목에 초점이 간다', async ({ authenticatedPage: page }) => {
      const requests = await openPanel(page, MOCK_SESSIONS, { slowDeleteIds: ['3'] });
      await openListByKeyboard(page);
      await deleteByKeyboard(page, '셋째 대화');
      await expect.poll(() => requests.deletes).toEqual(['3']);

      requests.releaseDelete('3');
      await expect(titleBtn(page, '셋째 대화')).toHaveCount(0);
      await expect(titleBtn(page, '둘째 대화')).toBeFocused();
    });

    test('하나뿐인 줄을 지우면 목록이 닫히고 「새 대화」에 초점이 간다', async ({ authenticatedPage: page }) => {
      const requests = await openPanel(page, [MOCK_SESSIONS[0]], { slowDeleteIds: ['1'] });
      await openListByKeyboard(page);
      await deleteByKeyboard(page, '첫째 대화');
      await expect.poll(() => requests.deletes).toEqual(['1']);

      requests.releaseDelete('1');
      await expect(page.getByText('대화 이력 없음')).toBeVisible();
      await expect(page.getByRole('dialog', { name: '대화 선택' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: '새 대화' })).toBeFocused();
    });

    test('마지막 대화를 지운 뒤 새 대화가 생겨도(채팅 전송) 목록은 저절로 열리지 않는다', async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page, [MOCK_SESSIONS[0]], { slowDeleteIds: ['1'] });
      await page.route(
        (url) => url.pathname === '/api/v1/ai/chat',
        (route) =>
          route.fulfill({
            status: 200,
            headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
            body: `data: ${JSON.stringify({ type: 'init', sessionId: 'sw-new' })}\n\ndata: ${JSON.stringify({ type: 'done' })}\n\n`,
          }),
      );
      await openListByKeyboard(page);
      await deleteByKeyboard(page, '첫째 대화');
      requests.releaseDelete('1');
      await expect(page.getByRole('button', { name: '새 대화' })).toBeFocused();

      // 실제 무효화 경로: 첫 메시지 전송 → 세션 생성 POST → 목록 무효화 → 목록 1개로 갱신
      const input = page.getByPlaceholder('메시지를 입력하세요...');
      await input.focus();
      await page.keyboard.type('안녕');
      await page.keyboard.press('Enter');
      const opener = page.getByRole('button', { name: '안녕', exact: true });
      await expect(opener).toBeVisible();
      await expect(page.getByRole('dialog', { name: '대화 선택' })).toHaveCount(0);
      await expect(opener).toHaveAttribute('aria-expanded', 'false');
    });

    test('갱신 전에 Tab 으로 다른 줄로 옮긴 초점은 갱신 뒤에도 빼앗기지 않는다', async ({ authenticatedPage: page }) => {
      const requests = await openPanel(page, MOCK_SESSIONS, { slowDeleteIds: ['1'] });
      await openListByKeyboard(page);
      await deleteByKeyboard(page, '첫째 대화');
      await expect.poll(() => requests.deletes).toEqual(['1']);
      await expect(deleteBtn(page, '첫째 대화')).toBeFocused();

      await page.keyboard.press('Tab');
      await page.keyboard.press('Tab');
      await expect(deleteBtn(page, '둘째 대화')).toBeFocused();
      requests.releaseDelete('1');
      await expect(titleBtn(page, '첫째 대화')).toHaveCount(0);
      await expect(deleteBtn(page, '둘째 대화')).toBeFocused();
    });

    test('갱신 전에 Esc 로 목록을 닫으면 갱신 뒤에도 여는 버튼에 초점이 남는다', async ({ authenticatedPage: page }) => {
      const requests = await openPanel(page, MOCK_SESSIONS, { slowDeleteIds: ['1'] });
      const { trigger } = await openListByKeyboard(page);
      await deleteByKeyboard(page, '첫째 대화');
      await expect.poll(() => requests.deletes).toEqual(['1']);

      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog', { name: '대화 선택' })).toHaveCount(0);
      await expect(trigger).toBeFocused();
      requests.releaseDelete('1');
      await expect(page.getByRole('button', { name: '대화 선택', exact: true })).toBeFocused();
      await expect(page.getByRole('dialog', { name: '대화 선택' })).toHaveCount(0);
    });

    test('목록 갱신을 붙잡지 않아도(일반 경로) 가운데 줄을 지우면 다음 줄 제목에 초점이 간다', async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page);
      await openListByKeyboard(page);
      await deleteByKeyboard(page, '둘째 대화');
      await expect.poll(() => requests.deletes).toEqual(['2']);

      await expect(titleBtn(page, '둘째 대화')).toHaveCount(0);
      await expect(titleBtn(page, '셋째 대화')).toBeFocused();
    });

    test('삭제 둘이 겹쳐 응답 순서가 뒤바뀌어도 초점이 body 로 빠지지 않고 남은 줄 제목에 간다', async ({
      authenticatedPage: page,
    }) => {
      // 둘째 삭제는 느리게, 첫째 삭제는 바로 — 먼저 보낸 느린 삭제가 기억에서 빠지면(하나만 기억하면)
      // 그 줄이 사라질 때 초점을 아무도 옮기지 않는다
      const requests = await openPanel(page, MOCK_SESSIONS, { slowDeleteIds: ['2'] });
      await openListByKeyboard(page);
      await deleteByKeyboard(page, '둘째 대화');
      await expect.poll(() => requests.deletes).toEqual(['2']);
      await deleteByKeyboard(page, '첫째 대화');
      await expect.poll(() => requests.deletes).toEqual(['2', '1']);
      await expect(titleBtn(page, '첫째 대화')).toHaveCount(0);
      await expect(titleBtn(page, '둘째 대화')).toBeFocused();

      requests.releaseDelete('2');
      await expect(titleBtn(page, '둘째 대화')).toHaveCount(0);
      await expect(titleBtn(page, '셋째 대화')).toBeFocused();
    });

    test('목록을 닫고 초점이 body 인 채 갱신이 와도 대화가 남아 있으면 「새 대화」로 초점을 끌어가지 않는다', async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page, MOCK_SESSIONS, { slowDeleteIds: ['2'] });
      // 첫째 대화를 현재 대화로 연다(목록은 선택하면 닫힌다)
      await openListByKeyboard(page);
      await page.keyboard.press('Enter');
      await expect.poll(() => requests.messages).toEqual(['sw-1']);
      await page.getByRole('button', { name: '첫째 대화', exact: true }).click();
      await deleteByKeyboard(page, '둘째 대화');
      await expect.poll(() => requests.deletes).toEqual(['2']);

      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog', { name: '대화 선택' })).toHaveCount(0);
      // 초점 못 받는 곳(패널 머리말의 빈 자리)을 눌러 초점을 body 로 보낸다
      await page.mouse.click(2, 2);
      expect(await focusIsOnBody(page)).toBe(true);

      const refetched = page.waitForResponse(
        (r) => new URL(r.url()).pathname === '/api/v1/ai/sessions' && r.request().method() === 'GET',
      );
      requests.releaseDelete('2');
      await refetched;
      // 갱신 결과가 렌더·효과까지 반영될 한 프레임을 기다린다(타이머 아님)
      await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
      await expect(page.getByRole('button', { name: '새 대화' })).not.toBeFocused();
      expect(await focusIsOnBody(page)).toBe(true);
      // 현재 대화는 그대로 — 다른 대화를 열지도 새 대화를 시작하지도 않았다
      await expect(page.getByRole('button', { name: '첫째 대화', exact: true })).toBeVisible();
      // 같은 대화(sw-1)의 메시지 재조회는 타이밍에 따라 한 번 더 나갈 수 있어(시작점에서도 6번 중 3번) 「다른 대화를 열지 않음」만 본다
      expect([...new Set(requests.messages)]).toEqual(['sw-1']);
    });

    test('삭제 응답보다 목록 갱신이 먼저 와서 줄이 사라져도 다음 줄 제목에 초점이 간다', async ({
      authenticatedPage: page,
    }) => {
      await page.clock.install();
      const requests = await openPanel(page, MOCK_SESSIONS, { slowDeleteIds: ['2'] });
      await openListByKeyboard(page);
      await deleteByKeyboard(page, '둘째 대화');
      await expect.poll(() => requests.deletes).toEqual(['2']);
      await expect(deleteBtn(page, '둘째 대화')).toBeFocused();

      // 서버에서는 이미 지워졌고 응답만 늦다. 목록이 낡은 뒤(30초) 망이 다시 붙으면 응답보다 먼저 다시 읽는다.
      requests.setSessions([MOCK_SESSIONS[0], MOCK_SESSIONS[2]]);
      await refetchStaleSessions(page);
      await expect(titleBtn(page, '둘째 대화')).toHaveCount(0);
      await expect(titleBtn(page, '셋째 대화')).toBeFocused();
      requests.releaseDelete('2');
    });

    test('하나뿐인 줄을 지우고 Esc 로 닫아 여는 버튼에 있던 초점은 갱신 뒤 「새 대화」로 간다', async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page, [MOCK_SESSIONS[0]], { slowDeleteIds: ['1'] });
      const { trigger } = await openListByKeyboard(page);
      await deleteByKeyboard(page, '첫째 대화');
      await expect.poll(() => requests.deletes).toEqual(['1']);
      await page.keyboard.press('Escape');
      await expect(trigger).toBeFocused();

      requests.releaseDelete('1');
      await expect(page.getByText('대화 이력 없음')).toBeVisible();
      await expect(page.getByRole('button', { name: '새 대화' })).toBeFocused();
    });

    test('마지막 대화를 지우고 목록 밖 빈 자리를 누른 뒤 갱신이 와도 「새 대화」로 초점을 끌어가지 않는다', async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page, [MOCK_SESSIONS[0]], { slowDeleteIds: ['1'] });
      const { trigger } = await openListByKeyboard(page);
      await deleteByKeyboard(page, '첫째 대화');
      await expect.poll(() => requests.deletes).toEqual(['1']);
      await page.keyboard.press('Escape');
      await expect(trigger).toBeFocused();
      await page.mouse.click(2, 2);
      expect(await focusIsOnBody(page)).toBe(true);

      requests.releaseDelete('1');
      await expect(page.getByText('대화 이력 없음')).toBeVisible();
      await expect(page.getByRole('button', { name: '새 대화' })).not.toBeFocused();
      expect(await focusIsOnBody(page)).toBe(true);
    });

    test('이웃 줄도 함께 사라지면 목록 순서의 첫 남은 줄로 간다 — 나중에 생긴 줄이 위에 있어도 (리뷰 후속 ①)', async ({
      authenticatedPage: page,
    }) => {
      // WD-973: 성공(204)은 캐시에서 그 줄만 즉시 뺀다 — 지운 줄(셋째)은 DELETE 응답을 붙잡아 사라지는 시점을 쥐고,
      // 이웃(둘째)은 서버 목록이 바뀐 재조회로 함께 사라지게 한다(삭제는 셋째 하나만).
      // 시계를 쥐어 목록 조회를 낡게(staleTime 30초) 만든 뒤 재연결 이벤트로 재조회를 일으킨다
      await page.clock.install();
      const requests = await openPanel(page, MOCK_SESSIONS, { slowDeleteIds: ['3'] });
      await openListByKeyboard(page);
      await deleteByKeyboard(page, '셋째 대화');
      await expect.poll(() => requests.deletes).toEqual(['3']);

      // 그사이 다른 곳에서 둘째(이웃)가 지워지고 새 대화가 맨 위에 생겼다 — 목록 순서의 첫 줄인 새 대화로 가야 한다
      // (인라인 ref 는 렌더마다 다시 붙어 지금은 Map 순서도 같지만, 그 구현 세부에 기대지 않음을 지킨다)
      const created = createAISession({ id: 7, sessionId: 'sw-7', title: '새로 생긴 대화', updatedAt: '2026-11-23T04:00:00Z' });
      requests.setSessions([created, MOCK_SESSIONS[0]]);
      // 삭제 응답은 아직 붙잡힌 채 재조회(서버의 새 목록)가 먼저 도착한다 — 지운 줄(셋째)과 이웃(둘째)이 한 갱신에 함께 사라진다
      await refetchStaleSessions(page);
      await expect(titleBtn(page, '셋째 대화')).toHaveCount(0);
      await expect(titleBtn(page, '둘째 대화')).toHaveCount(0);
      await expect(titleBtn(page, '새로 생긴 대화')).toBeFocused();
      // 붙잡은 요청을 모두 풀어 둔다
      requests.releaseDelete('3');
    });

    test('실패한 삭제는 기억에서 빠져, 뒤에 그 줄이 다른 까닭으로 사라져도 초점을 그 이웃으로 끌지 않는다 (리뷰 후속 ②)', async ({
      authenticatedPage: page,
    }) => {
      const four = createAISession({ id: 4, sessionId: 'sw-4', title: '넷째 대화', updatedAt: '2026-11-23T00:00:00Z' });
      // 시계를 쥐어 목록 조회를 낡게(staleTime 30초) 만든 뒤 재연결 이벤트로 재조회를 일으킨다
      await page.clock.install();
      // 셋째 삭제는 즉시 500, 첫째 삭제는 응답을 붙잡는다
      const requests = await openPanel(page, [...MOCK_SESSIONS, four], { slowDeleteIds: ['1'], failDeleteIds: ['3'] });
      await openListByKeyboard(page);
      // 셋째 삭제는 실패한다(이웃은 넷째)
      await deleteByKeyboard(page, '셋째 대화');
      await expect(page.getByText('대화 삭제에 실패했습니다.')).toBeVisible();
      await expect(deleteBtn(page, '셋째 대화')).toBeFocused();

      // 첫째를 지운다(이웃은 둘째) — 응답은 붙잡혀 있어 첫째 줄이 아직 남는다.
      // WD-973: 성공(204)은 캐시에서 그 줄만 즉시 빼므로, 두 줄이 한 갱신에 사라지는 상황은 응답 전에 도착한 재조회로 만든다.
      await deleteByKeyboard(page, '첫째 대화');
      await expect.poll(() => requests.deletes).toEqual(['3', '1']);
      await expect(titleBtn(page, '첫째 대화')).toHaveCount(1);
      // 그사이 다른 곳에서 셋째가 지워졌다 — 서버 목록에서 첫째·셋째가 모두 빠진 재조회가 한 번에 도착한다
      requests.setSessions([MOCK_SESSIONS[1], four]);
      await refetchStaleSessions(page);
      await expect(titleBtn(page, '첫째 대화')).toHaveCount(0);
      await expect(titleBtn(page, '셋째 대화')).toHaveCount(0);
      // 실패한 셋째의 기억이 남아 있으면 먼저 기억한 그 이웃(넷째)으로 간다
      await expect(titleBtn(page, '둘째 대화')).toBeFocused();
      // 붙잡은 요청을 모두 풀어 둔다
      requests.releaseDelete('1');
    });

    test('삭제가 500 으로 실패하면 오류 토스트, 줄은 남고 초점은 그 삭제 버튼에 돌아온다', async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page, MOCK_SESSIONS, { deleteStatus: 500 });
      await openListByKeyboard(page);
      await deleteByKeyboard(page, '둘째 대화');
      await expect.poll(() => requests.deletes).toEqual(['2']);

      await expect(page.getByText('대화 삭제에 실패했습니다.')).toBeVisible();
      await expect(titleBtn(page, '둘째 대화')).toHaveCount(1);
      await expect(deleteBtn(page, '둘째 대화')).toBeFocused();
    });
  });

  test.describe('마우스로 열 때 (WD-951)', () => {
    const rowBg = (page: Page, index: number) =>
      page
        .getByRole('dialog', { name: '대화 선택' })
        .getByRole('listitem')
        .nth(index)
        .evaluate((el) => getComputedStyle(el).backgroundColor);
    const TRANSPARENT = 'rgba(0, 0, 0, 0)';

    test('현재 대화가 첫 줄이 아니어도 마우스로 열면 현재 줄만 강조된다 (첫 제목의 초점은 강조하지 않는다)', async ({
      authenticatedPage: page,
    }) => {
      await openPanel(page);
      await page.getByRole('button', { name: '대화 선택', exact: true }).click();
      await titleBtn(page, '둘째 대화').click();
      await expect(page.getByRole('dialog', { name: '대화 선택' })).toHaveCount(0);

      await page.getByRole('button', { name: '둘째 대화', exact: true }).click();
      await expect(titleBtn(page, '첫째 대화')).toBeFocused();
      // 마우스를 목록 밖에 두어 hover 가 섞이지 않게 한다
      await page.mouse.move(2, 2);
      const current = await rowBg(page, 1);
      expect(current).not.toBe(TRANSPARENT);
      expect(await rowBg(page, 0)).toBe(TRANSPARENT);
      expect(await rowBg(page, 2)).toBe(TRANSPARENT);
    });

    test('현재 줄(bg-accent) 위 휴지통 아이콘의 대비가 밝은·어두운 테마 모두 3:1 이상이다 (리뷰 후속 ③, WCAG 1.4.11)', async ({
      authenticatedPage: page,
    }) => {
      await openPanel(page);
      await page.getByRole('button', { name: '대화 선택', exact: true }).click();
      await titleBtn(page, '첫째 대화').click();
      await page.getByRole('button', { name: '첫째 대화', exact: true }).click();
      await page.mouse.move(2, 2);
      const contrast = () =>
        deleteBtn(page, '첫째 대화').evaluate((btn) => {
          // 색 문자열을 캔버스로 sRGB 로 바꾼다(oklch 등도 읽힌다)
          const ctx = document.createElement('canvas').getContext('2d')!;
          const rgba = (c: string) => {
            ctx.clearRect(0, 0, 1, 1);
            ctx.fillStyle = c;
            ctx.fillRect(0, 0, 1, 1);
            const d = ctx.getImageData(0, 0, 1, 1).data;
            return [d[0], d[1], d[2], d[3] / 255];
          };
          const over = (top: number[], below: number[]) => {
            const a = top[3];
            return [0, 1, 2].map((i) => top[i] * a + below[i] * (1 - a)).concat(1);
          };
          const lum = ([r, g, b]: number[]) => {
            const f = (v: number) => {
              const x = v / 255;
              return x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4;
            };
            return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
          };
          // 줄 배경은 반투명일 수 있어(어두운 테마 accent 9%) 불투명한 조상이 나올 때까지 아래 배경 위에 겹친다
          const layers: number[][] = [];
          for (let el: Element | null = btn.closest('li'); el; el = el.parentElement) {
            const c = rgba(getComputedStyle(el).backgroundColor);
            if (c[3] > 0) layers.push(c);
            if (c[3] >= 1) break;
          }
          const bg = layers.reverse().reduce((acc, c) => over(c, acc), [255, 255, 255, 1]);
          const icon = rgba(getComputedStyle(btn.querySelector('svg')!).color);
          // 버튼 opacity 까지 곱해 배경 위에 실제로 칠해지는 색으로 섞는다
          const fg = over([icon[0], icon[1], icon[2], icon[3] * Number(getComputedStyle(btn).opacity)], bg);
          const [l1, l2] = [lum(fg), lum(bg)].sort((x, y) => y - x);
          return (l1 + 0.05) / (l2 + 0.05);
        });
      await expect.poll(contrast).toBeGreaterThanOrEqual(3);
      await page.evaluate(() => document.documentElement.classList.add('dark'));
      await expect.poll(contrast).toBeGreaterThanOrEqual(3);
    });

    test('키보드로 열면 초점이 간 첫 줄도 강조된다', async ({ authenticatedPage: page }) => {
      await openPanel(page);
      await openListByKeyboard(page);
      await expect(titleBtn(page, '첫째 대화')).toBeFocused();
      await page.mouse.move(2, 2);
      expect(await rowBg(page, 0)).not.toBe(TRANSPARENT);
      expect(await rowBg(page, 1)).toBe(TRANSPARENT);
    });

    // 휴지통 실제 상자(del)의 네 변 바로 바깥 2px 을 누른다 — 고정 좌표가 아니라 버튼 크기·위치가 바뀌어도
    // 휴지통 경계 밖은 곧바로 줄(대화 열기)이 받는지를 본다(WD-1010 리뷰 후속).
    const SPOTS = [
      { name: '휴지통 오른쪽 바깥(오른쪽 여백)', pos: (d: Box) => ({ x: d.x + d.width + 2, y: d.y + d.height / 2 }) },
      { name: '휴지통 왼쪽 바깥(제목과 휴지통 사이 틈)', pos: (d: Box) => ({ x: d.x - 2, y: d.y + d.height / 2 }) },
      { name: '휴지통 위 바깥', pos: (d: Box) => ({ x: d.x + d.width / 2, y: d.y - 2 }) },
      { name: '휴지통 아래 바깥', pos: (d: Box) => ({ x: d.x + d.width / 2, y: d.y + d.height + 2 }) },
    ];
    type Box = { x: number; y: number; width: number; height: number };
    for (const spot of SPOTS) {
      test(`줄의 ${spot.name}을 눌러도 그 대화가 열린다`, async ({ authenticatedPage: page }) => {
        const requests = await openPanel(page);
        await openListByMouse(page);
        const row = (await page.getByRole('dialog', { name: '대화 선택' }).getByRole('listitem').nth(1).boundingBox())!;
        const del = (await deleteBtn(page, '둘째 대화').boundingBox())!;
        const { x, y } = spot.pos(del);
        // 누를 점이 그 줄 안이어야 이 시험이 「줄의 여백」을 누른 것이다
        expect(x).toBeGreaterThan(row.x);
        expect(x).toBeLessThan(row.x + row.width);
        expect(y).toBeGreaterThan(row.y);
        expect(y).toBeLessThan(row.y + row.height);
        await page.mouse.click(x, y);
        await expect.poll(() => requests.messages).toEqual(['sw-2']);
        await expect(page.getByRole('dialog', { name: '대화 선택' })).toHaveCount(0);
      });
    }

    test('휴지통 버튼을 누르면 확인 창만 열고 대화는 열지 않는다', async ({ authenticatedPage: page }) => {
      const requests = await openPanel(page);
      await page.getByRole('button', { name: '대화 선택', exact: true }).click();
      await deleteBtn(page, '둘째 대화').click();
      await expect(confirm(page)).toBeVisible();
      expect(requests.messages).toEqual([]);
      expect(requests.deletes).toEqual([]);
    });
  });

  test.describe('목록이 max-h-64 를 넘칠 때', () => {
    /** 12개 — 줄 32px × 12 가 max-h-64(256px) 를 넘어 스크롤 상자가 생긴다 */
    const MANY = Array.from({ length: 12 }, (_, i) =>
      createAISession({ id: 100 + i, sessionId: `many-${i + 1}`, title: `넘침 대화 ${i + 1}` }),
    );

    test('Tab 이 끝에서 처음으로(또는 반대로) 돌 때 초점 받은 버튼이 스크롤 상자 안에 완전히 보인다', async ({
      authenticatedPage: page,
    }) => {
      await openPanel(page, MANY);
      await openListByKeyboard(page);
      await expect(titleBtn(page, '넘침 대화 1')).toBeFocused();

      // Radix FocusScope 의 순환은 focus({ preventScroll: true }) 라 스스로는 스크롤하지 않는다.
      // 뷰포트가 아니라 스크롤 상자 안에 보여야 하므로 상자 경계 기준으로도 확인한다(WCAG 2.4.7·2.4.11).
      const insideBox = (name: string) =>
        page.getByRole('dialog', { name: '대화 선택' }).evaluate((box, n) => {
          const el = Array.from(box.querySelectorAll('button')).find((b) => b.getAttribute('aria-label') === n || b.textContent === n);
          if (!el) return false;
          const b = box.getBoundingClientRect();
          const r = el.getBoundingClientRect();
          return r.top >= b.top && r.bottom <= b.bottom;
        }, name);

      await page.keyboard.press('Shift+Tab');
      const lastDelete = deleteBtn(page, '넘침 대화 12');
      await expect(lastDelete).toBeFocused();
      await expect(lastDelete).toBeInViewport({ ratio: 1 });
      await expect.poll(() => insideBox('넘침 대화 12 삭제')).toBe(true);

      await page.keyboard.press('Tab');
      const firstTitle = titleBtn(page, '넘침 대화 1');
      await expect(firstTitle).toBeFocused();
      await expect(firstTitle).toBeInViewport({ ratio: 1 });
      await expect.poll(() => insideBox('넘침 대화 1')).toBe(true);
    });

    test('아래가 잘려 일부만 보이는 줄을 마우스로 누르면 스크롤로 밀리지 않고 정확히 그 줄이 선택된다', async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page, MANY);
      await page.getByRole('button', { name: '대화 선택', exact: true }).click();
      const box = page.getByRole('dialog', { name: '대화 선택' });
      await expect(box).toBeVisible();

      // 8번 줄은 스크롤 상자 아래로 일부가 잘려 있다. 마우스 누름이 초점을 옮길 때 스크롤하면 누르는 사이 버튼이
      // 움직여 클릭이 빗나갈 수 있다. (1번 줄은 열 때 이미 초점이 있어 누름이 초점 이벤트를 만들지 않으므로 8번 줄을 쓴다.)
      const boxRect = (await box.boundingBox())!;
      const row = titleBtn(page, '넘침 대화 8');
      const rowRect = (await row.boundingBox())!;
      expect(rowRect.y + rowRect.height).toBeGreaterThan(boxRect.y + boxRect.height); // 실제로 잘려 있다
      expect(rowRect.y).toBeLessThan(boxRect.y + boxRect.height - 6); // 보이는 부분이 있다

      // 보이는 부분 안(상자 아래에서 3px)을 좌표로 직접 누른다 — locator.click 은 먼저 스크롤해 버리므로 쓰지 않는다.
      await page.mouse.move(rowRect.x + 20, boxRect.y + boxRect.height - 3);
      await page.mouse.down();
      // 누른 순간 초점이 그 줄로 가도 스크롤 상자는 움직이지 않아야 한다(마우스 초점은 :focus-visible 이 아니다).
      await expect(row).toBeFocused();
      expect(await box.evaluate((el) => el.scrollTop)).toBe(0);
      await page.mouse.up();
      await expect.poll(() => requests.messages).toEqual(['many-8']);
    });
  });

  // WD-973: 삭제 응답을 기다리는 줄의 휴지통을 다시 누르면 같은 id 로 DELETE 가 또 나갔다(두 번째는 404 → 실패 토스트).
  test.describe('삭제 중 다시 지우기 (WD-973)', () => {
    /** 첫째 대화를 지우겠다고 확인까지 누른다 — DELETE 응답은 시험이 쥔다 */
    async function confirmDeleteFirst(page: Page) {
      await page.getByRole('button', { name: '대화 선택', exact: true }).click();
      await deleteBtn(page, '첫째 대화').click();
      await confirm(page).getByRole('button', { name: '삭제', exact: true }).click();
    }

    test('확인 뒤 응답 전에 같은 줄 휴지통을 눌러도 확인 창이 열리지 않고 DELETE 는 1건', async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page, MOCK_SESSIONS, { slowDeleteIds: ['1'] });
      await confirmDeleteFirst(page);
      await expect.poll(() => requests.deletes).toEqual(['1']);

      // aria-disabled 버튼은 Playwright 가 비활성으로 보고 기다리므로 force 로 실제 클릭을 보낸다
      await deleteBtn(page, '첫째 대화').click({ force: true });
      await expect(deleteBtn(page, '첫째 대화')).toHaveAttribute('aria-disabled', 'true');
      await expect(confirm(page)).toHaveCount(0);
      await expect(deleteBtn(page, '첫째 대화')).toBeFocused();
      expect(requests.deletes).toEqual(['1']);
      requests.releaseDelete('1');
    });

    for (const key of ACTIVATE_KEYS) {
      test(`키보드(${key})로 눌러도 열리지 않는다`, async ({ authenticatedPage: page }) => {
        const requests = await openPanel(page, MOCK_SESSIONS, { slowDeleteIds: ['1'] });
        await confirmDeleteFirst(page);
        await expect.poll(() => requests.deletes).toEqual(['1']);

        await deleteBtn(page, '첫째 대화').focus();
        await page.keyboard.press(key);
        await expect(confirm(page)).toHaveCount(0);
        expect(requests.deletes).toEqual(['1']);
        requests.releaseDelete('1');
      });
    }

    test('성공(204) 응답 뒤 재조회를 붙잡은 동안에도 줄이 사라지고 실패 토스트가 없다', async ({
      authenticatedPage: page,
    }) => {
      const requests = await openPanel(page, MOCK_SESSIONS, { slowDeleteIds: ['1'], holdRefetch: true });
      // 목록이 열린 채로 있어야 줄이 사라졌음을 볼 수 있다 — 키보드로 지운다(마우스 확인은 목록을 닫는다)
      await openListByKeyboard(page);
      await deleteBtn(page, '첫째 대화').focus();
      await page.keyboard.press('Enter');
      await confirm(page).getByRole('button', { name: '삭제', exact: true }).focus();
      await page.keyboard.press('Enter');
      await expect.poll(() => requests.deletes).toEqual(['1']);
      await expect(titleBtn(page, '첫째 대화')).toHaveCount(1);

      requests.releaseDelete('1');
      // 재조회 GET 은 release() 전까지 보류 — 캐시에서 바로 빠져야 한다(캐시 제거가 없으면 이 줄이 남는다)
      await expect(titleBtn(page, '첫째 대화')).toHaveCount(0);
      await expect(titleBtn(page, '둘째 대화')).toHaveCount(1);
      await expect(page.getByText('대화 삭제에 실패했습니다.')).toHaveCount(0);
      expect(requests.deletes).toEqual(['1']);
      requests.release();
    });

    test('실패(500) 뒤에는 다시 지울 수 있다', async ({ authenticatedPage: page }) => {
      const requests = await openPanel(page, MOCK_SESSIONS, { slowDeleteIds: ['1'], failDeleteIds: ['1'] });
      await confirmDeleteFirst(page);
      await expect.poll(() => requests.deletes).toEqual(['1']);

      requests.releaseDelete('1');
      await expect(page.getByText('대화 삭제에 실패했습니다.')).toBeVisible();
      await expect(titleBtn(page, '첫째 대화')).toHaveCount(1);
      await expect(deleteBtn(page, '첫째 대화')).not.toHaveAttribute('aria-disabled', 'true');

      await deleteBtn(page, '첫째 대화').click();
      await expect(confirm(page)).toBeVisible();
      await confirm(page).getByRole('button', { name: '삭제', exact: true }).click();
      await expect.poll(() => requests.deletes).toEqual(['1', '1']);
      requests.releaseDelete('1');
    });
  });

  // 줄별 휴지통 클릭 영역 — WCAG 2.5.8(최소 24×24)을 줄 높이 32px 안에서 채운다
  test.describe('휴지통 클릭 영역 (WD-1010)', () => {
    /** 상자를 정수 px 로 반올림한다 — 하위 픽셀(24 가 23.99 처럼)로 흔들리지 않게 하되 경계는 넓히지 않고 정확히 비교한다 */
    const rounded = (b: { x: number; y: number; width: number; height: number }) => ({
      x: Math.round(b.x),
      y: Math.round(b.y),
      width: Math.round(b.width),
      height: Math.round(b.height),
    });
    test('줄마다 휴지통이 24×24 이고 줄 안에 있으며 줄 높이는 32px 그대로', async ({ authenticatedPage: page }) => {
      await openPanel(page);
      await openListByMouse(page);
      for (const title of MOCK_SESSIONS.map((x) => x.title ?? '')) {
        // 줄(li)은 제목 버튼의 부모다
        const row = rounded((await titleBtn(page, title).locator('xpath=..').boundingBox())!);
        const del = rounded((await deleteBtn(page, title).boundingBox())!);
        // 정확히 24×24 — 하한(2.5.8)뿐 아니라 상한도 둬, 휴지통이 옆으로 커져 줄의 대화 열기 영역을 먹으면 잡는다
        expect(del.width).toBe(24);
        expect(del.height).toBe(24);
        expect(del.y).toBeGreaterThanOrEqual(row.y);
        expect(del.y + del.height).toBeLessThanOrEqual(row.y + row.height);
        expect(del.x).toBeGreaterThanOrEqual(row.x); // 왼쪽 경계 안
        expect(row.x + row.width - (del.x + del.width)).toBe(4); // 오른쪽 틈(right-1)
        expect(row.height).toBe(32);
        // 버튼만 커지고 아이콘은 WD-951 그대로 16px — icon-xs 변형의 기본 아이콘 크기(12px)로 줄지 않는다
        const icon = rounded((await deleteBtn(page, title).locator('svg').boundingBox())!);
        expect(icon.width).toBe(16);
        expect(icon.height).toBe(16);
      }
    });

    test('휴지통 가장자리를 눌러도 확인 창만 열리고 대화는 열리지 않는다', async ({ authenticatedPage: page }) => {
      const requests = await openPanel(page);
      await openListByMouse(page);
      const dialog = page.getByRole('dialog', { name: '대화 선택' });
      // 네 가장자리 가운데 안쪽 1px — 제목 버튼이 아니라 휴지통이 받아야 한다. 모서리 끝점은 버튼의 둥근 모서리 바깥이라
      // 원래부터 줄(제목 버튼) 영역이므로 쓰지 않는다.
      for (const edge of ['left', 'right', 'top', 'bottom'] as const) {
        const box = (await deleteBtn(page, '둘째 대화').boundingBox())!;
        const cx = box.x + box.width / 2;
        const cy = box.y + box.height / 2;
        const p = {
          left: { x: box.x + 1, y: cy },
          right: { x: box.x + box.width - 1, y: cy },
          top: { x: cx, y: box.y + 1 },
          bottom: { x: cx, y: box.y + box.height - 1 },
        }[edge];
        await page.mouse.click(p.x, p.y);
        await expect(confirm(page)).toBeVisible();
        await page.keyboard.press('Escape');
        await expect(confirm(page)).toHaveCount(0);
        // Escape 는 확인 창만 닫고 목록은 남는다(「Esc 는 확인 창 → 목록 순으로 한 층씩 닫는다」와 같은 동작).
        // 닫힘 애니메이션 중에 상태를 한 번 읽어 갈래를 나누면 경합하므로, 목록이 열린 채인지를 기다려 단언한다(WD-1010 리뷰 후속).
        await expect(dialog).toHaveAttribute('data-state', 'open');
        // 닫히기 시작한 목록의 낡은 'open' 을 읽고 지나가지 않게, 확인 창이 돌려준 초점이 그 휴지통에 머무는지도 본다
        await expect(deleteBtn(page, '둘째 대화')).toBeFocused();
      }
      // 네 번 누르고 확인 창을 닫는 동안 나갔을 대화 열기 요청까지 한 번에 본다(고정 대기 없이)
      expect(requests.messages).toEqual([]);
    });

    test('긴 제목은 말줄임되고 글자 영역이 휴지통 왼쪽에서 끝난다', async ({ authenticatedPage: page }) => {
      const title = '가'.repeat(60);
      await openPanel(page, [createAISession({ id: 5, sessionId: 'sw-long', title, updatedAt: '2026-11-23T03:00:00Z' })]);
      await openListByMouse(page, title);
      // 제목이 실제로 잘려 있어야 이 시험이 의미 있다
      const clipped = await titleBtn(page, title).evaluate((btn) => btn.scrollWidth > btn.clientWidth);
      expect(clipped).toBe(true);
      const contentRight = await titleBtn(page, title).evaluate((btn) => {
        return btn.getBoundingClientRect().right - parseFloat(getComputedStyle(btn).paddingRight);
      });
      const del = (await deleteBtn(page, title).boundingBox())!;
      expect(contentRight).toBeLessThanOrEqual(del.x);
    });
  });
});
