/**
 * ChatInput 열림 포커스 단위 테스트 (WD-579)
 *
 * 패널 열림 여부(focusOnOpen)가 false→true 로 바뀔 때 입력창에 포커스하는 동작을 검증한다.
 * 스트리밍 SSE 를 E2E 로 붙잡아 두기 어려워 disabled→enabled 전환·가드는 여기서 다룬다.
 */
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { RefObject } from 'react';
import { StrictMode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MAX_CHAT_MESSAGE_LENGTH } from '../../lib/ai-chat-limits';
import { MAIN_CONTENT_ID } from '../../lib/focus-lost';
import { ChatInput } from './ChatInput';

/** 실제 AIChatPanel 처럼 data-ai-chat-panel 경계 안에 ChatInput 을 둔다 */
function Panel(props: {
  focusOnOpen?: boolean;
  isStreaming?: boolean;
  focusOriginRef?: RefObject<Element | null>;
}) {
  return (
    <div>
      <input aria-label="바깥 입력" />
      <div data-ai-chat-panel>
        <button type="button">패널 안 버튼</button>
        <ChatInput onSend={vi.fn()} {...props} />
      </div>
    </div>
  );
}

const textarea = () => screen.getByPlaceholderText('메시지를 입력하세요...');

afterEach(() => {
  (document.activeElement as HTMLElement | null)?.blur();
});

