import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { AlertDialog, AlertDialogContent, AlertDialogDescription, AlertDialogTitle } from './alert-dialog';

/**
 * 공용 AlertDialogContent 높이 제한·세로 스크롤 단위 테스트(WD-1123).
 *
 * 참조 목록이 길거나 뷰포트가 낮으면 확인 창이 뷰포트를 벗어나 「삭제」 버튼에 닿을 수 없다.
 * 그래서 기본 클래스에 높이 제한(max-h)과 세로 스크롤(overflow-y-auto)이 반드시 있는지 고정한다.
 * 실제 브라우저 스크롤은 e2e/pages/data/dataset-delete-references.spec.ts 가 맡는다.
 */
describe('AlertDialogContent — 높이 제한·세로 스크롤 (WD-1123)', () => {
  it('기본 클래스에 max-h-[calc(100dvh-4rem)] 와 overflow-y-auto 가 모두 있다', () => {
    render(
      <AlertDialog open>
        <AlertDialogContent>
          <AlertDialogTitle>삭제할까요?</AlertDialogTitle>
          <AlertDialogDescription>설명</AlertDialogDescription>
        </AlertDialogContent>
      </AlertDialog>,
    );
    const content = document.querySelector('[data-slot="alert-dialog-content"]');
    if (!content) throw new Error('alert-dialog-content 요소를 찾지 못했다');
    // 클래스 토큰 정확 비교 — 변형 클래스(hover:overflow-y-auto 등)만 있는 회귀를 놓치지 않는다.
    expect(content.classList.contains('max-h-[calc(100dvh-4rem)]')).toBe(true);
    expect(content.classList.contains('overflow-y-auto')).toBe(true);
  });
});
