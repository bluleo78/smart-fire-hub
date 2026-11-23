import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useRef, useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { MAIN_CONTENT_ID } from '@/lib/focus-lost';

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

/**
 * WD-980: 「본문으로 건너뛰기」 뒤(main#main-content 에 포커스·tabindex=-1)에 `autoFocus` 가 있는 대화상자(#337 경로)가
 * 포커스 없이 열렸다 닫히면, 전역 포커스 이력의 오래된 항목(건너뛰기 링크)이 아니라
 * 대체 지점(restoreFocusRef) → 없으면 본문(main)으로 돌아와야 한다.
 */
describe('useDialogFocusRestore — 건너뛰기 뒤 autoFocus 대화상자 (WD-980)', () => {
  function SkipHarness({ withRestoreRef }: { withRestoreRef: boolean }) {
    const [open, setOpen] = useState(false);
    const restoreRef = useRef<HTMLButtonElement>(null);
    return (
      <div>
        <a href={`#${MAIN_CONTENT_ID}`}>본문으로 건너뛰기</a>
        <main id={MAIN_CONTENT_ID} tabIndex={-1}>
          <button type="button" onClick={() => setOpen(true)}>
            열기
          </button>
          <button type="button" ref={restoreRef}>
            대체 지점
          </button>
        </main>
        <AlertDialog open={open} onOpenChange={setOpen}>
          <AlertDialogContent restoreFocusRef={withRestoreRef ? restoreRef : undefined}>
            <AlertDialogTitle>확인</AlertDialogTitle>
            <AlertDialogDescription>정말 실행할까요?</AlertDialogDescription>
            <AlertDialogFooter>
              <AlertDialogCancel autoFocus>취소</AlertDialogCancel>
              <AlertDialogAction>확인</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    );
  }

  it.each([
    [true, '대체 지점'],
    [false, 'main'],
  ])('restoreFocusRef=%s 이면 포커스가 %s 로 돌아오고 건너뛰기 링크로 튕기지 않는다', async (withRestoreRef) => {
    render(<SkipHarness withRestoreRef={withRestoreRef} />);
    // 건너뛰기: 링크 → 본문 순으로 포커스(이력에 링크, main 이 차례로 남는다)
    screen.getByRole('link', { name: '본문으로 건너뛰기' }).focus();
    const main = document.getElementById(MAIN_CONTENT_ID)!;
    main.focus();
    expect(main).toHaveFocus();

    // 버튼에 포커스가 가지 않는 클릭(Safari 흉내) — 포커스는 main 에 남은 채 열린다
    fireEvent.click(screen.getByRole('button', { name: '열기' }));
    expect(await screen.findByRole('button', { name: '취소' })).toHaveFocus();
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    await waitFor(() => expect(screen.queryByRole('button', { name: '취소' })).toBeNull());
    const expected = withRestoreRef ? screen.getByRole('button', { name: '대체 지점' }) : main;
    await waitFor(() => expect(expected).toHaveFocus());
    expect(screen.getByRole('link', { name: '본문으로 건너뛰기' })).not.toHaveFocus();
  });

  // 이력에서 온 오래된 main 은 사용자가 이미 떠난 자리일 수 있다 — tabindex 가 떼어졌으면 다시 붙여 끌어오지 않는다(WD-1039 코드 리뷰)
  it('main 을 떠나 tabindex 가 떼어진 뒤 연 autoFocus 대화상자를 닫아도 main 으로 끌어오지 않는다', async () => {
    render(<SkipHarness withRestoreRef={false} />);
    const main = document.getElementById(MAIN_CONTENT_ID)!;
    main.focus();
    // 사용자가 main 을 떠나(body) 본문을 눌러 임시 tabindex 가 떼어진 상태
    main.blur();
    main.removeAttribute('tabindex');

    fireEvent.click(screen.getByRole('button', { name: '열기' }));
    expect(await screen.findByRole('button', { name: '취소' })).toHaveFocus();
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    await waitFor(() => expect(screen.queryByRole('button', { name: '취소' })).toBeNull());
    await new Promise((r) => requestAnimationFrame(() => r(null)));
    expect(main).not.toHaveFocus();
    expect(main.hasAttribute('tabindex')).toBe(false);
  });
});

/**
 * WD-1039: 건너뛰기 뒤 main 에 초점이 남은 채 마우스로 연 대화상자(onOpenAutoFocus 가 오는 경로)는
 * 닫힐 때 최근 누른 버튼으로 초점을 돌려준다. 하네스 Content 안에는 autoFocus 를 두지 않는다.
 */
