import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';

/**
 * 열린 확인 창(alertdialog)의 배경(overlay)을 마우스로 누른다 (WD-943).
 * 창은 화면 가운데에 있어 좌상단 (5, 5) 는 항상 배경 위다. 포털이어도 배경의 click 은 React 트리를 따라
 * 창을 연 행·메뉴 항목으로 번지므로, 그 번짐이 막혔는지 보려는 시험이 공통으로 쓴다.
 * 창이 보이기 전에 누르면 아무것도 누르지 못한 채 시험이 통과할 수 있어 먼저 보이기를 기다린다.
 * @param page - 대상 페이지
 */
export async function clickDialogBackdrop(page: Page): Promise<void> {
  await expect(page.getByRole('alertdialog')).toBeVisible();
  // (5, 5) 가 실제로 배경 위인지 확인한다 — 애니메이션·레이아웃 변화로 다른 요소를 누르면 번짐 시험이 헛통과한다.
  await expect
    .poll(() => page.evaluate(() => document.elementFromPoint(5, 5)?.getAttribute('data-slot') ?? null))
    .toBe('alert-dialog-overlay');
  await page.mouse.click(5, 5);
}

/**
 * 목록 행에서 연 삭제 확인 창의 배경을 눌러도 목록에 남고 삭제 요청이 없는지 확인한다 (WD-943).
 * 배경 click 이 행으로 번졌다면 이동했거나 창이 사라진다 — 창이 남아 「취소」로 닫혀야 click 이 처리된 뒤임을 보인다.
 * @param page - 확인 창이 열린 목록 페이지
 * @param deleteRequests - 창을 열기 전에 건 DELETE 요청 캡처
 */
export async function expectBackdropClickStaysOnList(page: Page, deleteRequests: readonly unknown[]): Promise<void> {
  const dialog = page.getByRole('alertdialog');
  const listUrl = page.url();
  await clickDialogBackdrop(page);
  await expect(dialog).toBeVisible();
  await expect(page).toHaveURL(listUrl);
  await dialog.getByRole('button', { name: '취소' }).click();
  await expect(dialog).toBeHidden();
  await expect(page).toHaveURL(listUrl);
  expect(deleteRequests).toHaveLength(0);
}

/**
 * 대화상자가 열림 애니메이션(zoom-in-95)을 마친 뒤 세로로 뷰포트 안에 있는지 확인한다 (WD-1123).
 * 애니메이션 중에는 95% 로 줄어든 박스라 「뷰포트 안」 단언이 느슨해지므로 먼저 애니메이션이 끝나길 기다린다.
 * 높이는 page.viewportSize() 에서 읽어 시험마다 숫자를 되풀이하지 않는다.
 * @param page - 대상 페이지(뷰포트 크기를 읽는다)
 * @param dialog - 대화상자 locator
 */
export async function expectDialogWithinViewportHeight(page: Page, dialog: Locator): Promise<void> {
  await expect.poll(() => dialog.evaluate((el) => el.getAnimations().length)).toBe(0);
  const height = page.viewportSize()!.height;
  const box = await dialog.boundingBox();
  expect(box, '대화상자가 그려지지 않았다').not.toBeNull();
  expect(box!.y).toBeGreaterThanOrEqual(0);
  expect(box!.y + box!.height).toBeLessThanOrEqual(height);
}

/**
 * 사용자처럼 마우스 휠로 스크롤해 대상이 가장 가까운 스크롤 컨테이너 안에 완전히 들어올 때까지 내린다 (WD-1140, WD-1152).
 * scrollTop 직접 대입은 overflow-hidden 요소도 스크롤시켜 버그를 가리므로 실제 휠 입력을 쓴다.
 * 「가장 가까운 스크롤 컨테이너」 = 대상의 조상 중 overflow-y 가 auto/scroll 이고 scrollHeight > clientHeight 인 첫 요소(없으면 window).
 * 도달 판정은 그 컨테이너의 보이는 안쪽 영역(테두리 제외 clientTop~clientHeight) 안에 대상 사각형이 완전히 들어오는가 — 컨테이너에 가려진 채
 * window 기준으로만 「보임」이라 통과하는 헛통과를 막는다.
 * 휠을 둔 지점은 휠 영역 중 창 안(0~innerHeight)에 보이는 부분으로 한정한다 — 바깥 대화상자가 이미 스크롤돼 영역 윗부분이 창 밖이어도
 * 마우스가 배경(overlay)·창 밖 좌표에 놓이지 않는다. 휠마다 스크롤이 멈출 때까지 기다린 뒤 판정해 부드러운 스크롤 도중 지나치지 않게 한다.
 * 시작 때 이미 완전히 보이면 휠로 닿는지 확인할 수 없으므로 오류를 던진다 — 공회전 시험을 만들지 않기 위함.
 * @param page - 대상 페이지
 * @param dialog - 대화상자 locator (`at` 가 없으면 그 중앙에 마우스를 둔다)
 * @param target - 스크롤 컨테이너 안으로 들어와야 하는 요소
 * @param opts.at - 마우스를 둘 안쪽 영역. 가로 중앙, 세로는 위에서 min(높이/2, 40) 지점에 둔다
 * @param opts.deltaY - 휠 한 번의 이동량 (기본 200)
 */
