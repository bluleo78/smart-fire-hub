/**
 * SaveStatusChip 단위 테스트(S2 Task 3 리뷰 IMP-2, Task 4 리뷰 IMP-5 후속)
 *
 * 프레젠테이션 컴포넌트인 이 칩은 vitest + RTL로 독립 검증한다(뮤테이션 연동은
 * EntityInspector 쪽 E2E에서 실제 실패→재시도→성공 경로로 검증).
 * - 네 상태(idle/saving/saved/error) 각각의 시각 렌더와 스크린리더 라이브 리전 문구를 검증한다.
 * - onRetry prop은 Task 4에서 실제 뮤테이션과 함께 되살아났다 — error 상태에서만 노출되고
 *   클릭 시 호출됨을 검증한다.
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import SaveStatusChip from './SaveStatusChip';

describe('SaveStatusChip', () => {
  it('idle: 시각 칩은 그리지 않지만 라이브 리전은 항상 마운트돼 있다(빈 문자열)', () => {
    render(<SaveStatusChip state="idle" />);

    expect(screen.queryByTestId('save-status-chip')).not.toBeInTheDocument();
    const liveRegion = screen.getByTestId('save-status-live-region');
    expect(liveRegion).toHaveAttribute('role', 'status');
    expect(liveRegion).toHaveAttribute('aria-live', 'polite');
    expect(liveRegion).toHaveTextContent('');
  });

  it('saving: "저장 중…" 칩과 라이브 리전 문구가 함께 보인다', () => {
    render(<SaveStatusChip state="saving" />);

    const chip = screen.getByTestId('save-status-chip');
    expect(chip).toHaveAttribute('data-state', 'saving');
    expect(chip).toHaveTextContent('저장 중');
    expect(screen.getByTestId('save-status-live-region')).toHaveTextContent('저장 중');
  });

  it('saved: "저장됨" 칩과 라이브 리전 문구가 함께 보인다', () => {
    render(<SaveStatusChip state="saved" />);

    const chip = screen.getByTestId('save-status-chip');
    expect(chip).toHaveAttribute('data-state', 'saved');
    expect(chip).toHaveTextContent('저장됨');
    expect(screen.getByTestId('save-status-live-region')).toHaveTextContent('저장됨');
  });

  it('error: "저장 실패" 칩과 라이브 리전 문구가 함께 보이고, onRetry 미전달 시 재시도 버튼은 없다', () => {
    render(<SaveStatusChip state="error" />);

    const chip = screen.getByTestId('save-status-chip');
    expect(chip).toHaveAttribute('data-state', 'error');
    expect(chip).toHaveTextContent('저장 실패');
    expect(screen.getByTestId('save-status-live-region')).toHaveTextContent('저장 실패');
    expect(screen.queryByRole('button', { name: '재시도' })).not.toBeInTheDocument();
  });

  it('error + onRetry: 재시도 버튼이 보이고 클릭하면 onRetry가 호출된다', async () => {
    const onRetry = vi.fn();
    const user = userEvent.setup();
    render(<SaveStatusChip state="error" onRetry={onRetry} />);

    const retryButton = screen.getByRole('button', { name: '재시도' });
    await user.click(retryButton);

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('saving/saved 상태에서는 onRetry를 전달해도 재시도 버튼이 없다', () => {
    const onRetry = vi.fn();
    const { rerender } = render(<SaveStatusChip state="saving" onRetry={onRetry} />);
    expect(screen.queryByRole('button', { name: '재시도' })).not.toBeInTheDocument();

    rerender(<SaveStatusChip state="saved" onRetry={onRetry} />);
    expect(screen.queryByRole('button', { name: '재시도' })).not.toBeInTheDocument();
  });
});