describe('ChatInput focusOnOpen (WD-579)', () => {
  it('닫힌 상태(focusOnOpen=false)로 마운트되면 포커스하지 않는다(닫힌 채 마운트되는 사이드 패널)', () => {
    render(<Panel focusOnOpen={false} />);
    expect(textarea()).not.toHaveFocus();
    expect(document.activeElement).toBe(document.body);
  });

  it('열린 상태로 마운트되면 포커스한다(fullscreen 처럼 열리며 마운트)', () => {
    render(<Panel focusOnOpen />);
    expect(textarea()).toHaveFocus();
  });

  it('열릴 때마다 포커스하고, preventScroll 로 호출한다', () => {
    const { rerender } = render(<Panel focusOnOpen={false} />);
    const spy = vi.spyOn(HTMLTextAreaElement.prototype, 'focus');
    rerender(<Panel focusOnOpen />);
    expect(textarea()).toHaveFocus();
    expect(spy).toHaveBeenLastCalledWith({ preventScroll: true });

    // 같은 값의 재렌더는 새 요청이 아니다 — 패널 안 다른 컨트롤에서 포커스를 끌어오지 않는다.
    screen.getByRole('button', { name: '패널 안 버튼' }).focus();
    rerender(<Panel focusOnOpen />);
    expect(screen.getByRole('button', { name: '패널 안 버튼' })).toHaveFocus();

    // 닫았다가 다시 열면 → 다시 포커스.
    screen.getByRole('button', { name: '패널 안 버튼' }).focus();
    rerender(<Panel focusOnOpen={false} />);
    rerender(<Panel focusOnOpen />);
    expect(textarea()).toHaveFocus();
    spy.mockRestore();
  });

  it('스트리밍 중(disabled) 요청은 대기했다가 스트리밍이 끝나면 포커스한다', () => {
    const { rerender } = render(<Panel focusOnOpen={false} isStreaming />);
    rerender(<Panel focusOnOpen isStreaming />);
    expect(textarea()).toBeDisabled();
    expect(textarea()).not.toHaveFocus();

    rerender(<Panel focusOnOpen isStreaming={false} />);
    expect(textarea()).toHaveFocus();
  });

  it('대기 중 사용자가 패널 밖으로 포커스를 옮겼으면 빼앗지 않고 대기만 해제한다', () => {
    const { rerender } = render(<Panel focusOnOpen isStreaming />);
    const outside = screen.getByRole('textbox', { name: '바깥 입력' });
    outside.focus();

    rerender(<Panel focusOnOpen isStreaming={false} />);
    expect(outside).toHaveFocus();

    // 대기는 해제됐으므로 이후 스트리밍이 다시 시작·종료되어도 포커스를 끌어오지 않는다.
    rerender(<Panel focusOnOpen isStreaming />);
    (document.activeElement as HTMLElement | null)?.blur();
    rerender(<Panel focusOnOpen isStreaming={false} />);
    expect(textarea()).not.toHaveFocus();
  });

  it('초점이 body 면 활성화될 때 입력창으로 옮긴다', () => {
    const { rerender } = render(<Panel focusOnOpen isStreaming />);
    (document.activeElement as HTMLElement | null)?.blur();
    expect(document.body).toHaveFocus();
    rerender(<Panel focusOnOpen isStreaming={false} />);
    expect(textarea()).toHaveFocus();
  });

  /** 건너뛰기 뒤처럼 main(tabindex=-1)을 공유 jsdom 문서에 붙이고, 단언이 실패해도 남지 않게 지운다 */
  function withMain(run: (main: HTMLElement) => void) {
    const main = document.createElement('main');
    main.id = MAIN_CONTENT_ID;
    main.tabIndex = -1;
    document.body.appendChild(main);
    try {
      run(main);
    } finally {
      main.remove();
    }
  }

  it('대기 중 초점이 본문 main 으로 옮겨지면 활성화돼도 빼앗지 않는다 (WD-1039)', () => {
    withMain((main) => {
      // 열던 자리는 다른 버튼이다
      const originRef: RefObject<Element | null> = { current: null };
      const { rerender } = render(<Panel focusOnOpen isStreaming focusOriginRef={originRef} />);
      originRef.current = screen.getByRole('button', { name: '패널 안 버튼' });
      main.focus();
      expect(main).toHaveFocus();
      rerender(<Panel focusOnOpen isStreaming={false} focusOriginRef={originRef} />);
      expect(main).toHaveFocus();
    });
  });

  it('열던 자리가 main 이면 활성화될 때 입력창으로 옮긴다 (WD-1039)', () => {
    withMain((main) => {
      const originRef: RefObject<Element | null> = { current: main };
      const { rerender } = render(<Panel focusOnOpen isStreaming focusOriginRef={originRef} />);
      main.focus();
      expect(main).toHaveFocus();
      rerender(<Panel focusOnOpen isStreaming={false} focusOriginRef={originRef} />);
      expect(textarea()).toHaveFocus();
    });
  });

  it('열던 순간의 자리(패널 밖, 예: AI 상태 칩)에 포커스가 그대로 있으면 입력창으로 옮긴다', () => {
    const originRef: RefObject<Element | null> = { current: null };
    const { rerender } = render(<Panel focusOnOpen={false} focusOriginRef={originRef} />);
    const outside = screen.getByRole('textbox', { name: '바깥 입력' });
    outside.focus();
    // 프로바이더가 열림 커밋에서 기록하는 값을 흉내 낸다 — 열 때의 포커스 = 바깥 요소.
    originRef.current = outside;
    rerender(<Panel focusOnOpen focusOriginRef={originRef} />);
    expect(textarea()).toHaveFocus();
  });

  it('열린 뒤 포커스가 열던 자리와 다른 패널 밖 요소로 옮겨졌으면 빼앗지 않는다', () => {
    const originRef: RefObject<Element | null> = { current: document.body };
    const { rerender } = render(<Panel focusOnOpen isStreaming focusOriginRef={originRef} />);
    const outside = screen.getByRole('textbox', { name: '바깥 입력' });
    outside.focus();
    rerender(<Panel focusOnOpen isStreaming={false} focusOriginRef={originRef} />);
    expect(outside).toHaveFocus();
  });

  it('대기 중 패널이 닫히면 대기가 해제되어, 이후 스트리밍이 끝나도 포커스하지 않는다', () => {
    const { rerender } = render(<Panel focusOnOpen isStreaming />);
    rerender(<Panel focusOnOpen={false} isStreaming />);
    rerender(<Panel focusOnOpen={false} isStreaming={false} />);
    expect(textarea()).not.toHaveFocus();
  });

  it('대기 중 포커스가 같은 채팅 패널 안에 있으면 입력창으로 옮긴다', () => {
    const { rerender } = render(<Panel focusOnOpen isStreaming />);
    screen.getByRole('button', { name: '패널 안 버튼' }).focus();
    rerender(<Panel focusOnOpen isStreaming={false} />);
    expect(textarea()).toHaveFocus();
  });
});

