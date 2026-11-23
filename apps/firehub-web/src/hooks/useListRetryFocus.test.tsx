/**
 * useListRetryFocus 단위 테스트 (WD-987)
 *
 * 「다시 시도」 를 누르면 표로 초점을 옮기고, 재조회가 다시 실패했을 때만(사용자가 그 사이 초점을 옮기지 않았다면)
 * 버튼으로 되돌리는지, 표 mousedown 에서 tabindex 를 언제 떼는지 확인한다.
 */
import { fireEvent, render, renderHook, screen } from '@testing-library/react';
import { AxiosError, type AxiosResponse } from 'axios';
import { createPortal } from 'react-dom';
import { describe, expect, it, vi } from 'vitest';

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
function Harness(props: Props) {
  const { tableProps, retryAction } = useListRetryFocus(props);
  return (
    <div>
      <table aria-label="표" {...tableProps}>
        <tbody>
          <tr>
            <td>
              {retryAction}
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
