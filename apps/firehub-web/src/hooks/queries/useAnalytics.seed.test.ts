import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';

import type { Dashboard, DashboardListItem } from '../../types/analytics';
import { ALL_DASHBOARDS_KEY, seedDashboardInListCache } from './useAnalytics';

describe('seedDashboardInListCache', () => {
  it('widgets 가 없는 응답이어도 던지지 않고 widgetCount 0 으로 씨앗을 넣는다', () => {
    const queryClient = new QueryClient();
    // 서버 응답에 widgets 가 빠진 경우를 재현 — 씨앗이 만들기 흐름을 깨면 안 된다
    const created = { id: 5, name: '새 대시보드', isShared: false } as unknown as Dashboard;

    expect(() => seedDashboardInListCache(queryClient, created)).not.toThrow();

    const list = queryClient.getQueryData<DashboardListItem[]>(ALL_DASHBOARDS_KEY);
    expect(list?.find((d) => d.id === 5)?.widgetCount).toBe(0);
  });
});
