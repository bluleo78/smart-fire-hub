import { expect, type Locator } from '@playwright/test';

/**
 * 긴 값 말줄임을 검증할 뷰포트 폭 (WD-634)
 * - 1280px 만 보면 좁은 노트북 폭(1024px, 사이드바 펼침)에서 표가 넘쳐 작업 열이 밀리는 회귀를 놓친다. 두 폭을 모두 본다.
 */
export const TABLE_LAYOUT_WIDTHS = [1280, 1024] as const;

/**
 * 레이아웃이 자리 잡은 뒤의 측정값을 돌려준다 (WD-650)
 * - 글꼴 로딩·늦게 오는 조회 결과로 표 폭이 바뀌는 중에 한 번만 재면, 잠깐의 넘침으로 깜빡 실패하거나 다 그려지기 전 좁은 표로 거짓 통과한다.
 * - 요소가 보일 때까지 기다리고 글꼴 로딩을 마친 뒤, 연달아 두 번 같은 값이 나올 때까지 다시 잰다.
 * @param target - 잴 요소
 * @param measure - 브라우저에서 실행할 측정 함수(직렬화 가능한 값을 돌려준다)
 */
async function measureStable<T>(target: Locator, measure: (el: Element) => T): Promise<T> {
  await expect(target).toBeVisible();
  await target.evaluate(() => document.fonts.ready.then(() => undefined));
  let prev: string | undefined;
  let value: T | undefined;
  await expect
    .poll(
      async () => {
        value = await target.evaluate(measure);
        const cur = JSON.stringify(value);
        const stable = cur === prev;
        prev = cur;
        return stable;
      },
      { message: '표 레이아웃이 안정되지 않았다(연속 두 번 같은 측정값이 나오지 않음)', intervals: [100, 200, 300, 500] },
    )
    .toBe(true);
  return value as T;
}

/**
 * 표가 컨테이너 안에 가로로 들어맞고 마지막 열이 가로로 다 보이는지 검증한다 (WD-634, WD-650)
 * - 표 컨테이너가 가로로 넘치지 않고(≤ 1px), 마지막 열 요소의 오른쪽 끝이 컨테이너·뷰포트 오른쪽 끝 안이다.
 * - 세로 위치는 보지 않으므로 스크롤하지 않는다 — getBoundingClientRect 는 화면 밖 요소도 잰다.
 *   예전에는 scrollIntoViewIfNeeded 로 세로 스크롤을 했는데, 넘친 표라면 가로 스크롤까지 옮겨 마지막 열이 「보이는」 것처럼 재졌다(WD-650).
 * - 같은 이유로 재기 전에 요소의 모든 조상(표 컨테이너, AppLayout 의 <main class="overflow-auto"> 등 실제 스크롤 상자)과
 *   문서의 가로 스크롤을 0 으로 되돌린다 — 어느 상자가 스크롤되어 있어도 「스크롤 0 기준」 위치를 잰다.
 * @param lastCell - 가로로 다 보여야 하는 마지막 열의 요소(작업 버튼·상태 배지·마지막 칸)
 */
export async function expectTableFits(lastCell: Locator) {
  const m = await measureStable(lastCell, (el) => {
    // 페이지에 표가 여럿일 수 있으므로 이 요소가 들어 있는 표의 컨테이너를 잰다
    const container = el.closest<HTMLElement>('[data-slot="table-container"]');
    if (!container) throw new Error('expectTableFits: 요소가 표 컨테이너([data-slot="table-container"]) 안에 없다');
    for (let node = el.parentElement; node; node = node.parentElement) node.scrollLeft = 0;
    if (document.scrollingElement) document.scrollingElement.scrollLeft = 0;
    return {
      overflow: container.scrollWidth - container.clientWidth,
      containerRight: container.getBoundingClientRect().right,
      lastRight: el.getBoundingClientRect().right,
      viewport: window.innerWidth,
    };
  });
  expect(m.overflow, '표 컨테이너 가로 넘침(px)').toBeLessThanOrEqual(1);
  expect(m.lastRight, '마지막 열 오른쪽 끝이 컨테이너 밖').toBeLessThanOrEqual(m.containerRight + 1);
  expect(m.lastRight, '마지막 열 오른쪽 끝이 뷰포트 밖').toBeLessThanOrEqual(m.viewport);
}

/**
 * 짧은 칸의 배지가 그 열의 선언 폭(헤더의 `w-[Nrem]`) 안에 들어가는지 검증한다 (WD-650)
 * - 긴 값 상한은 「표 폭 − --k(짧은 칸 선언 폭의 합)」으로 계산한다. 배지가 선언 폭보다 넓으면 그 차이만큼 표가 넘친다.
 *   다른 짧은 칸에 남는 여유가 있으면 당장은 가려지므로, 넘침 대신 「내용 폭 ≤ 선언 폭」을 직접 본다.
 * - 배지(`[data-slot="badge"]`)들이 차지하는 가로 범위 + 칸 좌우 여백을 선언 폭과 비교한다.
 * - 헤더는 colSpan 을 더해 같은 열 위치의 th 를 찾는다. 헤더가 여러 열을 묶거나(colSpan > 1) 열 위치가 맞지 않으면 바로 실패시킨다.
 * @param cell - 짧은 칸(td) — 헤더가 `w-[Nrem]` 선언 폭을 가져야 한다
 */
