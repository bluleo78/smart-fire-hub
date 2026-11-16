import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { StatusBadge } from './StatusBadge';

/** API 연결 상태 배지 — 「점검 안 함」은 헬스체크 경로 기준이다(WD-753 리뷰). */
describe('StatusBadge', () => {
  it('경로가 없으면 마지막 상태(DOWN·UP·없음)와 상관없이 「점검 안 함」이다', () => {
    for (const status of ['DOWN', 'UP', null] as const) {
      const { unmount } = render(
        <StatusBadge status={status} checkedAt="2026-11-16T00:00:00" healthCheckPath={null} />,
      );
      const badge = screen.getByText('점검 안 함');
      expect(badge.closest('[title]')?.getAttribute('title')).toBe('헬스체크 경로가 없어 자동 점검하지 않습니다');
      expect(screen.queryByText('이상')).toBeNull();
      expect(screen.queryByText('정상')).toBeNull();
      unmount();
    }
    // 공백뿐인 경로도 경로 없음이다
    render(<StatusBadge status="DOWN" checkedAt={null} healthCheckPath="  " />);
    expect(screen.getByText('점검 안 함')).toBeTruthy();
  });

  it('경로가 있으면 상태대로 보이고, 상태가 없으면 「미확인」이다', () => {
    const { rerender } = render(<StatusBadge status={null} checkedAt={null} healthCheckPath="/health" />);
    expect(screen.getByText('미확인')).toBeTruthy();
    rerender(<StatusBadge status="DOWN" checkedAt={null} healthCheckPath="/health" />);
    expect(screen.getByText('이상')).toBeTruthy();
    rerender(<StatusBadge status="UP" checkedAt={null} healthCheckPath="/health" />);
    expect(screen.getByText('정상')).toBeTruthy();
  });
});