describe('useDialogFocusRestore — main 에서 포인터로 연 대화상자 (WD-1039)', () => {
  afterEach(() => vi.restoreAllMocks());

  function PointerHarness({
    withRestoreRef = false,
    rowRemovable = false,
  }: { withRestoreRef?: boolean; rowRemovable?: boolean }) {
    const [open, setOpen] = useState(false);
    const [rowGone, setRowGone] = useState(false);
    const restoreRef = useRef<HTMLButtonElement>(null);
    return (
      <div>
        <button type="button">main 밖</button>
        <main id={MAIN_CONTENT_ID}>
          <p>본문 글자</p>
          <div data-testid="card" onClick={() => setOpen(true)}>
            카드
          </div>
          <div data-testid="list">
            {!rowGone && (
              <button type="button" onClick={() => setOpen(true)}>
                행 버튼
              </button>
            )}
          </div>
          <button type="button" ref={restoreRef}>
            대체 지점
          </button>
        </main>
        <AlertDialog open={open} onOpenChange={setOpen}>
          <AlertDialogContent restoreFocusRef={withRestoreRef ? restoreRef : undefined}>
            <AlertDialogTitle>확인</AlertDialogTitle>
            <AlertDialogDescription>정말 실행할까요?</AlertDialogDescription>
            <AlertDialogFooter>
              <AlertDialogCancel>취소</AlertDialogCancel>
              <AlertDialogAction
                onClick={() => {
                  if (rowRemovable) setRowGone(true);
                }}
              >
                확인
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    );
  }

  /** 건너뛰기 상태: main 에 tabindex=-1 을 붙이고 초점을 준다 */
  function skipToMain() {
    const main = document.getElementById(MAIN_CONTENT_ID)!;
    main.setAttribute('tabindex', '-1');
    main.focus();
    expect(main).toHaveFocus();
    return main;
  }

  /** 버튼에 초점 없이 pointerdown 만 보내고(Safari 흉내) 연다 */
  function pointerOpen(name: string) {
    const btn = screen.getByRole('button', { name });
    fireEvent.pointerDown(btn);
    fireEvent.click(btn);
    return btn;
  }

  const flushRaf = () =>
    new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));

  async function closeByCancel() {
    fireEvent.click(await screen.findByRole('button', { name: '취소' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: '취소' })).toBeNull());
    await flushRaf();
  }

  it('취소로 닫으면(행이 남음) 누른 버튼으로 돌아간다', async () => {
    render(<PointerHarness />);
    skipToMain();
    const row = pointerOpen('행 버튼');
    await closeByCancel();
    expect(row).toHaveFocus();
  });

  it('행이 사라지면 restoreFocusRef 로 간다', async () => {
    render(<PointerHarness withRestoreRef rowRemovable />);
    skipToMain();
    pointerOpen('행 버튼');
    fireEvent.click(await screen.findByRole('button', { name: '확인' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: '확인' })).toBeNull());
    await flushRaf();
    expect(screen.getByRole('button', { name: '대체 지점' })).toHaveFocus();
  });

  it('restoreFocusRef 없이 행이 사라지면 살아 있는 조상 컨테이너로 간다', async () => {
    render(<PointerHarness rowRemovable />);
    skipToMain();
    pointerOpen('행 버튼');
    fireEvent.click(await screen.findByRole('button', { name: '확인' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: '확인' })).toBeNull());
    await flushRaf();
    expect(screen.getByTestId('list')).toHaveFocus();
  });

  it('누른 지 1초가 지나서 열면 누른 요소를 쓰지 않는다', async () => {
    render(<PointerHarness />);
    const main = skipToMain();
    const now = vi.spyOn(performance, 'now');
    now.mockReturnValue(10000);
    const row = screen.getByRole('button', { name: '행 버튼' });
    fireEvent.pointerDown(row);
    now.mockReturnValue(11001);
    fireEvent.click(row);
    await closeByCancel();
    expect(row).not.toHaveFocus();
    expect(main).toHaveFocus();
  });

  it('main 밖을 누른 것은 쓰지 않는다', async () => {
    render(<PointerHarness />);
    const main = skipToMain();
    // main 밖 버튼을 누른 뒤, 초점은 main 에 둔 채 행 버튼 클릭만(pointerdown 없이) 보낸다
    fireEvent.pointerDown(screen.getByRole('button', { name: 'main 밖' }));
    fireEvent.click(screen.getByRole('button', { name: '행 버튼' }));
    await closeByCancel();
    expect(screen.getByRole('button', { name: 'main 밖' })).not.toHaveFocus();
    expect(main).toHaveFocus();
  });

  it('대화상자 안을 누른 기록은 다음 열림의 트리거로 쓰지 않는다', async () => {
    render(<PointerHarness />);
    const main = skipToMain();
    // 누름 없이 열고, 대화상자 안(취소)을 눌러 닫는다
    fireEvent.click(screen.getByRole('button', { name: '행 버튼' }));
    fireEvent.pointerDown(await screen.findByRole('button', { name: '취소' }));
    await closeByCancel();
    expect(main).toHaveFocus();
    // 다시 누름 없이 열고 닫아도 지난 누름(대화상자 안)으로 가지 않고 main 으로 돌아온다
    fireEvent.click(screen.getByRole('button', { name: '행 버튼' }));
    await closeByCancel();
    expect(main).toHaveFocus();
  });

  it('열 때의 main 이 그새 tabindex 를 잃었어도 대체 지점이 없으면 main 으로 돌아간다', async () => {
    render(<PointerHarness />);
    const main = skipToMain();
    // pointer 없이 연다(이전 시험의 누름 기록이 1초 안에 남지 않도록 시계를 충분히 앞으로)
    const now = vi.spyOn(performance, 'now');
    now.mockReturnValue(performance.timeOrigin + 1e7);
    fireEvent.click(screen.getByRole('button', { name: '행 버튼' }));
    await screen.findByRole('button', { name: '취소' });
    main.removeAttribute('tabindex');
    await closeByCancel();
    expect(main).toHaveFocus();
  });

  it('main 안의 포커스 불가 요소(div)를 눌러 열고 행이 사라지면 restoreFocusRef 로 간다', async () => {
    render(<PointerHarness withRestoreRef rowRemovable />);
    skipToMain();
    const card = screen.getByTestId('card');
    fireEvent.pointerDown(card);
    fireEvent.click(card);
    fireEvent.click(await screen.findByRole('button', { name: '확인' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: '확인' })).toBeNull());
    await flushRaf();
    expect(screen.getByRole('button', { name: '대체 지점' })).toHaveFocus();
  });

  it('main 의 글자(버튼 아닌 곳)를 눌러 열어도 trigger 는 main 이 아니다 — 삭제 때 restoreFocusRef 로 간다', async () => {
    render(<PointerHarness withRestoreRef rowRemovable />);
    skipToMain();
    fireEvent.pointerDown(screen.getByText('본문 글자'));
    fireEvent.click(screen.getByRole('button', { name: '행 버튼' }));
    fireEvent.click(await screen.findByRole('button', { name: '확인' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: '확인' })).toBeNull());
    await flushRaf();
    expect(screen.getByRole('button', { name: '대체 지점' })).toHaveFocus();
  });

  /** 닫을 때 트리거가 disabled 로 바뀌는(DOM 에 붙어 있지만 초점을 못 받는) 일반 열림 하네스 */
  function DisabledHarness({ withRestoreRef }: { withRestoreRef: boolean }) {
    const [open, setOpen] = useState(false);
    const [disabled, setDisabled] = useState(false);
    const restoreRef = useRef<HTMLButtonElement>(null);
    return (
      <div>
        <div data-testid="wrap">
          <button type="button" disabled={disabled} onClick={() => setOpen(true)}>
            트리거
          </button>
        </div>
        <button type="button" ref={restoreRef}>
          대체 지점
        </button>
        <AlertDialog open={open} onOpenChange={setOpen}>
          <AlertDialogContent restoreFocusRef={withRestoreRef ? restoreRef : undefined}>
            <AlertDialogTitle>확인</AlertDialogTitle>
            <AlertDialogDescription>정말 실행할까요?</AlertDialogDescription>
            <AlertDialogFooter>
              <AlertDialogCancel>취소</AlertDialogCancel>
              <AlertDialogAction onClick={() => setDisabled(true)}>확인</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    );
  }

  it.each([
    [true, '대체 지점'],
    [false, '조상 컨테이너'],
  ])('트리거가 닫힘과 함께 disabled 가 되면(restoreFocusRef=%s) %s 로 간다', async (withRestoreRef) => {
    render(<DisabledHarness withRestoreRef={withRestoreRef} />);
    const trigger = screen.getByRole('button', { name: '트리거' });
    trigger.focus();
    fireEvent.click(trigger);
    fireEvent.click(await screen.findByRole('button', { name: '확인' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: '확인' })).toBeNull());
    await flushRaf();
    const expected = withRestoreRef
      ? screen.getByRole('button', { name: '대체 지점' })
      : screen.getByTestId('wrap');
    expect(expected).toHaveFocus();
  });
});
