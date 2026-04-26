/**
 * PageErrorBoundary 단위 테스트 (이슈 #33)
 *
 * 페이지 수준 ErrorBoundary가 렌더링 오류를 올바르게 처리하는지 검증한다.
 * - 자식 컴포넌트가 throw하면 흰 화면 대신 폴백 UI가 표시되어야 한다.
 * - "다시 시도" 버튼 클릭 시 에러 상태가 초기화되어 자식이 다시 렌더링된다.
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import React from 'react';
import { describe, expect, it, vi } from 'vitest';

import { PageErrorBoundary } from './PageErrorBoundary';

/** 렌더링 중 항상 throw하는 테스트 전용 컴포넌트 */
function ThrowingComponent({ shouldThrow }: { shouldThrow: boolean }) {
  if (shouldThrow) {
    throw new Error('[테스트] 강제 렌더링 오류');
  }
  return <div>정상 렌더링</div>;
}

describe('PageErrorBoundary', () => {
  /**
   * 테스트 1: 자식 컴포넌트가 throw할 때 폴백 UI가 표시된다
   *
   * - ThrowingComponent가 throw → PageErrorBoundary가 캐치
   * - "페이지를 불러오는 중 문제가 발생했습니다" 폴백 메시지가 표시
   * - "다시 시도" 버튼이 표시
   * - 정상 자식 ("정상 렌더링")은 숨겨짐
   */
  it('자식 컴포넌트 렌더링 오류 시 폴백 메시지와 버튼이 표시된다', () => {
    // React가 ErrorBoundary 테스트 시 console.error를 출력하므로 억제
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(
      <PageErrorBoundary>
        <ThrowingComponent shouldThrow={true} />
      </PageErrorBoundary>
    );

    // 폴백 UI 메시지 확인
    expect(screen.getByText('페이지를 불러오는 중 문제가 발생했습니다')).toBeInTheDocument();

    // "다시 시도" 버튼 확인
    expect(screen.getByRole('button', { name: /다시 시도/ })).toBeInTheDocument();

    // 정상 자식이 렌더링되지 않음 확인
    expect(screen.queryByText('정상 렌더링')).not.toBeInTheDocument();

    consoleSpy.mockRestore();
  });

  /**
   * 테스트 2: 자식 컴포넌트가 정상일 때 children을 그대로 렌더링한다
   *
   * - 오류 없으면 ErrorBoundary는 투명하게 동작 → children이 정상 렌더링
   * - 폴백 메시지가 표시되지 않음
   */
  it('정상 자식 컴포넌트는 그대로 렌더링된다', () => {
    render(
      <PageErrorBoundary>
        <ThrowingComponent shouldThrow={false} />
      </PageErrorBoundary>
    );

    // 정상 자식 렌더링 확인
    expect(screen.getByText('정상 렌더링')).toBeInTheDocument();

    // 폴백 메시지가 없음 확인
    expect(screen.queryByText('페이지를 불러오는 중 문제가 발생했습니다')).not.toBeInTheDocument();
  });

  /**
   * 테스트 3: "다시 시도" 버튼 클릭 시 에러 상태가 초기화되고 자식이 복구된다
   *
   * 전략:
   * - 처음에 ThrowingComponent(throw)로 폴백 진입
   * - rerender로 정상 자식을 전달한 뒤 버튼 클릭 → ErrorBoundary.setState({hasError:false})
   *   → children이 정상 자식이므로 정상 렌더링됨
   *
   * 구현 노트: rerender 후 버튼 클릭 순서여야 한다.
   * ErrorBoundary가 hasError=true일 때 children은 무시되므로,
   * 버튼 클릭 시점에 children이 정상화되어 있어야 복구 확인이 가능하다.
   */
  it('"다시 시도" 클릭 후 정상 자식으로 교체하면 복구된다', async () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const user = userEvent.setup();

    const { rerender } = render(
      <PageErrorBoundary>
        <ThrowingComponent shouldThrow={true} />
      </PageErrorBoundary>
    );

    // 초기: 오류 발생 → 폴백 UI
    expect(screen.getByText('페이지를 불러오는 중 문제가 발생했습니다')).toBeInTheDocument();

    // 정상 자식으로 교체 (children을 변경해도 ErrorBoundary가 hasError=true면 자식을 렌더링하지 않음)
    rerender(
      <PageErrorBoundary>
        <ThrowingComponent shouldThrow={false} />
      </PageErrorBoundary>
    );

    // 버튼 클릭 → ErrorBoundary.setState({ hasError: false }) → 정상 자식 렌더링
    await user.click(screen.getByRole('button', { name: /다시 시도/ }));

    // 리셋 후 자식이 정상 렌더링됨을 확인
    expect(screen.getByText('정상 렌더링')).toBeInTheDocument();
    expect(screen.queryByText('페이지를 불러오는 중 문제가 발생했습니다')).not.toBeInTheDocument();

    consoleSpy.mockRestore();
  });

  /**
   * 테스트 4: 오류 콘솔 로그 확인 — componentDidCatch가 오류를 기록한다
   *
   * - React ErrorBoundary의 componentDidCatch에서 console.error가 호출됨
   * - 이는 디버깅/모니터링을 위한 의도된 동작이다
   */
  it('렌더링 오류가 console.error로 기록된다', () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(
      <PageErrorBoundary>
        <ThrowingComponent shouldThrow={true} />
      </PageErrorBoundary>
    );

    // componentDidCatch에서 console.error('[PageErrorBoundary]', ...) 호출됨
    const pageErrorLogs = consoleSpy.mock.calls.filter((args) =>
      String(args[0]).includes('[PageErrorBoundary]')
    );
    expect(pageErrorLogs.length).toBeGreaterThan(0);

    consoleSpy.mockRestore();
  });
});
