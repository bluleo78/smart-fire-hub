/**
 * useCanvasBackground 단위 테스트 (#507).
 *
 * 무엇: `--background` CSS 변수 계산값을 Cytoscape 호환 `rgb()`로 변환해 읽어오고,
 *       documentElement의 class가 바뀌면(다크/라이트 전환 · 테마 컬러 전환 모두 class 변경으로
 *       구현돼 있다) 값을 다시 읽는지 검증한다.
 * 왜:   테마 컬러(indigo/ocean/sunset)마다 `--background`가 다른데, 캔버스(cytoscape) 스타일시트가
 *       리터럴 색을 고정으로 쓰던 것이 원인이었다. 이 훅이 최신값을 못 따라가면 수정 자체가 무의미해진다.
 *       **회귀(#507 크로스체크)**: 원시 `oklch(...)` 문자열을 그대로 반환하면 Cytoscape 색상 파서가
 *       이를 이해하지 못해 파싱 실패로 폴백된다 — 이 훅은 반드시 `rgb()`로 변환된 값을 반환해야 한다.
 *       기대 rgb 값은 각 oklch 좌표를 OKLab→선형 sRGB 변환식으로 직접 계산해 얻었다
 *       (`oklch(0.13 0.015 280)` → `rgb(6, 7, 13)`, `oklch(0.12 0.015 210)` → `rgb(1, 7, 8)`).
 */
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { useCanvasBackground } from './useCanvasBackground';

describe('useCanvasBackground', () => {
  const root = document.documentElement;

  beforeEach(() => {
    root.style.setProperty('--background', 'oklch(0.13 0.015 280)');
  });

  afterEach(() => {
    root.style.removeProperty('--background');
    root.className = '';
  });

  it('초기 렌더에서 현재 --background 계산값을 rgb()로 변환해 반환한다', () => {
    const { result } = renderHook(() => useCanvasBackground());
    expect(result.current).toBe('rgb(6, 7, 13)');
  });

  it('documentElement class가 바뀌면(테마 컬러 전환) 최신 --background를 rgb()로 변환해 갱신한다', async () => {
    const { result } = renderHook(() => useCanvasBackground());
    expect(result.current).toBe('rgb(6, 7, 13)');

    // 실제 테마 컬러 전환(useThemeColor)이 하는 일과 동일 — class를 바꾸고, 그 결과로
    // --background 계산값이 바뀌었다고 가정한다(jsdom은 실제 CSS cascade를 계산하지 않으므로
    // class 변경과 함께 변수 값도 함께 세팅해 MutationObserver 트리거만 검증한다).
    act(() => {
      root.style.setProperty('--background', 'oklch(0.12 0.015 210)');
      root.classList.add('theme-ocean');
    });

    // MutationObserver 콜백은 마이크로태스크로 큐잉되므로 한 틱 기다린다.
    await act(async () => {
      await Promise.resolve();
    });

    expect(result.current).toBe('rgb(1, 7, 8)');
  });

  it('oklch가 아닌 값(이미 호환 형식)은 변환 없이 그대로 통과시킨다', () => {
    root.style.setProperty('--background', '#121110');
    const { result } = renderHook(() => useCanvasBackground());
    expect(result.current).toBe('#121110');
  });
});