export async function wheelUntilInViewport(
  page: Page,
  dialog: Locator,
  target: Locator,
  opts: { at?: Locator; deltaY?: number } = {},
): Promise<void> {
  // 대상이 가장 가까운 스크롤 컨테이너(없으면 window) 안에 완전히 들어와 있는지 — 브라우저 안에서 판정한다.
  const isFullyVisible = () =>
    target.evaluate((el) => {
      let container: Element | null = el.parentElement;
      while (container) {
        const oy = getComputedStyle(container).overflowY;
        if ((oy === 'auto' || oy === 'scroll') && container.scrollHeight > container.clientHeight) break;
        container = container.parentElement;
      }
      const r = el.getBoundingClientRect();
      // 창 안에도 들어와야 한다 — 컨테이너 안이어도 창 밖이면 사용자에게 보이지 않는다
      const inWindow = r.top >= 0 && r.bottom <= window.innerHeight;
      if (!container) return inWindow;
      // 테두리(clientTop)를 뺀 안쪽 보이는 영역과 비교한다 — 테두리 밑에 가려진 채로 통과하지 않게
      const top = container.getBoundingClientRect().top + container.clientTop;
      return inWindow && r.top >= top && r.bottom <= top + container.clientHeight;
    });

  // 휠 한 번 뒤 대상의 가장 가까운 스크롤 컨테이너(없으면 window)의 scrollTop 이 세 프레임 연속 같을 때까지 기다린다.
  const settle = () =>
    target.evaluate(
      (el) =>
        new Promise<void>((resolve) => {
          let container: Element | null = el.parentElement;
          while (container) {
            const oy = getComputedStyle(container).overflowY;
            if ((oy === 'auto' || oy === 'scroll') && container.scrollHeight > container.clientHeight) break;
            container = container.parentElement;
          }
          const read = () => (container ? container.scrollTop : window.scrollY);
          let prev = -1;
          let same = 0;
          const tick = () => {
            const cur = read();
            same = cur === prev ? same + 1 : 0;
            prev = cur;
            if (same >= 3) resolve();
            else requestAnimationFrame(tick);
          };
          tick();
        }),
    );

  // 처음부터 보이면 휠 도달을 확인할 수 없다 — 헛통과 대신 실패시킨다.
  if (await isFullyVisible()) {
    throw new Error('wheelUntilInViewport: 대상이 처음부터 보여 휠 도달을 확인할 수 없다');
  }

  const area = opts.at ?? dialog;
  const box = await area.boundingBox();
  expect(box, '휠을 둘 영역이 그려지지 않았다').not.toBeNull();
  // 영역 중 창 안에 보이는 부분만 쓴다(0~innerHeight)
  const winH = page.viewportSize()!.height;
  const visTop = Math.max(box!.y, 0);
  const visBottom = Math.min(box!.y + box!.height, winH);
  expect(visBottom, '휠을 둘 영역이 창 밖이다').toBeGreaterThan(visTop);
  const y = opts.at ? visTop + Math.min((visBottom - visTop) / 2, 40) : (visTop + visBottom) / 2;
  await page.mouse.move(box!.x + box!.width / 2, y);
  await expect
    .poll(async () => {
      await page.mouse.wheel(0, opts.deltaY ?? 200);
      await settle();
      return isFullyVisible();
    })
    .toBe(true);
  await expect(target).toBeInViewport({ ratio: 1 });
}

/**
 * 바깥 대화상자가 스크롤하지 않는지 확인한다 — scrollHeight <= clientHeight + 1 (WD-1140).
 * 바깥 overflow-hidden 을 걷은 뒤에도 보통 높이에서는 안쪽 영역만 스크롤해야 한다는 회귀 방지용.
 * @param dialog - 바깥 대화상자 locator
 */
export async function expectNoOuterScroll(dialog: Locator): Promise<void> {
  const outer = await dialog.evaluate((el) => ({ scrollHeight: el.scrollHeight, clientHeight: el.clientHeight }));
  expect(outer.scrollHeight).toBeLessThanOrEqual(outer.clientHeight + 1);
}

/**
 * 떠나기 가드의 「저장하지 않은 변경사항」 확인 창 (useUnsavedChangesGuard).
 * @param page - 대상 페이지
 */
export function unsavedChangesDialog(page: Page): Locator {
  return page.getByRole('alertdialog', { name: '저장하지 않은 변경사항' });
}

/**
 * 떠나기 가드 확인 창이 열린 상태인지 기다린다 (WD-1174) — 닫히는 중인 창(data-state=closed)을 누르지 않게 열린 상태를 확인한다.
 * @param page - 대상 페이지
 */
export async function expectUnsavedChangesDialogOpen(page: Page): Promise<void> {
  await expect(unsavedChangesDialog(page)).toHaveAttribute('data-state', 'open');
}
