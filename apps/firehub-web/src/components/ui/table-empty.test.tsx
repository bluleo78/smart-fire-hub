import { act, fireEvent, render, screen } from '@testing-library/react';
import { type ComponentType, createRef, useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ANNOUNCE_DELAY_MS, SearchNoResult, TableEmptyRow, TableErrorRow } from './table-empty';

/** 빈·실패 행은 <tr> 이므로 table/tbody 로 감싸 렌더한다. */
function renderInTable<P extends object>(Row: ComponentType<P>, props: P) {
  const ui = (p: P) => (
    <table>
      <tbody>
        <Row {...p} />
      </tbody>
    </table>
  );
  const result = render(ui(props));
  return { ...result, rerenderRow: (p: P) => result.rerender(ui(p)) };
}

const renderRow = (props: Parameters<typeof TableEmptyRow>[0]) => renderInTable(TableEmptyRow, props);
const renderErrorRow = (props: Parameters<typeof TableErrorRow>[0]) => renderInTable(TableErrorRow, props);

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
    const { unmount } = renderRow({
      colSpan: 3,
      searchKeyword: 'xyz',
      onResetSearch: () => {},
      resetFocusRef: createRef<HTMLElement>(),
    });
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

describe('TableErrorRow (WD-938)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('인자가 없으면 빨간 글자·기본 여백의 셀 안 alert 이다', () => {
    renderErrorRow({ colSpan: 3 });
    flush();
    const alert = screen.getByRole('alert');
    const td = screen.getByRole('cell');
    expect(td.hasAttribute('role')).toBe(false);
    // 톤은 문구 노드에만 — 셀(action 이 물려받는 자리)에는 없다
    expect(alert.firstElementChild?.className).toContain('text-destructive');
    expect(td.className).not.toContain('text-destructive');
    expect(td.className).toContain('text-center');
    expect(td.className).not.toContain('py-12');
    expect(td.contains(alert)).toBe(true);
    expect(alert.getAttribute('aria-live')).toBe('assertive');
  });

  it('tone=muted 는 지연 전·후 모두 흐린 글자다', () => {
    renderErrorRow({ colSpan: 3, tone: 'muted' });
    const td = screen.getByRole('cell');
    const alert = screen.getByRole('status');
    for (const announced of [false, true]) {
      if (announced) flush();
      expect(alert.firstElementChild?.className).toContain('text-muted-foreground');
      expect(alert.firstElementChild?.className).not.toContain('text-destructive');
    }
    expect(td.className).not.toContain('text-muted-foreground');
  });

  it('className 은 셀에 더해진다', () => {
    renderErrorRow({ colSpan: 3, className: 'py-12 font-medium' });
    const td = screen.getByRole('cell');
    expect(td.className).toContain('py-12');
    expect(td.className).toContain('font-medium');
    expect(td.className).not.toContain('text-destructive');
  });

  it('action 은 alert 밖·같은 셀이고 지연 전후 같은 노드다', () => {
    renderErrorRow({ colSpan: 3, action: <button>다시 시도</button> });
    const before = screen.getByRole('button', { name: '다시 시도' });
    flush();
    const button = screen.getByRole('button', { name: '다시 시도' });
    const alert = screen.getByRole('alert');
    const td = screen.getByRole('cell');
    expect(button).toBe(before);
    expect(alert.contains(button)).toBe(false);
    expect(td.contains(button)).toBe(true);
    expect(button.closest('[aria-hidden="true"]')).toBeNull();
    // 톤 클래스는 action 이 든 셀에 없다 — 버튼이 빨간 글자를 물려받지 않게
    expect(td.className).not.toContain('text-destructive');
    expect(td.className).not.toContain('text-muted-foreground');
  });

  it('tone=muted 면 status·polite 로 읽는다 (WD-990)', () => {
    renderErrorRow({ colSpan: 1, tone: 'muted', message: '권한 없음' });
    expect(screen.getByRole('status').getAttribute('aria-live')).toBe('polite');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('tone 이 muted → error 로 바뀌면 alert·assertive 로 바뀐다 (WD-990)', () => {
    const { rerenderRow } = renderErrorRow({ colSpan: 1, tone: 'muted', message: '권한 없음' });
    const statusNode = screen.getByRole('status');
    rerenderRow({ colSpan: 1, tone: 'error', message: '권한 없음' });
    expect(screen.getByRole('alert').getAttribute('aria-live')).toBe('assertive');
    // 톤이 바뀌면 라이브 리전이 새 노드로 다시 마운트되어 새 긴급도로 낭독된다 (WD-990 리뷰)
    expect(screen.getByRole('alert')).not.toBe(statusNode);
    expect(statusNode.isConnected).toBe(false);
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('문구가 바뀌어도 같은 alert 다', () => {
    const { rerenderRow } = renderErrorRow({ colSpan: 3, message: '첫 문구' });
    flush();
    const alert = screen.getByRole('alert');
    rerenderRow({ colSpan: 3, message: '새 문구' });
    expect(screen.getByRole('alert')).toBe(alert);
    expect(alert.textContent).toBe('새 문구');
  });

  it('낭독된 뒤 tone 이 바뀌어도 문구 노드의 톤이 따라간다', () => {
    const { rerenderRow } = renderErrorRow({ colSpan: 3, message: '첫 문구' });
    flush();
    rerenderRow({ colSpan: 3, message: '새 문구', tone: 'muted' });
    expect(screen.getByRole('status').firstElementChild?.className).toContain('text-muted-foreground');
  });
});

describe('TableEmptyRow 검색 초기화 초점 (WD-985)', () => {
  /**
   * 실제 목록 화면처럼 검색 입력과 빈 행을 함께 그리고, 「검색 초기화」가 검색어를 지우면 빈 행(과 그 버튼)이 사라지게 한다.
   * attach=false 면 resetFocusRef 를 넘기되 어떤 요소에도 붙이지 않아 current 가 null 인 채로 둔다.
   */
  function renderWithInput(attach: boolean, spy: () => void) {
    const inputRef = createRef<HTMLInputElement>();
    const emptyRef = createRef<HTMLInputElement>();
    function Screen() {
      const [keyword, setKeyword] = useState('xyz');
      return (
        <>
          <input aria-label="검색" ref={inputRef} />
          <table>
            <tbody>
              {keyword ? (
                <TableEmptyRow
                  colSpan={3}
                  searchKeyword={keyword}
                  onResetSearch={() => {
                    spy();
                    setKeyword('');
                  }}
                  resetFocusRef={attach ? inputRef : emptyRef}
                />
              ) : (
                <tr>
                  <td>결과 행</td>
                </tr>
              )}
            </tbody>
          </table>
        </>
      );
    }
    render(<Screen />);
    return screen.getByLabelText('검색');
  }

  it('검색 초기화를 누르면 버튼이 사라져도 초점이 resetFocusRef 대상(검색 입력)에 간다 (WD-985)', () => {
    const spy = vi.fn();
    const input = renderWithInput(true, spy);
    const button = screen.getByRole('button', { name: '검색 초기화' });
    button.focus();
    fireEvent.click(button);
    expect(spy).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: '검색 초기화' })).toBeNull();
    expect(document.activeElement).toBe(input);
  });

  it('resetFocusRef 가 요소에 붙어 있지 않으면 버튼과 함께 초점이 body 로 떨어진다 — ref 를 붙여야 하는 까닭 (WD-985 리뷰 후속)', () => {
    const spy = vi.fn();
    const input = renderWithInput(false, spy);
    const button = screen.getByRole('button', { name: '검색 초기화' });
    // 버튼에 먼저 초점을 둔다 — 처음부터 body 면 아래 단언이 클릭과 무관하게 참이 된다
    button.focus();
    expect(document.activeElement).toBe(button);
    fireEvent.click(button);
    expect(spy).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: '검색 초기화' })).toBeNull();
    expect(document.activeElement).not.toBe(input);
    expect(document.activeElement).toBe(document.body);
  });

  it('onResetSearch 를 넘기면서 resetFocusRef 를 빠뜨리면 타입 오류다 (WD-985 리뷰 후속)', () => {
    // 런타임 단언이 아니라 typecheck(tsc -b)가 지키는 계약 — 아래 줄에 타입 오류가 없으면 ts-expect-error 가 typecheck 를 깨뜨린다
    // @ts-expect-error resetFocusRef 없이 onResetSearch 만 넘길 수 없다
    const missing = <TableEmptyRow colSpan={3} searchKeyword="xyz" onResetSearch={() => {}} />;
    expect(missing).toBeTruthy();
  });
});

describe('SearchNoResult 단독 사용 (WD-1127)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('표 밖에서도 검색어·안내 문구를 role=status 안에 보인다', () => {
    render(<SearchNoResult searchKeyword="abc" />);
    const status = screen.getByRole('status');
    expect(status).toHaveTextContent("'abc'");
    expect(status).toHaveTextContent('에 대한 결과가 없습니다.');
    expect(status).toHaveTextContent('다른 키워드로 검색해 보세요.');
  });

  it('onResetSearch 가 없으면 「검색 초기화」 버튼이 없다', () => {
    render(<SearchNoResult searchKeyword="abc" />);
    expect(screen.queryByRole('button', { name: '검색 초기화' })).toBeNull();
  });

  it('「검색 초기화」 클릭 시 콜백을 부르고 resetFocusRef 요소에 초점을 준다', () => {
    const onReset = vi.fn();
    const ref = createRef<HTMLInputElement>();
    render(
      <>
        <input ref={ref} aria-label="검색" />
        <SearchNoResult searchKeyword="abc" onResetSearch={onReset} resetFocusRef={ref} />
      </>,
    );
    fireEvent.click(screen.getByRole('button', { name: '검색 초기화' }));
    expect(onReset).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(ref.current);
  });
});
