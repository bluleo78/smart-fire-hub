import { describe, expect, it } from 'vitest';

import { navLocationKey } from './nav-location';

describe('navLocationKey (WD-1109 반려 1)', () => {
  it('경로만 돌려준다 — 쿼리·해시는 떠남이 아니다', () => {
    window.history.replaceState(null, '', '/ai-insights/jobs/7?tab=executions#top');
    // 탭 전환·해시 이동은 폼을 들고 있는 화면 안의 일이라 위치 축에 세면 성공한 저장이 이탈로 읽힌다
    expect(navLocationKey()).toBe('/ai-insights/jobs/7');
  });
  it('경로가 바뀌어야 값이 바뀐다', () => {
    window.history.replaceState(null, '', '/ai-insights/jobs/8');
    expect(navLocationKey()).toBe('/ai-insights/jobs/8');
    window.history.replaceState(null, '', '/');
    expect(navLocationKey()).toBe('/');
  });
});
