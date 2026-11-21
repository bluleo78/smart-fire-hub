/**
 * SchemaExplorer 빈 목록 상태 단위 테스트 (WD-912 리뷰 후속)
 *
 * 마지막 조회 오류(lastError)는 데이터 없이 재조회하는 동안 오류 UI 를 붙잡아 두는 용도다.
 * - 재조회가 성공하면 기억한 오류를 비워, 나중에 쿼리가 데이터 없이 다시 pending 이 되어도(쿼리 reset·캐시 제거)
 *   옛 오류와 잠긴 「다시 시도」가 아니라 「불러오는 중」이 보인다.
 * - 오프라인으로 멈춘(paused) 재조회는 성공이 아니라 오류 UI 를 지키고, 버튼은 재조회 중(isFetching)일 때만 잠근다.
 * - 다시 실패하면 새 오류를 기억해, 이어지는 자동 재조회 동안에도 최신 문구가 보인다.
 */
import { fireEvent, render, screen } from '@testing-library/react';
import { AxiosError, type AxiosResponse } from 'axios';
import type { ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { SchemaExplorer } from './SchemaExplorer';

type Props = ComponentProps<typeof SchemaExplorer>;

const base: Props = {
  tables: [],
  onInsertAtCursor: () => {},
  onRetry: () => {},
};

/** 서버 문구(message)를 담은 axios 오류 — 오류 UI 가 상세 문구로 보여 준다 */
function apiError(message: string) {
  const response = { status: 500, data: { message }, headers: {}, config: {}, statusText: '' } as unknown as AxiosResponse;
  return new AxiosError(message, 'ERR_BAD_RESPONSE', undefined, undefined, response);
}

const failure = apiError('서버 오류');

describe('SchemaExplorer 빈 목록 — 다시 시도 오류 기억 (WD-912)', () => {
  it('다시 시도가 성공한 뒤 쿼리가 다시 pending 이 되면 옛 오류가 아니라 불러오는 중을 보인다', () => {
    const onRetry = vi.fn();
    const { rerender } = render(<SchemaExplorer {...base} onRetry={onRetry} error={failure} />);

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(onRetry).toHaveBeenCalledTimes(1);

    // 재조회 중 — react-query 가 error 를 비우고 pending 으로 돌린다. 오류 UI 는 유지되고 버튼은 잠긴다.
    rerender(<SchemaExplorer {...base} onRetry={onRetry} isLoading isFetching error={null} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toHaveAttribute('aria-disabled', 'true');

    // 재조회 성공(0건)
    rerender(<SchemaExplorer {...base} onRetry={onRetry} error={null} />);
    expect(screen.getByText('표시할 테이블이 없습니다.')).toBeInTheDocument();

    // 쿼리 reset·캐시 제거로 데이터 없이 다시 pending(아직 요청 전)
    rerender(<SchemaExplorer {...base} onRetry={onRetry} isLoading error={null} />);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('테이블 정보를 불러오는 중...')).toBeInTheDocument();
  });

  it('오류가 있어도 재조회 중이 아니면 다시 시도 버튼은 잠기지 않는다', () => {
    render(<SchemaExplorer {...base} error={failure} isFetching={false} />);
    expect(screen.getByRole('button', { name: '다시 시도' })).not.toHaveAttribute('aria-disabled', 'true');
  });

  it('재조회가 다시 실패하면 새 오류와 풀린 다시 시도 버튼이 보인다', () => {
    const { rerender } = render(<SchemaExplorer {...base} error={failure} />);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    rerender(<SchemaExplorer {...base} isLoading isFetching error={null} />);

    rerender(<SchemaExplorer {...base} error={apiError('두 번째 실패')} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).not.toHaveAttribute('aria-disabled', 'true');
  });

  it('오프라인으로 멈춘 재조회(pending·isFetching false)에서는 오류 UI 와 풀린 버튼을 지킨다', () => {
    const { rerender } = render(<SchemaExplorer {...base} error={failure} />);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    rerender(<SchemaExplorer {...base} isLoading isFetching={false} error={null} />);

    expect(screen.getByRole('alert')).toHaveTextContent('서버 오류');
    expect(screen.getByRole('button', { name: '다시 시도' })).not.toHaveAttribute('aria-disabled', 'true');
  });

  it('다시 실패한 뒤 자동 재조회 동안에는 가장 최근 오류 문구를 보인다', () => {
    const { rerender } = render(<SchemaExplorer {...base} error={failure} />);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    rerender(<SchemaExplorer {...base} isLoading isFetching error={null} />);
    rerender(<SchemaExplorer {...base} error={apiError('두 번째 실패')} />);

    // 창 포커스 등 사용자가 누르지 않은 재조회
    rerender(<SchemaExplorer {...base} isLoading isFetching error={null} />);
    expect(screen.getByRole('alert')).toHaveTextContent('두 번째 실패');
    expect(screen.getByRole('alert')).not.toHaveTextContent('서버 오류');
  });

  it('다시 시도를 누른 적이 없어도 자동 재조회 동안 오류 UI 를 지키고 버튼을 잠근다', () => {
    const { rerender } = render(<SchemaExplorer {...base} error={failure} />);
    rerender(<SchemaExplorer {...base} isLoading isFetching error={null} />);

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toHaveAttribute('aria-disabled', 'true');
  });
});
