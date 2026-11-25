import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { Dialog, DialogContent, DialogTitle } from './dialog';

/**
 * 공용 DialogContent 높이 제한·세로 스크롤 단위 테스트(WD-1110).
 *
 * 내용이 길면 대화상자가 뷰포트를 벗어나 제목·닫기 버튼이 화면 밖으로 밀려나고,
 * Radix 가 <body> 에 overflow: hidden 을 걸어 문서 스크롤도 막힌다.
 * 그래서 기본 클래스에 높이 제한(max-h)과 세로 스크롤(overflow-y-auto)이 반드시 있는지 고정한다.
 * 실제 브라우저에서 스크롤이 되는지는 E2E(작업 2)가 맡는다.
 */
describe('DialogContent — 높이 제한·세로 스크롤 (WD-1110)', () => {
  /** data-testid 없는 공용 요소는 data-slot 선택자로 찾는다 — 슬롯 속성이 그대로 있는지도 함께 확인된다. */
  function getContent(): HTMLElement {
    const el = document.querySelector('[data-slot="dialog-content"]');
    if (!el) throw new Error('dialog-content 요소를 찾지 못했다');
    return el as HTMLElement;
  }

  it('기본 클래스에 max-h-[calc(100dvh-4rem)] 와 overflow-y-auto 가 모두 있다', () => {
    render(
      <Dialog open>
        {/* Description 없이 여는 테스트 — aria-describedby 를 끌어 Radix 의 "Missing Description" 경고를 내보내지 않는다 */}
        <DialogContent showCloseButton={false} aria-describedby={undefined}>
          <DialogTitle>긴 제목</DialogTitle>
        </DialogContent>
      </Dialog>,
    );
    const content = getContent();
    // 클래스 **토큰**과 정확히 비교한다 — className 전체를 contain 로 검사하면
    // 'hover:overflow-y-auto' 같은 변형 클래스만 있어도 통과해, 기본 클래스가 빠진 회귀를 놓친다.
    expect(content.classList.contains('max-h-[calc(100dvh-4rem)]')).toBe(true);
    expect(content.classList.contains('overflow-y-auto')).toBe(true);
  });

  it('자식과 닫기 버튼이 그대로 렌더링되고 클릭이 전달된다', () => {
    const onClick = vi.fn();
    render(
      <Dialog open>
        {/* 내장 닫기(X) 와 이름이 겹치지 않게 끈다 — 여기서는 자식으로 넣은 닫기 버튼을 검증한다 */}
        <DialogContent showCloseButton={false} aria-describedby={undefined}>
          <DialogTitle>긴 제목</DialogTitle>
          <button type="button" onClick={onClick}>
            닫기
          </button>
        </DialogContent>
      </Dialog>,
    );
    expect(screen.getByText('긴 제목')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
