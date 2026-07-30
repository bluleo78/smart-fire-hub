import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';

import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogTitle,
} from '@/components/ui/alert-dialog';

/**
 * 공유 포커스 복귀 훅(#328)의 회귀 가드 — 특히 #337 로 드러난
 * "다이얼로그 내부에 `autoFocus` 가 있으면 `onOpenAutoFocus` 가 아예 dispatch 되지 않아
 * 캡처가 통째로 비고 포커스가 <body> 로 유실된다" 는 경로를 고정한다.
 *
 * 이 케이스는 E2E 로 지키기 어렵다 — 실제 페이지에서는 `autoFocus` 를 제거하는 것이 옳은 수정이라
 * 앱 코드에 재현 조건이 남지 않기 때문이다. 그래서 훅 자체를 대상으로 여기서 검증한다.
 */
describe('useDialogFocusRestore (#328, #337)', () => {
  /** 외부 상태로만 제어되는 다이얼로그 — `<AlertDialogTrigger>` 를 쓰지 않는 실제 사용 형태. */
  function Harness({ autoFocusCancel }: { autoFocusCancel: boolean }) {
    const [open, setOpen] = useState(false);
    return (
      <div>
        <button type="button" onClick={() => setOpen(true)}>
          트리거
        </button>
        <AlertDialog open={open} onOpenChange={setOpen}>
          <AlertDialogContent>
            <AlertDialogTitle>확인</AlertDialogTitle>
            <AlertDialogDescription>정말 실행할까요?</AlertDialogDescription>
            <AlertDialogFooter>
              <AlertDialogCancel autoFocus={autoFocusCancel}>취소</AlertDialogCancel>
              <AlertDialogAction>확인</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    );
  }

  /** 다이얼로그를 키보드 사용자와 같은 순서(트리거 포커스 → 열기)로 연다. */
  function openViaTrigger() {
    const trigger = screen.getByRole('button', { name: '트리거' });
    trigger.focus();
    fireEvent.click(trigger);
    return trigger;
  }

  it.each([
    ['autoFocus 없음 (#328 기존 동작)', false],
    ['취소 버튼에 autoFocus (#337 회귀 경로)', true],
  ])('닫으면 포커스가 트리거로 돌아온다 — %s', async (_label, autoFocusCancel) => {
    render(<Harness autoFocusCancel={autoFocusCancel} />);
    const trigger = openViaTrigger();

    // 어느 쪽이든 기본 포커스는 취소 버튼이어야 한다(Radix AlertDialog 기본 동작 — #315 UX).
    expect(await screen.findByRole('button', { name: '취소' })).toHaveFocus();

    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    // 훅의 보정은 requestAnimationFrame 안에서 일어난다.
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    expect(trigger).toHaveFocus();
  });
});
