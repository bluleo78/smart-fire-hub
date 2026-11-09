/**
 * EditConflictDialog 단위 테스트 (WD-678)
 *
 * 진행 중 버튼 잠금을 `disabled` 대신 `aria-disabled` 로 바꾸고, 콜백이 돌려준 Promise 로 잠그는 동작을 검증한다.
 * - 진행 중 세 버튼은 aria-disabled="true" 이고 disabled 가 아니다(키보드 포커스가 body 로 떨어지지 않게).
 * - 진행 중 클릭은 콜백을 부르지 않고, 「계속 편집」은 창을 닫지 않는다. Escape 는 끝나지 않는 요청의 비상구로 닫을 수 있다.
 * - 창을 닫아도 잠금은 Promise 가 끝날 때까지 남아, 다시 연 창에서도 요청이 두 번 나가지 않는다.
 * - 누르면 그 자리에서 잠기고, 콜백의 Promise 가 끝날 때(성공·실패) 풀린다 — 부모 pending 이 오지 않아도 마찬가지다.
 */
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { EditConflictDialog } from './EditConflictDialog';

type Props = Partial<ComponentProps<typeof EditConflictDialog>>;

/**
 * 테스트가 직접 끝낼 수 있는 Promise — 진행 중 상태를 붙잡아 두려고 쓴다.
 * settle()/fail() 은 act 안에서 끝내고 잠금 해제 렌더까지 기다린다.
 */
function deferred() {
  let resolve!: () => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<void>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  const settle = () =>
    act(async () => {
      resolve();
      await promise;
    });
  const fail = () =>
    act(async () => {
      reject(new Error('실패'));
      await promise.catch(() => undefined);
    });
  return { promise, settle, fail };
}

/** 포커스된 요소에서 Escape 를 누른다 — Radix 는 문서의 keydown 으로 닫기 요청을 받는다. */
const pressEscape = () => fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' });

/** 기본 props 로 열린 창을 그리고, rerender 로 props 를 바꿀 수 있게 돌려준다. */
function renderDialog(props: Props = {}) {
  const onReload = props.onReload ?? vi.fn();
  const onOverwrite = props.onOverwrite ?? vi.fn();
  const onOpenChange = props.onOpenChange ?? vi.fn();
  const view = (p: Props) => (
    <EditConflictDialog
      open
      onOpenChange={onOpenChange}
      resourceLabel="데이터셋"
      onReload={onReload}
      onOverwrite={onOverwrite}
      {...props}
      {...p}
    />
  );
  const utils = render(view({}));
  return { ...utils, onReload, onOverwrite, onOpenChange, rerenderWith: (p: Props) => utils.rerender(view(p)) };
}

const cancelButton = () => screen.getByRole('button', { name: '계속 편집' });
const reloadButton = () => screen.getByRole('button', { name: '다시 불러오기' });
const overwriteButton = () => screen.getByRole('button', { name: '내 변경으로 덮어쓰기' });