describe('ChatInput 입력란 이름 (WD-590·WD-643)', () => {
  it('호출부가 아무것도 넘기지 않아도 입력란은 「메시지 입력」 이름을 갖는다(AIChatPanel·AINativeMode 공통)', () => {
    // AINativeMode 는 UI 진입로가 없어 E2E 로 띄울 수 없으므로, 그 화면이 쓰는 그대로(이름 prop 없이) 여기서 지킨다.
    render(<ChatInput onSend={vi.fn()} />);
    expect(screen.getByRole('textbox', { name: '메시지 입력' })).toBe(textarea());
  });
});

/**
 * 메시지 길이 상한 (WD-631) — 서버 상한(50,000자)을 넘으면 전송을 막고 안내를 보이되, 글은 자르지 않는다.
 * 큰 문자열 입력은 E2E 에서도 다루지만, 경계값(정확히 상한 / 공백 trim)은 여기서 빠르게 검증한다.
 */
describe('ChatInput 메시지 길이 상한 (WD-631)', () => {
  const sendButton = () => screen.getByRole('button', { name: '메시지 전송' });

  it('정확히 상한(50,000자)이면 보낼 수 있고 안내가 없다', () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);
    fireEvent.change(textarea(), { target: { value: 'a'.repeat(MAX_CHAT_MESSAGE_LENGTH) } });
    expect(sendButton()).toBeEnabled();
    expect(screen.queryByText(/자 이하로 입력하세요/)).toBeNull();
    fireEvent.click(sendButton());
    expect(onSend).toHaveBeenCalledWith('a'.repeat(MAX_CHAT_MESSAGE_LENGTH), undefined);
  });

  it('상한을 넘으면 전송 버튼이 비활성되고 현재 길이를 담은 안내가 입력창과 이어진다 — Enter 로도 보내지 않고 글은 그대로', () => {
    const onSend = vi.fn();
    render(<ChatInput onSend={onSend} />);
    const value = 'a'.repeat(MAX_CHAT_MESSAGE_LENGTH + 1);
    fireEvent.change(textarea(), { target: { value } });

    expect(sendButton()).toBeDisabled();
    const hint = screen.getByText('메시지는 50,000자 이하로 입력하세요. (현재 50,001자)');
    expect(textarea()).toHaveAttribute('aria-describedby', hint.id);
    expect(textarea()).toHaveAttribute('aria-invalid', 'true');

    fireEvent.keyDown(textarea(), { key: 'Enter' });
    expect(onSend).not.toHaveBeenCalled();
    expect(textarea()).toHaveValue(value);
  });

  it('앞뒤 공백은 보내지 않으므로 길이에 세지 않는다', () => {
    render(<ChatInput onSend={vi.fn()} />);
    fireEvent.change(textarea(), { target: { value: `  ${'a'.repeat(MAX_CHAT_MESSAGE_LENGTH)}  ` } });
    expect(sendButton()).toBeEnabled();
  });

  it('줄여서 상한 안으로 들어오면 안내가 사라지고 다시 보낼 수 있다', () => {
    render(<ChatInput onSend={vi.fn()} />);
    fireEvent.change(textarea(), { target: { value: 'a'.repeat(MAX_CHAT_MESSAGE_LENGTH + 10) } });
    expect(sendButton()).toBeDisabled();
    fireEvent.change(textarea(), { target: { value: 'a'.repeat(10) } });
    expect(sendButton()).toBeEnabled();
    expect(screen.queryByText(/자 이하로 입력하세요/)).toBeNull();
    expect(textarea()).not.toHaveAttribute('aria-describedby');
  });
});

