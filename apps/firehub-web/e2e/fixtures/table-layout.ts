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
 * - 스크롤하지 않고 재며, 재기 전에 모든 조상·문서의 가로 스크롤을 0 으로 되돌린다 — 넘친 표가 「보이는」 것처럼 재지지 않게(WD-650).
 * @param lastCell - 가로로 다 보여야 하는 마지막 열의 요소(작업 버튼·상태 배지·마지막 칸)
 */
export async function expectTableFits(lastCell: Locator) {
  // 설정 확인은 poll 밖에서 한 번 — 기다려도 바뀌지 않는 오류를 「안정되지 않았다」로 가리지 않는다(WD-658)
  await expect(lastCell.locator('xpath=ancestor::*[@data-slot="table-container"]'), '표 컨테이너 안이 아니다').toHaveCount(1);
  const m = await measureStable(lastCell, (el) => {
    const container = el.closest<HTMLElement>('[data-slot="table-container"]') as HTMLElement;
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

/** 표 구조 — 헤더 선언 폭, 대상 칸의 선언 폭·여백, --k, 1rem (WD-658) */
interface TableInfo {
  /** 헤더 칸마다 colSpan 과 w-[Nrem] 선언 폭(rem, 없으면 null) */
  headers: { colSpan: number; declaredRem: number | null }[];
  /** 대상 칸 열의 선언 폭(rem, 없으면 null) */
  declaredRem: number | null;
  /** 대상 칸의 좌우 여백 합(px) */
  paddingPx: number;
  /** wrapper 의 --k(px) */
  k: number;
  /** 1rem(px) */
  remPx: number;
}

/**
 * 표 구조를 한 번 읽는다 (WD-658) — 세 검증 함수가 같이 쓰고, 설정 오류(헤더·colSpan·wrapper)는 poll 밖에서 바로 던진다
 * @param el - 표 칸(td·th) 또는 그 안의 요소
 */
async function readTable(el: Locator): Promise<TableInfo> {
  await expect(el).toBeVisible();
  return el.evaluate((node) => {
    const cell = node.closest('td, th');
    if (!(cell instanceof HTMLTableCellElement)) throw new Error('readTable: 표 칸(td·th) 안의 요소가 아니다');
    const headRow = cell.closest('table')?.tHead?.rows[0];
    if (!headRow) throw new Error('readTable: 표에 헤더 행이 없다');
    const wrapper = cell.closest<HTMLElement>('[class~="@container"]');
    if (!wrapper) throw new Error('readTable: @container wrapper 가 없다');
    const headers = [...headRow.cells].map((th) => {
      const m = th.className.match(/(?:^|\s)w-\[([\d.]+)rem\]/);
      return { colSpan: th.colSpan, declaredRem: m ? parseFloat(m[1]) : null };
    });
    // 대상 칸의 열 위치 = 앞선 칸 colSpan 합 → 같은 위치에서 시작하는 헤더
    let col = 0;
    for (const c of [...(cell.parentElement as HTMLTableRowElement).cells].slice(0, cell.cellIndex)) col += c.colSpan;
    let at = 0;
    let header: (typeof headers)[number] | undefined;
    for (const h of headers) {
      if (at === col) header = h;
      at += h.colSpan;
    }
    if (!header) throw new Error(`readTable: ${col}번 열 위치에서 시작하는 헤더가 없다(colSpan 불일치)`);
    if (header.colSpan !== 1 || cell.colSpan !== 1) throw new Error('readTable: 여러 열을 묶은 칸(colSpan > 1)은 지원하지 않는다');
    // --k 는 등록되지 않은 사용자 속성이라 계산값이 문자열 — width: var(--k) 인 임시 요소로 px 를 잰다
    const probe = document.createElement('div');
    probe.style.cssText = 'position:absolute;visibility:hidden;height:0;width:var(--k)';
    wrapper.appendChild(probe);
    const k = probe.getBoundingClientRect().width;
    probe.remove();
    const cs = getComputedStyle(cell);
    return {
      headers,
      declaredRem: header.declaredRem,
      paddingPx: parseFloat(cs.paddingLeft) + parseFloat(cs.paddingRight),
      k,
      remPx: parseFloat(getComputedStyle(document.documentElement).fontSize),
    };
  });
}

/**
 * 짧은 칸의 배지(+칸 여백)가 그 열의 선언 폭(헤더 `w-[Nrem]`) 안에 들어가는지 검증한다 (WD-650)
 * - 배지가 선언 폭보다 넓으면 --k 가 모자라 그 차이만큼 표가 넘친다. 다른 칸의 여유에 가려지지 않게 폭을 직접 본다.
 * @param cell - 짧은 칸(td)
 */
export async function expectBadgesFitDeclaredWidth(cell: Locator) {
  const t = await readTable(cell);
  if (t.declaredRem === null) throw new Error('expectBadgesFitDeclaredWidth: 헤더에 w-[Nrem] 선언 폭이 없다');
  await expect(cell.locator('[data-slot="badge"]'), '칸에 배지가 없다').not.toHaveCount(0);
  const badgesWidth = await measureStable(cell, (td) => {
    const rects = [...td.querySelectorAll('[data-slot="badge"]')].map((b) => b.getBoundingClientRect());
    return Math.max(...rects.map((r) => r.right)) - Math.min(...rects.map((r) => r.left));
  });
  const declared = t.declaredRem * t.remPx;
  expect(badgesWidth + t.paddingPx, `배지 폭+여백(px)이 선언 폭 ${declared}px 를 넘는다`).toBeLessThanOrEqual(declared + 0.5);
}

/**
 * `--k` = 헤더 w-[Nrem] 합 + 선언 폭이 없는 열 수 × 1rem(칸 여백 p-2) 인지 검증한다 (WD-650)
 * - 손으로 더해 적은 --k 가 헤더와 어긋나면 긴 값 상한이 틀어져 표가 넘친다.
 * @param tableEl - 표 안의 칸(td·th) 또는 그 안의 요소
 */
export async function expectKMatchesDeclaredWidths(tableEl: Locator) {
  const t = await readTable(tableEl);
  let expectedRem = 0;
  for (const h of t.headers) {
    if (h.colSpan !== 1) throw new Error('expectKMatchesDeclaredWidths: colSpan > 1 헤더는 지원하지 않는다');
    expectedRem += h.declaredRem ?? 1;
  }
  const expected = expectedRem * t.remPx;
  expect(t.k, `--k(px)가 헤더 선언 폭 합 ${expected}px 와 다르다`).toBeCloseTo(expected, 0);
}

/**
 * 남는 폭 R(=100cqw − --k)의 몫을 받는 칸이 상한 안에 머물고 실제로 그 몫을 받는지 검증한다 (WD-658)
 * - 상한 요소는 `data-long-value-cap`. 헤더 선언 폭(− 여백)은 --k 에 든 바닥이고, 몫은 그 위로 더 받는 부분이다.
 * - ① 내용 폭 ≤ 상한 ② 내용 폭 ≥ min(상한, 한 줄 폭) — 쪼그라들지 않음 ③ 상한 ≤ 바닥 + R — --k 규칙 안.
 * @param cell - 대상 칸(td)
 */
export async function expectCellWithinLongValueCap(cell: Locator) {
  const t = await readTable(cell);
  await expect(cell.locator('[data-long-value-cap]'), '칸에 data-long-value-cap 상한 요소가 없다').toHaveCount(1);
  const m = await measureStable(cell, (td) => {
    const capEl = td.querySelector<HTMLElement>('[data-long-value-cap]') as HTMLElement;
    const cs = getComputedStyle(td);
    const content = td.getBoundingClientRect().width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
    // 한 줄 폭: 인라인 스타일을 잠깐 바꿔 상한 없이 재고 바로 되돌린다
    const saved = capEl.style.cssText;
    capEl.style.cssText += ';max-width:none;width:max-content;flex-wrap:nowrap';
    const natural = capEl.getBoundingClientRect().width;
    capEl.style.cssText = saved;
    // R 은 같은 식의 임시 요소로 직접 잰다
    const probe = document.createElement('div');
    probe.style.cssText = 'position:absolute;visibility:hidden;height:0;width:max(0px,calc(100cqw - var(--k)))';
    td.closest('[class~="@container"]')?.appendChild(probe);
    const rest = probe.getBoundingClientRect().width;
    probe.remove();
    return { content, natural, maxWidth: getComputedStyle(capEl).maxWidth, rest };
  });
  // 퍼센트 상한은 칸 폭을 따라 순환해 --k 규칙으로 볼 수 없다 — px 로 계산되는 상한만 받는다
  expect(m.maxWidth, '상한 max-width 계산값이 px 가 아니다').toMatch(/^[\d.]+px$/);
  const cap = parseFloat(m.maxWidth);
  const allowed = (t.declaredRem === null ? 0 : t.declaredRem * t.remPx - t.paddingPx) + m.rest;
  expect(m.content, `칸 내용 폭(px)이 상한 ${cap}px 를 넘는다`).toBeLessThanOrEqual(cap + 0.5);
  expect(m.content, `칸 내용 폭(px)이 min(상한 ${cap}px, 한 줄 폭 ${m.natural}px)보다 좁다`).toBeGreaterThanOrEqual(
    Math.min(cap, m.natural) - 0.5,
  );
  expect(cap, `상한(px)이 --k 규칙의 허용 폭 ${allowed}px 를 넘는다`).toBeLessThanOrEqual(allowed + 0.5);
}

/**
 * 표 칸의 안쪽 폭(칸 폭 − 좌우 여백, px)을 잰다 (WD-658) — 값이 쓸 수 있는 실제 폭
 * @param el - 칸 안의 요소
 */
export async function measureCellInnerWidth(el: Locator): Promise<number> {
  await expect(el.locator('xpath=ancestor-or-self::td'), '표 칸(td) 안이 아니다').not.toHaveCount(0);
  return measureStable(el, (node) => {
    const td = node.closest('td') as HTMLTableCellElement;
    const cs = getComputedStyle(td);
    return td.getBoundingClientRect().width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
  });
}

/**
 * 요소와 그 안 어디에도 title 이 없는지 검증한다 (WD-658) — 표 밖에서는 말줄임 툴팁을 두지 않는다
 * @param el - 검사할 요소
 */
export async function expectNoTitle(el: Locator) {
  await expect(el).toBeVisible();
  await expect(el.locator('xpath=descendant-or-self::*[@title]')).toHaveCount(0);
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