describe('EditConflictDialog', () => {
  it('진행 중이면 세 버튼이 disabled 가 아니라 aria-disabled="true" 로 잠긴다', () => {
    renderDialog({ pending: true });
    for (const button of [cancelButton(), reloadButton(), overwriteButton()]) {
      expect(button).toHaveAttribute('aria-disabled', 'true');
      expect(button).not.toBeDisabled();
    }
  });

  it('창이 열리면 예전 AlertDialogCancel 처럼 「계속 편집」에 포커스가 가고 같은 data-slot 을 가진다', () => {
    renderDialog();
    expect(cancelButton()).toHaveFocus();
    expect(cancelButton()).toHaveAttribute('data-slot', 'alert-dialog-cancel');
  });

  it('진행 중이 아니면 aria-disabled 가 붙지 않는다', () => {
    renderDialog();
    for (const button of [cancelButton(), reloadButton(), overwriteButton()]) {
      expect(button).not.toHaveAttribute('aria-disabled');
    }
  });

  it('진행 중 클릭은 다시 불러오기·덮어쓰기 콜백을 부르지 않는다', () => {
    const { onReload, onOverwrite } = renderDialog({ pending: true });
    // pointer-events-none 은 jsdom 이 모르므로 fireEvent 로 click 을 직접 보낸다(키보드 Enter 도 click 으로 들어온다)
    fireEvent.click(reloadButton());
    fireEvent.click(overwriteButton());
    expect(onReload).not.toHaveBeenCalled();
    expect(onOverwrite).not.toHaveBeenCalled();
  });

  it('진행 중 「계속 편집」은 창을 닫지 않지만, Escape 는 비상구로 창을 닫는다', () => {
    const { onOpenChange } = renderDialog({ pending: true });
    fireEvent.click(cancelButton());
    expect(onOpenChange).not.toHaveBeenCalled();
    pressEscape();
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('진행 중이 아니면 「계속 편집」은 창을 닫는다', async () => {
    const { onOpenChange } = renderDialog();
    await userEvent.click(cancelButton());
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('진행 중이 아니면 Escape 로 닫힌다', () => {
    const { onOpenChange } = renderDialog();
    pressEscape();
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('부모 pending 없이도 누른 일의 Promise 가 끝나기 전에는 다시 눌러도, 다른 버튼을 눌러도 막히고 「계속 편집」도 닫지 않는다', async () => {
    const work = deferred();
    const onOverwrite = vi.fn(() => work.promise);
    const { onReload, onOpenChange } = renderDialog({ onOverwrite });
    fireEvent.click(overwriteButton());
    fireEvent.click(overwriteButton());
    fireEvent.click(reloadButton());
    fireEvent.click(cancelButton());
    expect(onOverwrite).toHaveBeenCalledTimes(1);
    expect(onReload).not.toHaveBeenCalled();
    expect(onOpenChange).not.toHaveBeenCalled();
    expect(overwriteButton()).toHaveAttribute('aria-disabled', 'true');
    expect(cancelButton()).toHaveAttribute('aria-disabled', 'true');

    await work.settle();
    expect(overwriteButton()).not.toHaveAttribute('aria-disabled');
    fireEvent.click(overwriteButton());
    expect(onOverwrite).toHaveBeenCalledTimes(2);
  });

  it('진행 중 Escape 로 닫았다가 끝나기 전에 다시 열면 여전히 잠겨 있고, 끝난 뒤에야 풀린다', async () => {
    const work = deferred();
    const onOverwrite = vi.fn(() => work.promise);
    const onOpenChange = vi.fn();
    const { rerenderWith } = renderDialog({ onOverwrite, onOpenChange });
    fireEvent.click(overwriteButton());
    pressEscape();
    expect(onOpenChange).toHaveBeenCalledWith(false);

    // 부모가 닫았다가 요청이 끝나기 전에 다시 연다
    rerenderWith({ open: false });
    rerenderWith({ open: true });
    expect(overwriteButton()).toHaveAttribute('aria-disabled', 'true');
    expect(reloadButton()).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(overwriteButton());
    fireEvent.click(reloadButton());
    expect(onOverwrite).toHaveBeenCalledTimes(1);

    await work.settle();
    expect(overwriteButton()).not.toHaveAttribute('aria-disabled');
    fireEvent.click(overwriteButton());
    expect(onOverwrite).toHaveBeenCalledTimes(2);
  });

  it('누른 일이 실패해도(거부) 잠금이 풀려 다시 누를 수 있다', async () => {
    const work = deferred();
    const onOverwrite = vi.fn(() => work.promise);
    renderDialog({ onOverwrite });
    fireEvent.click(overwriteButton());
    await work.fail();
    expect(overwriteButton()).not.toHaveAttribute('aria-disabled');
    fireEvent.click(overwriteButton());
    expect(onOverwrite).toHaveBeenCalledTimes(2);
  });

  it('동기 콜백이면 바로 잠금이 풀리고, 콜백이 던져도 잠금이 남지 않는다', async () => {
    const onReload = vi.fn(() => {
      throw new Error('동기 오류');
    });
    const { onOverwrite } = renderDialog({ onReload });
    await act(async () => {
      fireEvent.click(reloadButton());
    });
    expect(reloadButton()).not.toHaveAttribute('aria-disabled');
    await act(async () => {
      fireEvent.click(overwriteButton());
    });
    expect(overwriteButton()).not.toHaveAttribute('aria-disabled');
    fireEvent.click(overwriteButton());
    expect(onOverwrite).toHaveBeenCalledTimes(2);
  });

  it('부모 pending 이 같은 틱에 참→거짓으로 바뀌어도 잠금은 Promise 를 따르므로 남지 않는다', async () => {
    const work = deferred();
    const onOverwrite = vi.fn(() => work.promise);
    const { rerenderWith } = renderDialog({ onOverwrite });
    fireEvent.click(overwriteButton());
    rerenderWith({ pending: true });
    rerenderWith({ pending: false });
    await work.settle();
    expect(overwriteButton()).not.toHaveAttribute('aria-disabled');
  });
});