describe('ChatInput 보내지 못한 글 되돌리기 (WD-1004)', () => {
  it('onSend 가 false 로 풀리면 글과 첨부를 입력창에 돌려준다', async () => {
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    const onSend = vi.fn(() => Promise.resolve(false));
    const { container } = render(<ChatInput onSend={onSend} />);
    fireEvent.change(textarea(), { target: { value: '안녕' } });
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['a'], 'a.png', { type: 'image/png' });
    fireEvent.change(input, { target: { files: [file] } });
    expect(await screen.findByText('a.png')).toBeInTheDocument();

    fireEvent.keyDown(textarea(), { key: 'Enter' });
    // 보내자마자 비운다
    expect(textarea()).toHaveValue('');
    expect(onSend).toHaveBeenCalledWith('안녕', [file]);

    // 보내지 못했다는 결과가 오면 되돌린다
    await waitFor(() => expect(textarea()).toHaveValue('안녕'));
    expect(screen.getByText('a.png')).toBeInTheDocument();
  });

  it('그 사이 새로 쓴 글이 있으면 덮지 않는다', async () => {
    let resolve!: (v: boolean) => void;
    const onSend = vi.fn(() => new Promise<boolean>(r => { resolve = r; }));
    render(<ChatInput onSend={onSend} />);
    fireEvent.change(textarea(), { target: { value: '첫 글' } });
    fireEvent.keyDown(textarea(), { key: 'Enter' });
    fireEvent.change(textarea(), { target: { value: '새 글' } });
    resolve(false);
    await Promise.resolve();
    await Promise.resolve();
    expect(textarea()).toHaveValue('새 글');
  });

  it('onSend 가 true 로 풀리면 비운 채로 둔다', async () => {
    const onSend = vi.fn(() => Promise.resolve(true));
    render(<ChatInput onSend={onSend} />);
    fireEvent.change(textarea(), { target: { value: '안녕' } });
    fireEvent.keyDown(textarea(), { key: 'Enter' });
    await Promise.resolve();
    await Promise.resolve();
    expect(textarea()).toHaveValue('');
  });
});

describe('ChatInput restoreDraft (WD-1029)', () => {
  it('입력창이 비어 있으면 restoreDraft 로 채우고 한 번 소비한다', () => {
    const consumed = vi.fn();
    render(<ChatInput onSend={vi.fn()} restoreDraft="남은 글" onRestoreDraftConsumed={consumed} />);
    expect(screen.getByLabelText('메시지 입력')).toHaveValue('남은 글');
    expect(consumed).toHaveBeenCalledTimes(1);
  });

  // StrictMode 이중 실행에도 초안을 한 번만 반영한다 — 비었거나 공백뿐이면 채우고, 쓰던 글이 있으면 줄을 바꿔 이어 붙인다 (WD-1050)
  it.each([
    ['빈 입력', '', '남은 글'],
    ['공백뿐인 입력', '  ', '남은 글'],
    ['쓰던 글', '쓰던 글', '쓰던 글\n남은 글'],
  ])('StrictMode 에서 %s 일 때 초안을 한 번만 반영한다 (WD-1050)', (_name, typed, expected) => {
    const { rerender } = render(
      <StrictMode>
        <ChatInput onSend={vi.fn()} onRestoreDraftConsumed={vi.fn()} />
      </StrictMode>,
    );
    if (typed) fireEvent.change(screen.getByLabelText('메시지 입력'), { target: { value: typed } });
    rerender(
      <StrictMode>
        <ChatInput onSend={vi.fn()} restoreDraft="남은 글" onRestoreDraftConsumed={vi.fn()} />
      </StrictMode>,
    );
    expect(screen.getByLabelText('메시지 입력')).toHaveValue(expected);
  });

  it('이미 쓴 글이 있으면 줄을 바꿔 뒤에 이어 붙이고 한 번 소비한다', () => {
    const consumed = vi.fn();
    const { rerender } = render(<ChatInput onSend={vi.fn()} onRestoreDraftConsumed={consumed} />);
    fireEvent.change(screen.getByLabelText('메시지 입력'), { target: { value: '쓰던 글' } });
    rerender(<ChatInput onSend={vi.fn()} restoreDraft="남은 글" onRestoreDraftConsumed={consumed} />);
    expect(screen.getByLabelText('메시지 입력')).toHaveValue('쓰던 글\n남은 글');
    expect(consumed).toHaveBeenCalledTimes(1);
  });

  it('되돌린 글을 반영한 뒤 입력창 높이를 다시 맞춘다 (WD-1050)', () => {
    const desc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollHeight');
    Object.defineProperty(HTMLElement.prototype, 'scrollHeight', { configurable: true, get: () => 90 });
    try {
      render(<ChatInput onSend={vi.fn()} restoreDraft={'첫 줄\n둘째 줄'} onRestoreDraftConsumed={vi.fn()} />);
      expect(screen.getByLabelText('메시지 입력').style.height).toBe('90px');
    } finally {
      if (desc) Object.defineProperty(HTMLElement.prototype, 'scrollHeight', desc);
      else delete (HTMLElement.prototype as unknown as Record<string, unknown>).scrollHeight;
    }
  });
});