export async function expectBadgesFitDeclaredWidth(cell: Locator) {
  const m = await measureStable(cell, (td) => {
    if (!(td instanceof HTMLTableCellElement)) throw new Error('expectBadgesFitDeclaredWidth: td 가 아니다');
    const row = td.parentElement as HTMLTableRowElement;
    // td 의 열 위치 = 앞선 칸들의 colSpan 합
    let col = 0;
    for (const c of [...row.cells].slice(0, td.cellIndex)) col += c.colSpan;
    const headRow = td.closest('table')?.tHead?.rows[0];
    if (!headRow) throw new Error('expectBadgesFitDeclaredWidth: 표에 헤더 행이 없다');
    let at = 0;
    let th: HTMLTableCellElement | undefined;
    for (const h of headRow.cells) {
      if (at === col) th = h;
      at += h.colSpan;
    }
    if (!th) throw new Error(`expectBadgesFitDeclaredWidth: ${col}번 열 위치에서 시작하는 헤더가 없다(colSpan 불일치)`);
    if (th.colSpan !== 1 || td.colSpan !== 1)
      throw new Error('expectBadgesFitDeclaredWidth: 여러 열을 묶은 칸(colSpan > 1)은 선언 폭과 비교할 수 없다');
    const declared = th.className.match(/(?:^|\s)w-\[([\d.]+)rem\]/);
    if (!declared) throw new Error('expectBadgesFitDeclaredWidth: 헤더에 w-[Nrem] 선언 폭이 없다');
    const rects = [...td.querySelectorAll('[data-slot="badge"]')].map((b) => b.getBoundingClientRect());
    if (rects.length === 0) throw new Error('expectBadgesFitDeclaredWidth: 칸에 배지가 없다');
    const cs = getComputedStyle(td);
    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
    return {
      content:
        Math.max(...rects.map((r) => r.right)) -
        Math.min(...rects.map((r) => r.left)) +
        parseFloat(cs.paddingLeft) +
        parseFloat(cs.paddingRight),
      declared: parseFloat(declared[1]) * rem,
    };
  });
  expect(m.content, `배지 폭+여백(px)이 선언 폭 ${m.declared}px 를 넘는다`).toBeLessThanOrEqual(m.declared + 0.5);
}

/**
 * 표 wrapper 의 `--k` 가 헤더 선언 폭과 맞는지 검증한다 (WD-650)
 * - `--k` 는 헤더 `w-[Nrem]` 값을 손으로 더해 적은 문자열이라, 짧은 칸 폭만 바꾸고 `--k` 를 잊으면 긴 값 상한이 틀어져 표가 넘친다.
 * - 규칙: `--k` = 선언 폭이 있는 헤더들의 w-[Nrem] 합 + 선언 폭이 없는 열(긴 값 칸) 수 × 1rem(칸 좌우 여백 p-2).
 * - `--k` 는 등록되지 않은 사용자 속성이라 계산값이 문자열이므로, wrapper 안에 width: var(--k) 인 임시 요소를 넣어 px 로 잰다.
 * @param tableEl - 표 안의 아무 요소(칸·버튼 등)
 */
export async function expectKMatchesDeclaredWidths(tableEl: Locator) {
  const m = await measureStable(tableEl, (el) => {
    const wrapper = el.closest<HTMLElement>('[class~="@container"]');
    const headRow = el.closest('table')?.tHead?.rows[0];
    if (!wrapper || !headRow) throw new Error('expectKMatchesDeclaredWidths: @container wrapper 또는 헤더 행이 없다');
    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
    let expected = 0;
    for (const th of headRow.cells) {
      if (th.colSpan !== 1) throw new Error('expectKMatchesDeclaredWidths: colSpan > 1 헤더는 지원하지 않는다');
      const declared = th.className.match(/(?:^|\s)w-\[([\d.]+)rem\]/);
      expected += (declared ? parseFloat(declared[1]) : 1) * rem;
    }
    const probe = document.createElement('div');
    probe.style.cssText = 'position:absolute;visibility:hidden;height:0;width:var(--k)';
    wrapper.appendChild(probe);
    const k = probe.getBoundingClientRect().width;
    probe.remove();
    return { k, expected };
  });
  expect(m.k, `--k(px)가 헤더 선언 폭 합 ${m.expected}px 와 다르다`).toBeCloseTo(m.expected, 0);
}

/**
 * 긴 값은 말줄임되고 전체 값은 title 로 남는다 (WD-559)
 * @param valueEl - 긴 값을 그리는 요소(링크·span 등 truncate 가 걸리는 요소)
 * @param fullValue - 잘리기 전 전체 값
 */
export async function expectLongValueTruncated(valueEl: Locator, fullValue: string) {
  await expect(valueEl).toBeVisible();
  const clipped = await valueEl.evaluate((el) => el.scrollWidth > el.clientWidth);
  expect(clipped, '긴 값이 말줄임되어야 한다').toBe(true);
  await expect(valueEl).toHaveAttribute('title', fullValue);
}

/**
 * 평범한 길이의 값은 잘리지 않는다 (WD-559)
 * - 폭 상한이 너무 좁으면 보통 이메일·이름까지 말줄임되어 정보가 가려진다.
 * @param valueEl - 평범한 값을 그리는 요소
 */
export async function expectValueNotTruncated(valueEl: Locator) {
  await expect(valueEl).toBeVisible();
  const clipped = await valueEl.evaluate((el) => el.scrollWidth > el.clientWidth);
  expect(clipped, '평범한 길이의 값은 잘리지 않아야 한다').toBe(false);
}
