import type { ElementHandle, Locator, Page } from '@playwright/test';

/**
 * 여러 요소를 한 작업(한 evaluate) 안에서 차례로 클릭한다 — 클릭 사이에 React 렌더가 끼지 않게 하려는 WD-950 시험용.
 *
 * - 못 찾는 요소가 있으면 조용히 넘어가지 않고 throw 한다(elementHandle 이 찾지 못하면 시간 초과로 던진다).
 * - thenHistoryBack: 클릭들 직후 같은 작업 안에서 history.back() 을 부른다 —
 *   화면 반영 전에 쓴 주소가 뒤로가기(POP)와 겨루는 상황을 만든다.
 * - 만든 핸들은 끝나면(실패해도) dispose 해 브라우저 쪽 참조가 남지 않게 한다.
 */
export async function clickInOneTask(
  page: Page,
  targets: Locator[],
  options: { thenHistoryBack?: boolean } = {},
) {
  // 핸들은 한꺼번에 얻는다 — 하나라도 못 찾으면 throw 하고, 얻은 핸들은 아래 finally 가 아니라 여기서 못 정리되므로 allSettled 로 모은다
  const settled = await Promise.allSettled(targets.map((target) => target.elementHandle()));
  const handles = settled.flatMap((r) => (r.status === 'fulfilled' ? [r.value as ElementHandle] : []));
  try {
    const failed = settled.find((r) => r.status === 'rejected');
    if (failed) throw (failed as PromiseRejectedResult).reason;
    await page.evaluate(
      ({ els, back }) => {
        for (const el of els) (el as HTMLElement).click();
        if (back) window.history.back();
      },
      { els: handles, back: options.thenHistoryBack === true },
    );
  } finally {
    await Promise.all(handles.map((h) => h.dispose()));
  }
}
