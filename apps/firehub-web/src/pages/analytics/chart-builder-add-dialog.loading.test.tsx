/**
 * 「대시보드에 추가」 목록의 사용자 로딩 판정 (WD-1106 ⑤)
 *
 * `user == null` 을 로딩으로 보면, 사용자 조회가 실패해 user 가 계속 null 인 동안
 * 목록이 「불러오는 중」으로만 남아 빈 상태(만들기 입력)에 못 가는 무한 로딩이 생긴다.
 * 인증 로딩(useAuth().isLoading)이 끝난 뒤 user 가 없으면 무한 로딩 대신 안내를 보이되(WD-1148:
 * 빈 상태로 보내면 안 된다 — 내 대시보드를 못 가른 거짓 빈 목록), 만들기 입력은 숨기는지 검증한다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import { analyticsApi } from '../../api/analytics';
import { AuthContext, type AuthContextValue } from '../../hooks/auth-context-value';
import { AddToDashboardDialog } from './ChartBuilderPage';

/** 이 다이얼로그(useAuth·useIsOwner)가 쓰는 필드만 실제 값으로 준 최소 인증 스텁 */
const authValue = {
  user: null, // 사용자 조회가 끝났는데 실패한 상태 — user 는 계속 없음
  isLoading: false, // 인증 로딩은 끝났다 — 여기를 true 로 두면 로딩이 끝난 뒤에도 목록이 계속 로딩으로만 남는다
} as AuthContextValue;

/** 대시보드 목록을 빈 배열로 성공시킨다 — E2E 목록 모킹을 단위 시험용으로 옮긴 것 */
function mockEmptyDashboards() {
  const emptyPage = { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 };
  // AxiosResponse 의 나머지 필드(status 등) 는 이 시험에서 쓰이지 않아 생략한다(as never 는 기존 시험 패턴)
  vi.spyOn(analyticsApi, 'listDashboards').mockResolvedValue({ data: emptyPage } as never);
}

function renderDialog() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AuthContext.Provider value={authValue}>
          <AddToDashboardDialog
            open
            onOpenChange={() => {}}
            chartId={1}
            chartType="BAR"
            onNavigate={() => {}}
          />
        </AuthContext.Provider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AddToDashboardDialog 사용자 로딩 판정 (WD-1106 ⑤, WD-1148)', () => {
  it('인증 로딩이 끝났는데 user 가 null 이면 빈 상태·만들기 대신 새로 고침 안내를 보인다', async () => {
    mockEmptyDashboards();
    renderDialog();

    // 목록 쿼리가 가라앉을 때까지 — 그동안 로딩 문구는 나올 수 있다
    await waitFor(() =>
      expect(screen.queryByText('대시보드 목록을 불러오는 중...')).not.toBeInTheDocument(),
    );
    // user 가 없으면 내 대시보드를 가를 수 없어 빈 목록이 거짓이다 — 안내만 보이고 만들기는 숨는다
    expect(screen.getByRole('alert')).toHaveTextContent(
      '사용자 정보를 확인하지 못해 대시보드 목록을 보일 수 없습니다. 페이지를 새로 고친 뒤 다시 시도하세요.',
    );
    expect(screen.queryByText(/아직 대시보드가 없습니다/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText('새 대시보드 이름')).not.toBeInTheDocument();
  });
});
