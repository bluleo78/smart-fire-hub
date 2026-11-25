/**
 * 「대시보드에 추가」 성공 뒤 이동에서 예외가 나도 실패로 알리지 않는다 (WD-1148 반려 ③)
 *
 * try 가 `addWidget.mutateAsync` 만 감싸야 한다 — 토스트·닫기·onNavigate 의 예외가 같은 try 에 있으면
 * 이미 들어간 위젯을 「차트 추가 실패」로 알려, 사용자가 다시 눌러 위젯이 중복된다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { analyticsApi } from '../../api/analytics';
import { AuthContext, type AuthContextValue } from '../../hooks/auth-context-value';
import { AddToDashboardDialog } from './ChartBuilderPage';

const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));
vi.mock('sonner', () => ({ toast: toastMock }));

/** 대시보드 작성자(createdBy=1)와 같은 사용자로 로그인한 인증 스텁 */
const authValue = { user: { id: 1 }, isLoading: false } as unknown as AuthContextValue;

describe('AddToDashboardDialog 성공 뒤 이동 예외 (WD-1148)', () => {
  beforeEach(() => {
    toastMock.success.mockClear();
    toastMock.error.mockClear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('onNavigate 가 던져도 「추가 실패」 토스트를 띄우지 않고 위젯은 한 번만 보낸다', async () => {
    const page = {
      content: [{ id: 10, name: '영업 대시보드', description: null, createdBy: 1 }],
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
    };
    vi.spyOn(analyticsApi, 'listDashboards').mockResolvedValue({ data: page } as never);
    vi.spyOn(analyticsApi, 'getDashboard').mockResolvedValue({ data: { id: 10, widgets: [] } } as never);
    const addWidget = vi.spyOn(analyticsApi, 'addWidget').mockResolvedValue({ data: { id: 999 } } as never);
    const onOpenChange = vi.fn();
    // 이동 단계에서 예외 — 위젯은 이미 들어갔다
    const onNavigate = vi.fn(() => {
      throw new Error('navigate boom');
    });

    // 성공 뒤 예외는 console.error 로만 기록된다 — 시험 출력을 어지럽히지 않게 막고 호출만 확인한다
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <AuthContext.Provider value={authValue}>
            <AddToDashboardDialog
              open
              onOpenChange={onOpenChange}
              chartId={1}
              chartType="BAR"
              onNavigate={onNavigate}
            />
          </AuthContext.Provider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await userEvent.click(await screen.findByText('영업 대시보드'));
    await userEvent.click(screen.getByRole('button', { name: '추가' }));

    // 성공 토스트·닫기·이동까지 진행됐고, 실패 토스트는 없다
    await waitFor(() => expect(onNavigate).toHaveBeenCalledTimes(1));
    expect(toastMock.success).toHaveBeenCalledTimes(1);
    expect(onOpenChange).toHaveBeenCalledWith(false);
    expect(toastMock.error).not.toHaveBeenCalled();
    expect(addWidget).toHaveBeenCalledTimes(1);
    expect(consoleError).toHaveBeenCalled();
  });
});
