/**
 * useListRetryFocus 단위 테스트 (WD-987)
 *
 * 「다시 시도」 를 누르면 표로 초점을 옮기고, 재조회가 다시 실패했을 때만(사용자가 그 사이 초점을 옮기지 않았다면)
 * 버튼으로 되돌리는지, 표 mousedown 에서 tabindex 를 언제 떼는지 확인한다.
 */
import { act, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { AxiosError, type AxiosResponse } from 'axios';
import { StrictMode } from 'react';
import { createPortal } from 'react-dom';
import { describe, expect, it, vi } from 'vitest';

import { MAIN_CONTENT_ID } from '../lib/focus-lost';
import { useListRetryFocus } from './useListRetryFocus';

interface Props {
  isPending: boolean;
  isError: boolean;
  isFetching: boolean;
  data: unknown;
  error: unknown;
  refetch: () => unknown;
}

// 훅을 표·버튼·바깥 입력에 연결하는 시험용 컴포넌트 — 실제 페이지처럼 tableProps·retryAction 을 그대로 펼쳐 단다
// rowKey 를 바꾸면 버튼을 감싼 요소가 다시 마운트된다(부모가 오류 행을 새로 그리는 경우 시늉)
function Harness({ rowKey = 'a', ...props }: Props & { rowKey?: string }) {
  const { tableProps, retryAction } = useListRetryFocus(props);
  return (
    <div>
      <table aria-label="표" {...tableProps}>
        <tbody>
          <tr>
            <td>
              <span key={rowKey}>{retryAction}</span>
              <span data-testid="cell">셀</span>
              {createPortal(<span data-testid="portal">포털</span>, document.body)}
            </td>
          </tr>
        </tbody>
      </table>
      <input aria-label="밖" />
    </div>
  );
}

// 데이터 없이 5xx 로 실패한 상태 — 「다시 시도」 가 보인다
const failed = (refetch = vi.fn()): Props => ({
  isPending: false,
  isError: true,
  isFetching: false,
  data: undefined,
  error: new Error('x'),
  refetch,
});
// 재조회 중 — 쿼리가 pending 이고 실패 상태가 아니다(버튼 없음)
const PENDING = { isPending: true, isError: false, isFetching: true, error: null } as const;
// 버튼 ref 정리 함수가 미룬 판단(queueMicrotask)이 돌 때까지 기다린다
const flushMicrotasks = () => act(async () => {});
// 403 로 실패한 상태 — 버튼을 두지 않는다
const forbidden = () =>
  new AxiosError('forbidden', 'ERR_BAD_REQUEST', undefined, undefined, { status: 403 } as AxiosResponse);

describe('useListRetryFocus', () => {
  it('retryList 누르면 표로 초점이 가고 tabindex=-1, refetch 1회', () => {
    const refetch = vi.fn();
    render(<Harness {...failed(refetch)} />);
    const button = screen.getByRole('button', { name: '다시 시도' });
    button.focus();
    fireEvent.click(button);
    const table = screen.getByRole('table');
    expect(document.activeElement).toBe(table);
    expect(table.getAttribute('tabindex')).toBe('-1');
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('재조회가 다시 실패(isPending true→false, 데이터 없는 non-403 실패)하면 버튼으로 초점', () => {
    const p = failed();
    const { rerender } = render(<Harness {...p} />);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    rerender(<Harness {...p} {...PENDING} />);
    rerender(<Harness {...p} error={new Error('y')} />);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '다시 시도' }));
  });

  it('재조회 성공(데이터 도착)이면 표에 남음', () => {
    const p = failed();
    const { rerender } = render(<Harness {...p} />);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    rerender(<Harness {...p} {...PENDING} />);
    rerender(<Harness {...p} isError={false} data={[]} error={null} />);
    expect(document.activeElement).toBe(screen.getByRole('table'));
  });

  it('재조회가 403(버튼 없음)이면 표에 남음', () => {
    const p = failed();
    const { rerender } = render(<Harness {...p} />);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    rerender(<Harness {...p} {...PENDING} />);
    rerender(<Harness {...p} error={forbidden()} />);
    expect(document.activeElement).toBe(screen.getByRole('table'));
  });

  it('그 사이 사용자가 밖 input 으로 옮겼으면 다시 실패해도 초점 유지', () => {
    const p = failed();
    const { rerender } = render(<Harness {...p} />);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    rerender(<Harness {...p} {...PENDING} />);
    const input = screen.getByLabelText('밖');
    input.focus();
    rerender(<Harness {...p} error={new Error('y')} />);
    expect(document.activeElement).toBe(input);
  });

  it('한 번 판단한 뒤 이후의 실패(isPending 다시 true→false)는 초점을 옮기지 않음', () => {
    const p = failed();
    const { rerender } = render(<Harness {...p} />);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    rerender(<Harness {...p} {...PENDING} />);
    rerender(<Harness {...p} error={new Error('y')} />);
    // 판단 끝 — 사용자가 표로 초점을 옮긴 뒤 또 한 번 실패해도 버튼으로 옮기지 않는다
    screen.getByRole('table').focus();
    rerender(<Harness {...p} {...PENDING} />);
    rerender(<Harness {...p} error={new Error('z')} />);
    expect(document.activeElement).toBe(screen.getByRole('table'));
  });

  // 리뷰 후속(PR #1218 ①): 누르지 않고 저절로 다시 불러올 때(다시 연결 refetch·무효화·다시 마운트)
  // — 창 포커스 refetch 는 앱(main.tsx)에서 꺼져 있어 해당하지 않는다 (WD-1005)
  it('저절로 재조회돼 초점 가진 버튼이 사라지면 표로 초점, 다시 실패하면 버튼으로', async () => {
    const p = failed();
    const { rerender } = render(<Harness {...p} />);
    screen.getByRole('button', { name: '다시 시도' }).focus();
    rerender(<Harness {...p} {...PENDING} />);
    // 버튼이 실제로 빠졌는지는 커밋 뒤 마이크로태스크에서 판단한다
    await flushMicrotasks();
    const table = screen.getByRole('table');
    expect(document.activeElement).toBe(table);
    expect(table.getAttribute('tabindex')).toBe('-1');
    rerender(<Harness {...p} error={new Error('y')} />);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '다시 시도' }));
  });

  it('저절로 재조회될 때 버튼에 초점이 없었으면 초점을 옮기지 않음', async () => {
    const p = failed();
    const { rerender } = render(<Harness {...p} />);
    const input = screen.getByLabelText('밖');
    input.focus();
    rerender(<Harness {...p} {...PENDING} />);
    await flushMicrotasks();
    expect(document.activeElement).toBe(input);
    rerender(<Harness {...p} error={new Error('y')} />);
    expect(document.activeElement).toBe(input);
  });

  // 개발 모드 StrictMode 는 새 버튼의 ref 를 떼었다 다시 붙인다 — 그때 초점을 표로 빼앗지 않아야 한다(E2E 회귀로 발견)
  it('StrictMode 에서도 다시 실패하면 초점이 새 버튼에 남는다', async () => {
    const p = failed();
    const { rerender } = render(<Harness {...p} />, { wrapper: StrictMode });
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    rerender(<Harness {...p} {...PENDING} />);
    rerender(<Harness {...p} error={new Error('y')} />);
    await flushMicrotasks();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '다시 시도' }));
  });

  it('StrictMode 에서도 저절로 재조회돼 버튼이 사라지면 표로 초점', async () => {
    const p = failed();
    const { rerender } = render(<Harness {...p} />, { wrapper: StrictMode });
    screen.getByRole('button', { name: '다시 시도' }).focus();
    rerender(<Harness {...p} {...PENDING} />);
    await flushMicrotasks();
    expect(document.activeElement).toBe(screen.getByRole('table'));
  });

  it('재조회 없이 버튼이 다시 마운트되면 초점이 새 버튼으로 간다', async () => {
    const p = failed();
    const { rerender } = render(<Harness {...p} />);
    screen.getByRole('button', { name: '다시 시도' }).focus();
    rerender(<Harness {...p} rowKey="b" />);
    await flushMicrotasks();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '다시 시도' }));
  });

  it('재조회 없이 버튼이 사라지면(데이터를 직접 채움) 표로만 옮기고, 이후 실패가 초점을 끌어가지 않는다', async () => {
    const p = failed();
    const { rerender } = render(<Harness {...p} />);
    screen.getByRole('button', { name: '다시 시도' }).focus();
    rerender(<Harness {...p} isError={false} data={[]} error={null} />);
    await flushMicrotasks();
    const table = screen.getByRole('table');
    expect(document.activeElement).toBe(table);
    // 재조회를 기다리는 표시가 없으므로, 나중에 실패로 바뀌어도 버튼으로 옮기지 않는다
    rerender(<Harness {...p} error={new Error('later')} />);
    expect(document.activeElement).toBe(table);
  });

  // WD-980: 버튼이 사라진 뒤 포커스가 body 가 아니라 본문 main#main-content(건너뛰기 뒤 tabindex=-1 이 남은 것)에 떨어져도 표로 옮긴다.
  // 같은 틱(마이크로태스크 전)에 main 에 포커스를 둬 시늉한다. 'body' 는 기존 동작 회귀 확인.
  it.each(['main', 'body'] as const)('저절로 재조회돼 버튼이 사라진 뒤 초점이 %s 에 떨어져도 표로 초점', async (landing) => {
    const main = document.createElement('main');
    main.id = MAIN_CONTENT_ID;
    main.tabIndex = -1;
    document.body.appendChild(main);
    try {
      const p = failed();
      const { rerender } = render(<Harness {...p} />);
      screen.getByRole('button', { name: '다시 시도' }).focus();
      rerender(<Harness {...p} {...PENDING} />);
      if (landing === 'main') main.focus();
      expect(document.activeElement).toBe(landing === 'main' ? main : document.body);
      await flushMicrotasks();
      const table = screen.getByRole('table');
      expect(document.activeElement).toBe(table);
      expect(table.getAttribute('tabindex')).toBe('-1');
    } finally {
      main.remove();
    }
  });

  // 리뷰 후속(PR #1218 ②): 화면이 준 className 이 포커스 링을 덮지 않고 합쳐진다
  it('tableClassName 은 포커스 링 className 과 합쳐진다', () => {
    const { result } = renderHook(() => useListRetryFocus({ ...failed(), tableClassName: 'min-w-[600px]' }));
    expect(result.current.tableProps.className).toContain('focus-visible:outline-ring');
    expect(result.current.tableProps.className).toContain('min-w-[600px]');
  });

  it('isPlaceholderData 면 흐림·aria-busy·tbody inert 를 돌려주고, 기본값에서는 아니다 (WD-982)', () => {
    const { result } = renderHook(() => useListRetryFocus({ ...failed(), isPlaceholderData: true }));
    expect(result.current.tableProps.className).toContain('pointer-events-none opacity-60');
    expect(result.current.tableProps['aria-busy']).toBe(true);
    expect(result.current.bodyProps.inert).toBe(true);
    // placeholder 는 pending 으로 본다 — 데이터가 없는 실패여도 이동 중에는 아직 끝나지 않은 것
    const { result: dflt } = renderHook(() => useListRetryFocus(failed()));
    expect(dflt.current.tableProps.className).not.toContain('opacity-60');
    expect(dflt.current.tableProps['aria-busy']).toBeUndefined();
    expect(dflt.current.bodyProps.inert).toBe(false);
  });

  it('403 이면 showRetry 가 false 이고 retryAction 이 없다', () => {
    const { result } = renderHook(() => useListRetryFocus({ ...failed(), error: forbidden() }));
    expect(result.current.showRetry).toBe(false);
    expect(result.current.retryAction).toBeUndefined();
  });

  describe('표 mousedown', () => {
    it('초점이 표에 없으면 tabindex 제거', () => {
      render(<Harness {...failed()} />);
      const table = screen.getByRole('table');
      table.tabIndex = -1;
      screen.getByLabelText('밖').focus();
      fireEvent.mouseDown(screen.getByTestId('cell'));
      expect(table.hasAttribute('tabindex')).toBe(false);
    });

    it('초점이 표에 있으면 유지', () => {
      render(<Harness {...failed()} />);
      const table = screen.getByRole('table');
      table.tabIndex = -1;
      table.focus();
      fireEvent.mouseDown(screen.getByTestId('cell'));
      expect(table.getAttribute('tabindex')).toBe('-1');
    });

    it('target 이 표 밖(포털 시늉)이면 무시', () => {
      render(<Harness {...failed()} />);
      const table = screen.getByRole('table');
      table.tabIndex = -1;
      screen.getByLabelText('밖').focus();
      // 포털 안 요소의 mousedown 은 합성 이벤트로 표 핸들러까지 버블링되지만 DOM 상 표 밖이다
      fireEvent.mouseDown(screen.getByTestId('portal'));
      expect(table.getAttribute('tabindex')).toBe('-1');
    });
  });
});
