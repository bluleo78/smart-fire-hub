/**
 * 스크롤바 자동 강조 전역 리스너 테스트
 *
 * 무엇: 스크롤이 발생한 엘리먼트에 `data-scrolling="true"`가 붙고 유휴 800ms 뒤 사라지는지,
 *       그리고 여러 스크롤 컨테이너가 서로의 타이머를 침범하지 않는지 검증한다.
 * 왜:   스크롤 이벤트는 **버블링하지 않는다.** 캡처 단계로 듣지 않으면 앱 안의 72개 스크롤
 *       컨테이너 중 어느 것도 잡히지 않아 index.css의 `[data-scrolling]` 규칙이 죽는다.
 *       이 전제가 깨지면 조용히 아무 일도 안 일어나므로 테스트로 고정한다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { installScrollbarAutoHide, SCROLLBAR_IDLE_MS } from './scrollbar-autohide';

describe('installScrollbarAutoHide', () => {
  let uninstall: () => void;

  beforeEach(() => {
    vi.useFakeTimers();
    document.body.innerHTML = '';
    uninstall = installScrollbarAutoHide();
  });

  afterEach(() => {
    uninstall();
    vi.useRealTimers();
  });

  /** 스크롤 가능한 div 하나를 만들어 붙인다. */
  function makeScroller() {
    const el = document.createElement('div');
    document.body.appendChild(el);
    return el;
  }

  it('버블링하지 않는 scroll 이벤트도 캡처 단계로 잡아 data-scrolling을 세팅한다', () => {
    const el = makeScroller();
    expect(el.hasAttribute('data-scrolling')).toBe(false);

    // bubbles:false — 실제 브라우저의 엘리먼트 scroll 이벤트와 동일한 조건
    el.dispatchEvent(new Event('scroll', { bubbles: false }));

    expect(el.getAttribute('data-scrolling')).toBe('true');
  });

  it('유휴 800ms가 지나면 속성을 제거한다', () => {
    const el = makeScroller();
    el.dispatchEvent(new Event('scroll'));

    vi.advanceTimersByTime(SCROLLBAR_IDLE_MS - 1);
    expect(el.hasAttribute('data-scrolling')).toBe(true);

    vi.advanceTimersByTime(1);
    expect(el.hasAttribute('data-scrolling')).toBe(false);
  });

  it('스크롤이 이어지면 타이머가 갱신된다', () => {
    const el = makeScroller();
    el.dispatchEvent(new Event('scroll'));
    vi.advanceTimersByTime(SCROLLBAR_IDLE_MS - 10);
    el.dispatchEvent(new Event('scroll'));

    // 첫 이벤트 기준 800ms는 지났지만 두 번째 이벤트가 타이머를 리셋했다
    vi.advanceTimersByTime(20);
    expect(el.hasAttribute('data-scrolling')).toBe(true);

    vi.advanceTimersByTime(SCROLLBAR_IDLE_MS);
    expect(el.hasAttribute('data-scrolling')).toBe(false);
  });

  it('스크롤 컨테이너마다 타이머가 독립적이다', () => {
    // 중첩 스크롤러(예: 데이터 탭의 main + 테이블)가 서로의 타이머를 지우면
    // 한쪽 스크롤바가 스크롤 중인데 꺼지는 현상이 생긴다.
    const a = makeScroller();
    const b = makeScroller();

    a.dispatchEvent(new Event('scroll'));
    vi.advanceTimersByTime(SCROLLBAR_IDLE_MS - 100);
    b.dispatchEvent(new Event('scroll'));
    vi.advanceTimersByTime(100);

    expect(a.hasAttribute('data-scrolling')).toBe(false); // a는 800ms 경과
    expect(b.hasAttribute('data-scrolling')).toBe(true); // b는 아직 100ms
  });

  it('document 스크롤은 documentElement에 표시한다', () => {
    // scroll 이벤트의 target이 Document인 경우(문서 스크롤) 속성을 붙일 엘리먼트가 없다.
    document.dispatchEvent(new Event('scroll'));
    expect(document.documentElement.hasAttribute('data-scrolling')).toBe(true);
  });

  it('uninstall 후에는 더 이상 반응하지 않고 잔여 속성도 정리한다', () => {
    const el = makeScroller();
    el.dispatchEvent(new Event('scroll'));
    expect(el.hasAttribute('data-scrolling')).toBe(true);

    uninstall();
    expect(el.hasAttribute('data-scrolling')).toBe(false);

    el.dispatchEvent(new Event('scroll'));
    expect(el.hasAttribute('data-scrolling')).toBe(false);

    uninstall = () => {}; // afterEach 중복 호출 방지
  });
});
