import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ANNOUNCE_DELAY_MS, TableEmptyRow } from './table-empty';

/** TableEmptyRow 는 <tr> 이므로 table/tbody 로 감싸 렌더한다. */
function renderRow(props: Parameters<typeof TableEmptyRow>[0]) {
  const ui = (p: Parameters<typeof TableEmptyRow>[0]) => (
    <table>
      <tbody>
        <TableEmptyRow {...p} />
      </tbody>
    </table>
  );
  const result = render(ui(props));
  return { ...result, rerenderRow: (p: Parameters<typeof TableEmptyRow>[0]) => result.rerender(ui(p)) };
}

/** status 안 문구가 아직 접근성 트리에서 빠져 있는지(aria-hidden) — 화면 문구는 처음부터 있다 */
function hiddenFromAT(status: HTMLElement) {
  return status.querySelector('[aria-hidden="true"]') !== null;
}

/** ANNOUNCE_DELAY_MS 지연(ready 전환)까지 타이머를 돌린다. */
function flush() {
  act(() => {
    vi.runAllTimers();
  });
}

describe('TableEmptyRow 라이브 리전 (WD-815)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('문구는 마운트 직후부터 보이고, 지연 뒤에야 접근성 트리에 드러난다', () => {
    renderRow({ colSpan: 3, searchKeyword: 'xyz' });
    const status = screen.getByRole('status');
    // 화면 문구는 바로 있다(깜빡임 없음) — 낭독만 aria-hidden 으로 늦춘다
    expect(status.textContent).toBe("'xyz'에 대한 결과가 없습니다.다른 키워드로 검색해 보세요.");
    expect(hiddenFromAT(status)).toBe(true);
    flush();
    expect(hiddenFromAT(status)).toBe(false);
    expect(status.textContent).toBe("'xyz'에 대한 결과가 없습니다.다른 키워드로 검색해 보세요.");
    expect(status.getAttribute('aria-live')).toBe('polite');
    expect(status.getAttribute('aria-atomic')).toBe('true');
  });

  it('기본 빈 상태도 status 로 안내한다', () => {
    renderRow({ colSpan: 3, message: '조건에 맞는 감사 로그가 없습니다.' });
    flush();
    expect(screen.getByRole('status').textContent).toBe('조건에 맞는 감사 로그가 없습니다.');
  });

  it('키워드가 바뀌면 같은 status 안 문구가 바뀐다', () => {
    const { rerenderRow } = renderRow({ colSpan: 3, searchKeyword: 'xyz' });
    flush();
    const status = screen.getByRole('status');
    rerenderRow({ colSpan: 3, searchKeyword: 'abcd' });
    expect(screen.getByRole('status')).toBe(status);
    expect(status.textContent).toContain('abcd');
  });

  // 분기 전환 양방향 — status 가 같은 요소로 남아야 새 문구가 라이브 리전 변경으로 낭독된다
  it.each([
    ['검색 0건 → 기본 빈 상태', { searchKeyword: 'xyz' }, {}, '데이터가 없습니다.'],
    ['기본 빈 상태 → 검색 0건', {}, { searchKeyword: 'xyz' }, "'xyz'"],
  ])('%s 로 바뀌어도 status 는 같은 요소다', (_name, from, to, expected) => {
    const { rerenderRow } = renderRow({ colSpan: 3, ...from });
    flush();
    const status = screen.getByRole('status');
    rerenderRow({ colSpan: 3, ...to });
    flush();
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toBe(status);
    expect(status.textContent).toContain(expected);
  });

  it('지연 뒤에는 숨긴 사본 대신 새 노드가 들어온다(속성만 떼지 않고 실제 노드 추가로 낭독되게)', () => {
    renderRow({ colSpan: 3, searchKeyword: 'xyz' });
    const status = screen.getByRole('status');
    const pending = status.firstElementChild;
    expect(pending?.getAttribute('aria-hidden')).toBe('true');
    flush();
    const announced = status.firstElementChild;
    expect(announced).not.toBe(pending);
    expect(pending?.isConnected).toBe(false);
    expect(announced?.hasAttribute('aria-hidden')).toBe(false);
    expect(announced?.textContent).toBe("'xyz'에 대한 결과가 없습니다.다른 키워드로 검색해 보세요.");
  });

  it('announceKey 가 바뀌면 같은 문구라도 새 노드가 들어와 다시 낭독된다', () => {
    const { rerenderRow } = renderRow({ colSpan: 3, message: '검색 결과가 없습니다.', announceKey: 'abc' });
    flush();
    const status = screen.getByRole('status');
    const first = status.firstElementChild;
    rerenderRow({ colSpan: 3, message: '검색 결과가 없습니다.', announceKey: 'abcx' });
    const second = status.firstElementChild;
    expect(second).not.toBe(first);
    expect(second?.hasAttribute('aria-hidden')).toBe(false);
    expect(second?.textContent).toBe('검색 결과가 없습니다.');
  });

  it('지연(ANNOUNCE_DELAY_MS) 전에는 낭독에서 빠져 있고 지난 뒤에 드러난다', () => {
    renderRow({ colSpan: 3 });
    const status = screen.getByRole('status');
    act(() => {
      vi.advanceTimersByTime(ANNOUNCE_DELAY_MS - 1);
    });
    expect(hiddenFromAT(status)).toBe(true);
    expect(status.textContent).toBe('데이터가 없습니다.');
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(hiddenFromAT(status)).toBe(false);
  });

  it('검색 초기화 버튼·emptyAction 은 status 밖이고 마운트 때부터 보인다', () => {
    const { unmount } = renderRow({ colSpan: 3, searchKeyword: 'xyz', onResetSearch: () => {} });
    const button = screen.getByRole('button', { name: '검색 초기화' });
    expect(screen.getByRole('status').contains(button)).toBe(false);
    unmount();

    renderRow({ colSpan: 3, emptyAction: <button>새로 만들기</button> });
    const action = screen.getByRole('button', { name: '새로 만들기' });
    expect(screen.getByRole('status').contains(action)).toBe(false);
  });

  it('ready 전에 언마운트하면 타이머가 정리된다', () => {
    const { unmount } = renderRow({ colSpan: 3 });
    // 다른 자식이 타이머를 더 걸어도 깨지지 않게 「언마운트로 줄어든다」만 확인한다
    const before = vi.getTimerCount();
    expect(before).toBeGreaterThan(0);
    unmount();
    expect(vi.getTimerCount()).toBeLessThan(before);
  });
});
