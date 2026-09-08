/**
 * WidgetFreshnessBar 단위 테스트 (#540 회귀 방지)
 *
 * 자동 갱신이 꺼진("수동", refreshSeconds가 없거나 0) 위젯에서도 경과 시간 표시("방금",
 * "N초 전" 등)가 실제 흐른 시간을 반영해 계속 갱신돼야 한다. 과거에는 now를 갱신하는
 * interval이 refreshSeconds 유무에 잘못 묶여 있어, 수동 모드에서는 마운트 시점 값에
 * 고정된 채 절대 바뀌지 않았다(#540). fake timer로 시간을 흘려보내며 검증한다.
 */
import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { WidgetFreshnessBar } from './WidgetFreshnessBar';

describe('WidgetFreshnessBar', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('자동 갱신이 꺼진(refreshSeconds 없음) 위젯도 시간이 흐르면 경과 시간 표시가 갱신된다', () => {
    const dataUpdatedAt = Date.now();
    render(
      <WidgetFreshnessBar
        dataUpdatedAt={dataUpdatedAt}
        isFetching={false}
        refreshSeconds={undefined}
        onRefresh={vi.fn()}
      />
    );

    expect(screen.getByText('방금')).toBeInTheDocument();

    // 20초 경과 — now를 갱신하는 interval(10초 간격)이 refreshSeconds와 무관하게
    // 동작해야 elapsed가 실제 흐른 시간을 반영한다.
    act(() => {
      vi.advanceTimersByTime(20_000);
    });

    expect(screen.queryByText('방금')).not.toBeInTheDocument();
    expect(screen.getByText('20초 전')).toBeInTheDocument();
  });

  it('refreshSeconds가 0인 경우도 동일하게 경과 시간 표시가 갱신된다', () => {
    const dataUpdatedAt = Date.now();
    render(
      <WidgetFreshnessBar
        dataUpdatedAt={dataUpdatedAt}
        isFetching={false}
        refreshSeconds={0}
        onRefresh={vi.fn()}
      />
    );

    act(() => {
      vi.advanceTimersByTime(65_000);
    });

    expect(screen.getByText('1분 전')).toBeInTheDocument();
  });

  it('자동 갱신이 켜진 경우(refreshSeconds > 0)도 계속 갱신된다(기존 동작 유지)', () => {
    const dataUpdatedAt = Date.now();
    render(
      <WidgetFreshnessBar
        dataUpdatedAt={dataUpdatedAt}
        isFetching={false}
        refreshSeconds={30}
        onRefresh={vi.fn()}
      />
    );

    act(() => {
      vi.advanceTimersByTime(20_000);
    });

    expect(screen.getByText('20초 전')).toBeInTheDocument();
  });
});
